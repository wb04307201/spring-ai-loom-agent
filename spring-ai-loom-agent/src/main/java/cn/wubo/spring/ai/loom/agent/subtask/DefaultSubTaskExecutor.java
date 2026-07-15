package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
 * </ul>
 */
public class DefaultSubTaskExecutor implements ISubTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultSubTaskExecutor.class);

    private final ChatClient subTaskChatClient;
    private final MessageChatMemoryAdvisor memoryAdvisor;
    private final ExecutorService executor;

    public DefaultSubTaskExecutor(ChatClient subTaskChatClient,
                                  MessageChatMemoryAdvisor memoryAdvisor,
                                  ExecutorService executor) {
        this.subTaskChatClient = subTaskChatClient;
        this.memoryAdvisor = memoryAdvisor;
        this.executor = executor;
    }

    @Override
    public SubTaskResult execute(SubTaskRequest req) {
        long startedAt = System.currentTimeMillis();
        log.info("Sub-task start: id={}, parentConv={}, user={}, fromScheduler={}",
                req.subTaskId(), req.parentConversationId(), req.username(), req.fromScheduler());

        Future<SubTaskResult> future = executor.submit(() -> doExecute(req, startedAt));
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