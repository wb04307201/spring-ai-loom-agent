package cn.wubo.spring.ai.loom.agent.model;

/**
 * Internal request describing a single sub-task execution.
 * <p>
 * NOT a wire-format DTO for HTTP — only used between {@code DefaultSubTaskTool}
 * and {@code DefaultSubTaskExecutor}, and between flex-schedule callbacks and the
 * executor.
 * </p>
 *
 * @param subTaskId UUID assigned by the registry or scheduler.
 * @param parentConversationId Main conversation's id; sub-task memory is
 * stored under "{conversationId}--sub--{subTaskId}".
 * @param parentSubTaskId {@code null} in v1 (no nesting); reserved.
 * @param username Authorizing user (for tool context & RBAC).
 * @param prompt User-facing instruction to the sub-model.
 * @param systemContext Optional extra system guidance; {@code null} skips.
 * @param fromScheduler {@code true} if invoked by a flex-schedule callback.
 */
public record SubTaskRequest(
 String subTaskId,
 String parentConversationId,
 String parentSubTaskId,
 String username,
 String prompt,
 String systemContext,
 boolean fromScheduler
) {
 /**
 * Conversation-id namespace under which the sub-task writes to ChatMemory.
 */
 public String memoryConversationId() {
 return parentConversationId + "--sub--" + subTaskId;
 }
}