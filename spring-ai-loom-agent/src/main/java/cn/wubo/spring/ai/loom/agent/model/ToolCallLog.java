package cn.wubo.spring.ai.loom.agent.model;

/**
 * 单条 LLM 工具调用日志（+）。
 *
 * <p>每次 LLM 响应包含 {@code toolCalls} 时，{@code DefaultChat} 把入参/结果/耗时
 * 写一行到 {@code loom_tool_call_log}，供 {@code ConversationFlowService} 时间线展示。
 *
 * <p>历史数据（之前）没有这张表，{@code ConversationFlowService} 会从
 * {@code SPRING_AI_CHAT_MEMORY} 的 {@code metadata.toolCalls} 兜底解析。
 *
 * @param logId          自增主键
 * @param conversationId 会话 ID
 * @param username       所属用户
 * @param toolCallId     Spring AI 工具调用 id（与 ToolResponseMessage 关联）
 * @param toolName       工具方法名（如 compileAndDeploy / getSkill）
 * @param argumentsJson  工具入参 JSON 字符串
 * @param resultText     工具返回值（截断 64KB）
 * @param resultIsError  是否错误返回
 * @param durationMs     工具执行耗时
 * @param createdAt      写入时间
 */
public record ToolCallLog(
        Long logId,
        String conversationId,
        String username,
        String toolCallId,
        String toolName,
        String argumentsJson,
        String resultText,
        boolean resultIsError,
        Long durationMs,
        java.time.Instant createdAt
) {
}
