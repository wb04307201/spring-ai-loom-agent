package cn.wubo.spring.ai.loom.agent.model;

/**
 * agentic loop 迭代边界帧(Task 2,agentic-loop-steering):每轮模型迭代(含续跑)开始时,
 * 经 {@link ChatResponseRecord#iteration()} 随 SSE 帧下发;前端据此在当前 AI 气泡内
 * 画轻量分隔线并开新的回答/思考分段(Task 12),思考折叠区按迭代分段与
 * loom_chat_reasoning 的按轮持久化对齐。
 *
 * @param index 迭代序号,值来自 {@code StreamRunContext#nextIteration()}(从 0 开始,每次续跑+1)
 */
public record IterationEvent(int index) {
}
