package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;

public interface ISubTaskExecutor {
    /**
     * Runs a sub-task synchronously on a dedicated thread pool. The implementation
     * is responsible for honoring cancellation (interrupting its worker thread).
     */
    SubTaskResult execute(SubTaskRequest req);

    /**
     * Attempts to cancel a previously-submitted sub-task by interrupting its worker
     * thread. Returns {@code true} iff the sub-task was still running and an interrupt
     * was issued. No-op if the sub-task is unknown or has already completed.
     * <p>
     * Invoked by {@link SubTaskRegistry#kill(String)} via a registered cancel hook, so
     * the panel UI's "kill" button actually stops the LLM call instead of just marking
     * the record CANCELLED in the registry.
     * </p>
     */
    boolean cancel(String subTaskId);
}
