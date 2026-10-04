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
     * Submits every request to the pool <b>before</b> awaiting any of them, then
     * collects the results in the original order.
     *
     * <p><b>Why this exists</b>: Spring AI 2.0.1's {@code DefaultToolCallingManager}
     * walks a turn's tool calls with a plain {@code Iterator} — no
     * {@code parallelStream}, no {@code ExecutorService}. So if the model emits N
     * separate {@code start_sub_task} calls in one turn, Spring AI runs them one
     * after another and each call blocks the main thread on its own future. Calling
     * this method instead moves the fan-out <b>inside one tool invocation</b>,
     * where the pool can actually overlap the work; the concurrency ceiling is
     * whatever {@code loomSubTaskExecutor} is configured to, and overflow queues
     * rather than failing.
     *
     * @param requests must not be empty; every element is submitted.
     * @return one result per request, in the same order as {@code requests}.
     */
    java.util.List<SubTaskResult> executeBatch(java.util.List<SubTaskRequest> requests);

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
