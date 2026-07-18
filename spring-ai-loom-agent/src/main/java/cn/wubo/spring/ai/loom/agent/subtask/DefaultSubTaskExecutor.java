package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Default {@link ISubTaskExecutor}.
 * <p>
 * Behavior:
 * <ul>
 *   <li>Submits the call to a dedicated {@link ExecutorService} bean
 *       {@code loomSubTaskExecutor} so the call is interruptible and bounded.</li>
 *   <li>Uses the main {@link ChatClient} bean (re-used, not a separate filtered
 *       ChatClient — see SubTaskConfiguration for the rationale) with
 *       {@link ChatClient.ChatClientRequestSpec#call()} — synchronous, runs the
 *       full Spring AI tool-call loop to final response.</li>
 *   <li>On interrupt: cancels the future, returns {@link SubTaskStatus#CANCELLED}.</li>
 *   <li>On exception: returns {@link SubTaskStatus#FAILED} with the message.</li>
 *   <li>Writes intermediate ChatMemory entries under
 *       {@code "{conversationId}--sub--{subTaskId}"} so the main conversation
 *       can later see what the sub-task produced.</li>
 *   <li>Tracks each in-flight sub-task in a {@link ConcurrentHashMap} so external
 *       callers (e.g. {@link SubTaskRegistry#kill(String)} via a registered cancel
 *       hook) can interrupt the worker thread via {@link Future#cancel(boolean)}.</li>
 *   <li>Per-call filters the {@link IEmbedTool} list passed in via constructor
 *       to drop {@code ISubTaskTool}/{@code IScheduleTool} (recursion guard).
 *       Lazy {@code @Lazy} resolution of the list breaks the bean-graph cycle that
 *       would otherwise appear when both this executor and {@code defaultSubTaskTool}
 *       are part of {@code List<IEmbedTool>} auto-collection.</li>
 *   <li>Propagates the full tool set available to the user: {@link IMcp} callbacks
 *       for every MCP server the user can see, plus the filtered {@code embedTools}
 *       list.</li>
 *   <li>Propagates {@code username} + {@code parentConversationId} into the spec's
 *       toolContext so nested tool calls inside the sub-task see the same identity
 *       values the main chat uses.</li>
 * </ul>
 */
public class DefaultSubTaskExecutor implements ISubTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultSubTaskExecutor.class);

    private final ChatClient chatClient;
    private final MessageChatMemoryAdvisor memoryAdvisor;
    private final ExecutorService executor;
    private final IMcp mcp;
    private final List<IEmbedTool> embedTools;
    private final SubTaskRegistry subTaskRegistry;

    /** Active in-flight sub-task futures, keyed by {@code req.subTaskId()}. Cleared in the worker's finally block. */
    private final ConcurrentHashMap<String, Future<?>> activeFutures = new ConcurrentHashMap<>();

    public DefaultSubTaskExecutor(ChatClient chatClient,
                                  MessageChatMemoryAdvisor memoryAdvisor,
                                  ExecutorService executor,
                                  IMcp mcp,
                                  List<IEmbedTool> embedTools,
                                  SubTaskRegistry subTaskRegistry) {
        this.chatClient = chatClient;
        this.memoryAdvisor = memoryAdvisor;
        this.executor = executor;
        this.mcp = mcp;
        this.embedTools = embedTools;
        this.subTaskRegistry = subTaskRegistry;
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
        try {
            SubTaskResult result = future.get();
            subTaskRegistry.markFinished(subTaskId, result.status(), result.text(), result.errorMessage());
            return result;
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

    /**
     * The executor contract says {@code req.subTaskId()} is non-null, but in
     * practice callers have been known to forget. Preserve the old behaviour
     * of silently synthesizing an id rather than NPE-ing — the registry
     * accepts the synthetic id just fine.
     */
    private static String safeSubId(String s) {
        return (s == null || s.isBlank()) ? java.util.UUID.randomUUID().toString() : s;
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

            // Attach embedTools (filtered to exclude ISubTaskTool/IScheduleTool so the
            // sub-task cannot recursively spawn sub-tasks or schedules).
            List<Object> filtered = new ArrayList<>();
            for (var t : embedTools) {
                if (t instanceof cn.wubo.spring.ai.loom.agent.subtask.ISubTaskTool) continue;
                if (t instanceof cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool) continue;
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

    private static String rootCauseMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getClass().getSimpleName() + ": " + r.getMessage();
    }
}
