package cn.wubo.spring.ai.loom.agent.askuser;

/**
 * §2 提问卡片日志行(只读视图,来源 loom_tool_call_log WHERE tool_name='askUser')。
 *
 * @param logId          loom_tool_call_log.log_id
 * @param conversationId 会话 ID
 * @param username       提问所属用户
 * @param question       问题文本(从 arguments_json 解析;解析失败 = "(解析失败)")
 * @param header         问题卡片 chip 标题(可空)
 * @param answerText     用户答案(仅 ANSWERED;其余状态为 null)
 * @param status         ANSWERED / TIMEOUT / CANCELLED / FAILED / UNKNOWN
 * @param durationMs     工具阻塞时长 —— 含用户思考+作答时间,前端标注为"等待 Ns"(spec D3)
 * @param createdAt      写入时间(Instant,与 ToolCallLog 一致;Spring Boot 默认序列化为 ISO-8601)
 */
public record AskUserLogRecord(
        long logId,
        String conversationId,
        String username,
        String question,
        String header,
        String answerText,
        String status,
        long durationMs,
        java.time.Instant createdAt
) {
}
