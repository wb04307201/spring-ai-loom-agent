package cn.wubo.spring.ai.loom.agent.model;

/**
 * steering 插话确认帧(Task 2,agentic-loop-steering):后端把一条已排队的 steering 消息
 * 注入模型上下文(写 memory + append instructions)后,经 {@link ChatResponseRecord#steerAck()}
 * 随 SSE 帧下发本事件;前端据 messageId 把灰态用户气泡转正(Task 11)。
 *
 * <p><b>status 终态语义(Task 7,R11/A2)</b>:
 * <ul>
 *   <li>{@link #STATUS_INJECTED} —— 消息已注入模型上下文(内层 drain 或续跑注入),
 *       前端灰态气泡转正;</li>
 *   <li>{@link #STATUS_NOT_INJECTED} —— 护栏触顶/stop/断连收口时残留<b>未注入</b>的
 *       排队消息的可见终态,前端灰态气泡标"未发送(已停止)"。绝不静默蒸发(A2)。</li>
 * </ul>
 * 1-arg 兼容构造器默认 {@link #STATUS_INJECTED}(Task 2 冻结形态,既有注入发送点源码不变)。
 *
 * @param messageId steering 入队时 steer 端点返回的 stable id,与灰态气泡一一对应
 * @param status    {@link #STATUS_INJECTED} 或 {@link #STATUS_NOT_INJECTED}
 */
public record SteerAck(String messageId, String status) {

    /** 已注入模型上下文(灰态转正)。 */
    public static final String STATUS_INJECTED = "INJECTED";

    /** 未注入的可见终态(护栏/stop/断连收口残留,灰态标"未发送(已停止)")。 */
    public static final String STATUS_NOT_INJECTED = "NOT_INJECTED";

    /** 兼容构造(Task 2 冻结的 1-arg 形态):默认已注入。 */
    public SteerAck(String messageId) {
        this(messageId, STATUS_INJECTED);
    }
}