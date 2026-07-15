package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;

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
 *   <li>Uses {@link ChatClient.ChatClientRequestSpec#call()} — synchronous, runs
 *       full Spring AI tool-call loop to final response.</li>
 *   <li>On interrupt: cancels the future, returns {@link SubTaskStatus#CANCELLED}.</li>
 *   <li>On exception: returns {@link SubTaskStatus#FAILED} with the message.</li>
 *   <li>Writes intermediate ChatMemory entries under
 *       {@code "{conversationId}--sub--{subTaskId}"} so the main conversation
 *       can later see what the sub-task produced.</li>
 *   <li>Tracks each in-flight sub-task in a {@link ConcurrentHashMap} so external
 *       callers (e.g. {@link SubTaskRegistry#kill(String)} via a registered cancel
 *       hook) can interrupt the worker thread via {@link Future#cancel(boolean)}.</li>
 *   <li>Propagates the full tool set available to the user: {@link IMcp} callbacks
 *       for every MCP server the user can see, in addition to the {@code embedTools}
 *       baked into the {@link ChatClient} via {@code .defaultTools(...)}.</li>
 *   <li>Propagates {@code username} + {@code parentConversationId} into the spec's
 *       toolContext so nested tool calls inside the sub-task see the same identity
 *       values the main chat uses.</li>
 * </ul>
 */
public class DefaultSubTaskExecutor implements ISubTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultSubTaskExecutor.class);

    private final ChatClient subTaskChatClient;
    private final MessageChatMemoryAdvisor memoryAdvisor;
    private final ExecutorService executor;
    private final IMcp mcp;

    /** Active in-flight sub-task futures, keyed by {@code req.subTaskId()}. Cleared in the worker's finally block. */
    private final ConcurrentHashMap<String, Future<?>> activeFutures = new ConcurrentHashMap<>();

    public DefaultSubTaskExecutor(ChatClient subTaskChatClient,
                                  MessageChatMemoryAdvisor memoryAdvisor,
                                  ExecutorService executor,
                                  IMcp mcp) {
        this.subTaskChatClient = subTaskChatClient;
        this.memoryAdvisor = memoryAdvisor;
        this.executor = executor;
        this.mcp = mcp;
    }

    @Override
    public SubTaskResult execute(SubTaskRequest req) {
        long startedAt = System.currentTimeMillis();
        log.info("Sub-task start: id={}, parentConv={}, user={}, fromScheduler={}",
                req.subTaskId(), req.parentConversationId(), req.username(), req.fromScheduler());

        Future<SubTaskResult> future;
        try {
            future = executor.submit(() -> {
                try {
                    return doExecute(req, startedAt);
                } finally {
                    activeFutures.remove(req.subTaskId());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ree) {
            // Thread pool is shut down — surface as FAILED rather than letting the caller hang.
            log.error("Sub-task rejected (pool shut down): id={}", req.subTaskId(), ree);
            return SubTaskResult.failed(req, startedAt, System.currentTimeMillis(),
                    "Executor 已关闭: " + ree.getMessage());
        }
        activeFutures.put(req.subTaskId(), future);
        try {
            return future.get();
        } catch (InterruptedException ie) {
            future.cancel(true);
            SubTaskResult r = SubTaskResult.cancelled(req, startedAt, System.currentTimeMillis());
            log.info("Sub-task interrupted: id={}", req.subTaskId());
            return r;
        } catch (java.util.concurrent.ExecutionException ee) {
            SubTaskResult r = SubTaskResult.failed(req, startedAt, System.currentTimeMillis(),
                    rootCauseMessage(ee));
            log.error("Sub-task failed: id={}", req.subTaskId(), ee);
            return r;
        } catch (java.util.concurrent.CancellationException ce) {
            SubTaskResult r = SubTaskResult.cancelled(req, startedAt, System.currentTimeMillis());
            log.info("Sub-task cancelled: id={}", req.subTaskId());
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
            ChatClient.ChatClientRequestSpec spec = subTaskChatClient.prompt();
            if (req.systemContext() != null) {
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

            // Attach MCP callbacks the user has access to. Sub-task has no per-call MCP
            // selection; we pass an empty list to mean "every MCP visible to this user",
            // matching the design intent of giving sub-tasks the same tool access as
            // the main chat.
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
