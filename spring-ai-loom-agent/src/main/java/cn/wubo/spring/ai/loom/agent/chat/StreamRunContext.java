package cn.wubo.spring.ai.loom.agent.chat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 单轮流式运行的上下文 —— 贯穿一次 SSE 流的生命周期,把 steering 收件箱、
 * 记忆写回调、思考(reasoning)缓冲、迭代计数、SSE 帧发射回调、stop 信号、
 * 活跃计时(D-3)与注入模式(perIterationMode)收在一处,经
 * {@link #CONTEXT_KEY} 从 request attribute 传给 advisor。
 *
 * <p>线程安全:reasoning 缓冲与活跃计时用 {@code synchronized} 串行化
 * (临界区极短,仅字符串拼接/纳秒累加);迭代序号是 {@link AtomicInteger};
 * 其余字段不可变或委托给本身线程安全的 {@link SteeringInbox} / 回调方。
 *
 * <p>ack/iteration 发射器当前为原始类型(ack=messageId, iteration=index),
 * Task 2 引入 {@code SteerAck} / {@code IterationEvent} record 后由接线层
 * (Task 6)适配成完整 SSE 帧,本类不依赖那两个 record,可独立编译。
 */
public class StreamRunContext {

    /**
     * request attribute / ToolContext 键:advisor 与 SSE lambda 之间传递本上下文的约定 key。
     */
    public static final String CONTEXT_KEY = "loomStreamRunContext";

    private final SteeringInbox inbox;
    private final MemoryWriter memory;
    private final Consumer<String> reasoningFlush;
    private final Consumer<String> ackEmitter;
    private final Consumer<Integer> iterationEmitter;
    private final BooleanSupplier stopSignal;

    /** 外层 agentic-loop 迭代序号,首迭代 = 0。 */
    private final AtomicInteger iteration = new AtomicInteger(-1);

    /**
     * 逐迭代注入模式:loop advisor 每外层迭代写 assistant 行后置 true;
     * 默认 false = legacy 单写(after() 一次性写)。子任务/legacy after() 据此判断
     * 自己是否已被逐迭代写接管,避免双写(spec A1/F4)。
     */
    private volatile boolean perIterationMode;

    // ---- reasoning 缓冲(当前迭代) ----
    private final StringBuilder reasoningBuffer = new StringBuilder();

    /**
     * 本迭代已推送的 provider 累积 reasoningContent 长度(SseController 的
     * cumulative→delta 状态机读写此值,Task 6 从 lambda 本地 AtomicInteger 移入)。
     * 只在 {@link #resetReasoningDelta()}(外层迭代边界)归零 —— 内层 tool 轮次
     * 跨轮仍由 lambda 的 shrink 容错分支处理,与改造前逐字节一致(A1)。
     */
    private int lastReasoningLen;

    // ---- 活跃计时(D-3):暂停区间不计 ----
    /** 已结算的活跃纳秒(各活跃段之和)。 */
    private long accumulatedActiveNanos;
    /** 当前活跃段起点;paused 时无效。 */
    private long activeStartNanos = System.nanoTime();
    private boolean paused;

    public StreamRunContext(SteeringInbox inbox,
                            MemoryWriter memory,
                            Consumer<String> reasoningFlush,
                            Consumer<String> ackEmitter,
                            Consumer<Integer> iterationEmitter,
                            BooleanSupplier stopSignal) {
        this.inbox = inbox;
        this.memory = memory;
        this.reasoningFlush = reasoningFlush;
        this.ackEmitter = ackEmitter;
        this.iterationEmitter = iterationEmitter;
        this.stopSignal = stopSignal;
    }

    public SteeringInbox inbox() {
        return inbox;
    }

    public MemoryWriter memory() {
        return memory;
    }

    /**
     * 取下一个外层迭代序号(从 0 开始递增)。loop advisor 每轮迭代开头调用,
     * 返回值即本轮 index,同时用于 emitIteration 帧。
     */
    public int nextIteration() {
        return iteration.incrementAndGet();
    }

    /**
     * 追加一段思考增量。
     *
     * @param delta provider 帧的 {@code reasoning_content} 增量;非思考帧为 null,直接忽略
     */
    public synchronized void appendReasoning(String delta) {
        if (delta != null) {
            reasoningBuffer.append(delta);
        }
    }

    /**
     * 读 cumulative→delta 状态机的已见累积长度(Task 6:SseController 的
     * {@code lastReasoningLen} 本地变量移入本上下文,迭代边界可复位)。
     */
    public synchronized int getLastReasoningLen() {
        return lastReasoningLen;
    }

    /** 更新 cumulative→delta 状态机的已见累积长度(SseController 每帧 delta 计算后写回)。 */
    public synchronized void setLastReasoningLen(int len) {
        lastReasoningLen = len;
    }

    /**
     * 外层迭代边界复位 delta 累积长度(lastReasoningLen=0,缓冲不动 —— 落库由
     * {@link #flushReasoning()} 负责)。Task 6 在 loop advisor 收口时调用;
     * Task 7 续跑开始时同样调用,使新模型轮的累积 reasoning 从头计算 delta。
     */
    public synchronized void resetReasoningDelta() {
        lastReasoningLen = 0;
    }

    /** 当前迭代缓冲中尚未落库的思考长度(仅日志/观测用)。 */
    public synchronized int reasoningBufferLength() {
        return reasoningBuffer.length();
    }

    /**
     * 取走当前迭代思考缓冲并清空。
     *
     * @return 累计的全部 delta 拼接;无内容时为空串(非 null)
     */
    public synchronized String drainReasoning() {
        String text = reasoningBuffer.toString();
        reasoningBuffer.setLength(0);
        return text;
    }

    /**
     * 落库当前迭代思考:drain 后交给 reasoningFlush 消费;缓冲为空则不调用
     * (A1 —— 无思考不写行)。
     */
    public void flushReasoning() {
        String text = drainReasoning();
        if (!text.isEmpty()) {
            reasoningFlush.accept(text);
        }
    }

    /**
     * 向 SSE 流发射 steering ack 帧(告知前端 messageId 已受理)。
     *
     * @param messageId 被受理的 steering 消息 ID,非 null(由 offer 入队路径保证)
     */
    public void emitAck(String messageId) {
        ackEmitter.accept(messageId);
    }

    /**
     * 向 SSE 流发射迭代边界帧(续跑进入第 index 个外层迭代)。
     *
     * @param index 迭代序号,来自 {@link #nextIteration()}
     */
    public void emitIteration(int index) {
        iterationEmitter.accept(index);
    }

    /** stop 信号:用户点了停止,即应尽快收敛本轮续跑。委托 SSE lambda 绑定的标志。 */
    public boolean isStopped() {
        return stopSignal.getAsBoolean();
    }

    /**
     * 暂停活跃计时(如等待 askUser 作答期间)。重复调用幂等。
     */
    public synchronized void pauseClock() {
        if (!paused) {
            accumulatedActiveNanos += System.nanoTime() - activeStartNanos;
            paused = true;
        }
    }

    /**
     * 恢复活跃计时,开启新的活跃段。未暂停时调用为 no-op(幂等)。
     */
    public synchronized void resumeClock() {
        if (paused) {
            activeStartNanos = System.nanoTime();
            paused = false;
        }
    }

    /**
     * 累计活跃时长(毫秒),暂停区间不计(D-3:run-timeout 用活跃时长判定)。
     */
    public synchronized long activeElapsedMs() {
        long live = paused ? 0 : System.nanoTime() - activeStartNanos;
        return (accumulatedActiveNanos + live) / 1_000_000L;
    }

    /** 标记本流走逐迭代注入写记忆(接管 legacy after() 单写)。 */
    public void markPerIterationMode() {
        perIterationMode = true;
    }

    /** 是否已切逐迭代模式;默认 false(子任务/legacy 路径据此仍单写)。 */
    public boolean perIterationMode() {
        return perIterationMode;
    }
}
