package cn.wubo.spring.ai.loom.agent.model;

/**
 * steering 插话确认帧(Task 2,agentic-loop-steering):后端把一条已排队的 steering 消息
 * 注入模型上下文(写 memory + append instructions)后,经 {@link ChatResponseRecord#steerAck()}
 * 随 SSE 帧下发本事件;前端据 messageId 把灰态用户气泡转正(Task 11)。
 * 护栏触顶收口时,残留未注入的排队消息同样经 ack 帧标"未注入"终态(Task 6)。
 *
 * @param messageId steering 入队时 steer 端点返回的 stable id,与灰态气泡一一对应
 */
public record SteerAck(String messageId) {
}
