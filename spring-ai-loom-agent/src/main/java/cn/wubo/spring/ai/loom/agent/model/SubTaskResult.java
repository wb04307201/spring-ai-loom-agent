package cn.wubo.spring.ai.loom.agent.model;

/**
 * Outcome of a sub-task execution. Returned synchronously by
 * {@code ISubTaskExecutor.execute}.
 *
 * @param text         Final response text when status == COMPLETED; empty otherwise.
 * @param errorMessage Populated when status == FAILED.
 */
public record SubTaskResult(
        String subTaskId,
        String conversationId,
        String username,
        SubTaskStatus status,
        String text,
        String errorMessage,
        long startedAt,
        long finishedAt
) {
    public static SubTaskResult cancelled(SubTaskRequest req, long startedAt, long finishedAt) {
        return new SubTaskResult(req.subTaskId(), req.parentConversationId(), req.username(),
                SubTaskStatus.CANCELLED, "", "用户取消", startedAt, finishedAt);
    }

    public static SubTaskResult failed(SubTaskRequest req, long startedAt, long finishedAt, String message) {
        return new SubTaskResult(req.subTaskId(), req.parentConversationId(), req.username(),
                SubTaskStatus.FAILED, "", message, startedAt, finishedAt);
    }

    public static SubTaskResult completed(SubTaskRequest req, long startedAt, long finishedAt, String text) {
        return new SubTaskResult(req.subTaskId(), req.parentConversationId(), req.username(),
                SubTaskStatus.COMPLETED, text, "", startedAt, finishedAt);
    }
}