package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Default {@link ISubTaskExecutor}.
 * <p>
 * Behavior:
 * <ul>
 * <li>Submits the call to a dedicated {@link ExecutorService} bean
 * {@code loomSubTaskExecutor} so the call is interruptible and bounded.</li>
 * <li>Uses the main {@link ChatClient} bean (re-used, not a separate filtered
 * ChatClient — see SubTaskConfiguration for the rationale) with
 * {@link ChatClient.ChatClientRequestSpec#call()} — synchronous, runs the
 * full Spring AI tool-call loop to final response.</li>
 * <li>On interrupt: cancels the future, returns {@link SubTaskStatus#CANCELLED}.</li>
 * <li>On exception: returns {@link SubTaskStatus#FAILED} with the message.</li>
 * <li>Writes intermediate ChatMemory entries under
 * {@code "{conversationId}--sub--{subTaskId}"} so the main conversation
 * can later see what the sub-task produced.</li>
 * <li>Tracks each in-flight sub-task in a {@link ConcurrentHashMap} so external
 * callers (e.g. {@link SubTaskRegistry#kill(String)} via a registered cancel
 * hook) can interrupt the worker thread via {@link Future#cancel(boolean)}.</li>
 * <li>Per-call filters the {@link IEmbedTool} list passed in via constructor
 * to drop {@code ISubTaskTool}/{@code IScheduleTool} (recursion guard) and
 * {@code IAskUserTool} (sub-tasks execute what the main task planned and return
 * results; questions for the user belong to the main conversation — #1 spec D6).
 * Lazy {@code @Lazy} resolution of the list breaks the bean-graph cycle that
 * would otherwise appear when both this executor and {@code defaultSubTaskTool}
 * are part of {@code List<IEmbedTool>} auto-collection.</li>
 * <li>RBAC 过滤(spec 2026-09-10-subtask-rbac-filter):embed 工具列表先经
 * {@code CapabilityService.visibleToolGroupsFor(username)} 过滤 —— 子任务/定时任务
 * 继承用户角色授权,未授权的 RBAC 工具(render/git/maven/compile)不进子任务工具集
 * (修复此前"universal 的 subtask/schedule 入口绕过 role_tool 授权"的越权面)。
 * 剔除按 (username, droppedSet) 去重记 WARN(Caffeine 有界缓存),同组合再犯降 DEBUG。</li>
 * <li>Propagates the full tool set available to the user: {@link IMcp} callbacks
 * for every MCP server the user can see, plus the filtered {@code embedTools}
 * list.</li>
 * <li>Propagates {@code username} + {@code parentConversationId} into the spec's
 * toolContext so nested tool calls inside the sub-task see the same identity
 * values the main chat uses.</li>
 * </ul>
 */
public class DefaultSubTaskExecutor implements ISubTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultSubTaskExecutor.class);

    private final ChatClient chatClient;
    private final org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor memoryAdvisor;
    private final ExecutorService executor;
    private final IMcp mcp;
    private final List<IEmbedTool> embedTools;
    private final SubTaskRegistry subTaskRegistry;
    private final cn.wubo.spring.ai.loom.agent.capability.CapabilityService capabilityService;
    /**
     * 2026-09-21 (spec § 5.3 B):子任务超时配置。null 时退化为默认 600s,
     * 走无参构造器即可使用默认值(向旧调用方保持兼容)。
     */
    private final LoomAgentProperties.SubTaskProperty subTaskProperty;

    /**
     * RBAC 剔除日志去重(spec 2026-09-10-subtask-rbac-filter D6):
     * key = username + "|" + 排序后 dropped 集,值仅占位。多数用户常态无 RBAC 授权 →
     * dropped 几乎每次非空,逐次 WARN 会刷爆日志;有界缓存把 WARN 封顶在
     * "不同用户 × 不同授权集",授权变更 → dropped 集变 → 新 key → 重新 WARN 一次。
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, Boolean> rbacWarned =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumSize(512)
                    .expireAfterWrite(java.time.Duration.ofHours(1))
                    .build();

    /**
     * Active in-flight sub-task futures, keyed by {@code req.subTaskId()}. Cleared in the worker's finally block.
     */
    private final ConcurrentHashMap<String, Future<?>> activeFutures = new ConcurrentHashMap<>();

    public DefaultSubTaskExecutor(ChatClient chatClient,
                                  BaseChatMemoryAdvisor memoryAdvisor,
                                  ExecutorService executor,
                                  IMcp mcp,
                                  List<IEmbedTool> embedTools,
                                  SubTaskRegistry subTaskRegistry,
                                  cn.wubo.spring.ai.loom.agent.capability.CapabilityService capabilityService) {
        this(chatClient, memoryAdvisor, executor, mcp, embedTools, subTaskRegistry, capabilityService, null);
    }

    /**
     * 2026-09-21 (spec § 5.3 B):新增 {@code subTaskProperty} 形参 —— 子任务超时防御。
     * 调用方（LoomAgentConfiguration SubTaskConfiguration）传 {@code properties.getSubtask()},
     * 老调用方（既有测试 {@code DefaultSubTaskExecutorTest}）走上面的无参回退,
     * 内部 {@code timeoutSeconds=null 时退化到 600s 默认值}。
     */
    public DefaultSubTaskExecutor(ChatClient chatClient,
                                  BaseChatMemoryAdvisor memoryAdvisor,
                                  ExecutorService executor,
                                  IMcp mcp,
                                  List<IEmbedTool> embedTools,
                                  SubTaskRegistry subTaskRegistry,
                                  cn.wubo.spring.ai.loom.agent.capability.CapabilityService capabilityService,
                                  LoomAgentProperties.SubTaskProperty subTaskProperty) {
        this.chatClient = chatClient;
        this.memoryAdvisor = memoryAdvisor;
        this.executor = executor;
        this.mcp = mcp;
        this.embedTools = embedTools;
        this.subTaskRegistry = subTaskRegistry;
        this.capabilityService = capabilityService;
        this.subTaskProperty = subTaskProperty;
    }

    /**
     * The executor contract says {@code req.subTaskId()} is non-null, but in
     * practice callers have been known to forget. Preserve the old behaviour
     * of silently synthesizing an id rather than NPE-ing — the registry
     * accepts the synthetic id just fine.
     */
    private static String safeSubId(String s) {
        return (s == null || s.isBlank()) ? java.util.UUID.randomUUID().toString() : s;
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getClass().getSimpleName() + ": " + r.getMessage();
    }

    @Override
    public SubTaskResult execute(SubTaskRequest req) {
        long startedAt = System.currentTimeMillis();
        log.info("Sub-task start: id={}, parentConv={}, user={}, fromScheduler={}",
                req.subTaskId(), req.parentConversationId(), req.username(), req.fromScheduler());

        // Register BEFORE submitting so subTaskRegistry.listActive sees it.
        // The registry is the single source of truth for both the LLM-tool
        // path (DefaultSubTaskTool) and the schedule-callback path
        // (DefaultScheduleTool.runAsSubTask); centralising the write here
        // means both contribute to the same active/history streams.
        String subTaskId = safeSubId(req.subTaskId());
        try {
            subTaskRegistry.registerWithId(subTaskId, req.username(),
                    req.parentConversationId(), req.prompt());
        } catch (IllegalStateException dup) {
            // Caller already registered (e.g. duplicate LLM-tool invocation
            // with the same id). Tolerate but log so we can detect bugs.
            log.warn("Sub-task id {} already registered; re-executing without re-register", subTaskId);
        } catch (IllegalArgumentException bad) {
            log.error("Sub-task id missing/invalid — cannot record history: id={}", subTaskId);
        }

        Future<SubTaskResult> future;
        try {
            future = executor.submit(() -> {
                try {
                    return doExecute(req, startedAt);
                } finally {
                    activeFutures.remove(subTaskId);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ree) {
            // Thread pool is shut down — surface as FAILED and persist the
            // failure to the registry so the history isn't left stuck in RUNNING.
            log.error("Sub-task rejected (pool shut down): id={}", subTaskId, ree);
            SubTaskResult r = SubTaskResult.failed(req, startedAt, System.currentTimeMillis(),
                    "Executor 已关闭: " + ree.getMessage());
            subTaskRegistry.markFinished(subTaskId, r.status(), r.text(), r.errorMessage());
            return r;
        }
        activeFutures.put(subTaskId, future);
        // No attachFuture() call here: registry's cancel-hook mechanism
        // (wired via the SubTaskRegistry constructor Consumer<String>)
        // handles kill routing from the REST endpoint to the running worker.
        // attachFuture only exists for legacy CompletableFuture callers.
        // 2026-09-21 (spec § 5.3 B):future.get 改为带超时的 get(timeout, unit),
        // 超时则 cancel(true) 中断 worker thread,向主对话返回可读诊断而非无限阻塞。
        long timeoutSec = (subTaskProperty != null) ? subTaskProperty.getTimeoutSeconds() : 600L;
        try {
            SubTaskResult result = future.get(timeoutSec, TimeUnit.SECONDS);
            subTaskRegistry.markFinished(subTaskId, result.status(), result.text(), result.errorMessage());
            return result;
        } catch (TimeoutException te) {
            boolean cancelled = future.cancel(true);
            log.warn("Sub-task timed out after {}s, id={}, cancel={}", timeoutSec, subTaskId, cancelled);
            String diagnostic = String.format(
                    "[子任务超时 %d 秒,已自动取消。请基于已有结果继续,或拆分更小的子任务重试。]",
                    timeoutSec);
            SubTaskResult r = SubTaskResult.failed(req, startedAt, System.currentTimeMillis(), diagnostic);
            subTaskRegistry.markFinished(subTaskId, r.status(), r.text(), r.errorMessage());
            return r;
        } catch (InterruptedException ie) {
            future.cancel(true);
            SubTaskResult r = SubTaskResult.cancelled(req, startedAt, System.currentTimeMillis());
            log.info("Sub-task interrupted: id={}", subTaskId);
            subTaskRegistry.markFinished(subTaskId, r.status(), "", "用户取消");
            return r;
        } catch (java.util.concurrent.ExecutionException ee) {
            SubTaskResult r = SubTaskResult.failed(req, startedAt, System.currentTimeMillis(),
                    rootCauseMessage(ee));
            log.error("Sub-task failed: id={}", subTaskId, ee);
            subTaskRegistry.markFinished(subTaskId, r.status(), r.text(), r.errorMessage());
            return r;
        } catch (java.util.concurrent.CancellationException ce) {
            SubTaskResult r = SubTaskResult.cancelled(req, startedAt, System.currentTimeMillis());
            log.info("Sub-task cancelled: id={}", subTaskId);
            subTaskRegistry.markFinished(subTaskId, r.status(), "", "用户取消");
            return r;
        }
    }

    @Override
    public boolean cancel(String subTaskId) {
        Future<?> future = activeFutures.remove(subTaskId);
        if (future == null) return false;
        boolean cancelled = future.cancel(true);
        log.info("Sub-task cancel requested: id={}, cancelled={}", subTaskId, cancelled);
        return cancelled;
    }

    private SubTaskResult doExecute(SubTaskRequest req, long startedAt) {
        try {
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
            // Treat empty / blank systemContext the same as null — LLMs sometimes
            // pass an empty string when they mean "no override", and Spring AI's
            // spec.system() throws IllegalArgumentException("text cannot be null
            // or empty") for empty input. Empty == absent here.
            if (req.systemContext() != null && !req.systemContext().isBlank()) {
                spec.system(req.systemContext());
            }
            spec.user(req.prompt());
            String memoryId = req.memoryConversationId();
            spec.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, memoryId));
            spec.advisors(memoryAdvisor);

            // Propagate identity into nested tool calls (same shape DefaultChat uses).
            Map<String, Object> props = new HashMap<>();
            props.put("username", req.username());
            props.put("parentConversationId", req.parentConversationId());
            spec.toolContext(props);

            // 第一步:RBAC 过滤(spec 2026-09-10-subtask-rbac-filter)—— 子任务/定时任务
            // 继承用户角色授权,未授权的 RBAC 工具(render/git/maven/compile)不进列表。
            // universal 工具因 visibleToolGroupsFor = 角色授权 ∪ universal 恒在集合内,照常可用。
            java.util.Set<String> visibleGroups = capabilityService.visibleToolGroupsFor(req.username());
            List<IEmbedTool> authorized =
                    capabilityService.filterEmbedToolsByCapabilityIds(embedTools, visibleGroups);

            // D6:被剔除时按 (username, droppedSet) 去重记 WARN(仅首次),同组合再犯降 DEBUG
            java.util.List<String> dropped = new java.util.ArrayList<>();
            for (var t : embedTools) {
                String id = cn.wubo.spring.ai.loom.agent.capability.CapabilityService.toolGroupIdOf(t);
                if (id != null && !visibleGroups.contains(id)) dropped.add(id);
            }
            if (!dropped.isEmpty()) {
                java.util.Collections.sort(dropped);
                String dedupKey = req.username() + "|" + String.join(",", dropped);
                if (rbacWarned.asMap().putIfAbsent(dedupKey, Boolean.TRUE) == null) {
                    log.warn("Sub-task RBAC filter: user={} conv={} fromScheduler={} dropped={} visible={}",
                            req.username(), req.parentConversationId(), req.fromScheduler(), dropped, visibleGroups);
                } else {
                    log.debug("Sub-task RBAC filter(已警告过,降级 DEBUG): user={} dropped={}",
                            req.username(), dropped);
                }
            }

            // 第二步:自身工具排除(既有,保持不动)—— 防递归 + 子任务不打断用户提问
            // (ISubTaskTool/IScheduleTool 防递归;IAskUserTool 见 #1 spec D6:
            //  子任务只做主任务规划好的执行并返回结果,疑问写进结果由主任务决定是否提问。
            //  定时任务经子任务路径执行,自动继承本排除。)
            List<Object> filtered = new ArrayList<>();
            for (var t : authorized) {
                if (t instanceof cn.wubo.spring.ai.loom.agent.subtask.ISubTaskTool) continue;
                if (t instanceof cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool) continue;
                if (t instanceof cn.wubo.spring.ai.loom.agent.askuser.IAskUserTool) continue;
                filtered.add(t);
            }
            if (!filtered.isEmpty()) {
                spec.tools(filtered.toArray());
            }

            // Attach MCP callbacks the user has access to. Empty list means "every MCP
            // visible to this user", mirroring the design intent of giving sub-tasks
            // the same tool access as the main chat.
            if (mcp != null) {
                ToolCallbackProvider mcpProvider = mcp.getVisibleToolCallbackProvider(req.username(), List.of());
                if (mcpProvider != null) {
                    spec.toolCallbacks(mcpProvider);
                }
            }

            String text = spec.call().chatResponse().getResult().getOutput().getText();
            return SubTaskResult.completed(req, startedAt, System.currentTimeMillis(), text);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e);
        }
    }
}
