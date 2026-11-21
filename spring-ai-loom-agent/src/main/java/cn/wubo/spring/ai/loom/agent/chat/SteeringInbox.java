package cn.wubo.spring.ai.loom.agent.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 续跑 steering 收件箱 —— 流执行期间用户追加输入的无锁并发队列。
 *
 * <p>语义(spec 2026-11-21 A2 + 深度上限 429)：
 * <ul>
 *   <li>{@link #offer} 入队成功返回 true；closing 后或深度达 {@link #MAX_DEPTH} 时
 *       返回 false(路由层据此回 429,不排队不阻塞)；</li>
 *   <li>{@link #drain} 一次性取走当前全部消息(FIFO 注入顺序),供 loop advisor
 *       每外层迭代边界消费；</li>
 *   <li>{@link #closeAndRemaining} 收口：置 closing 后返回未注入残留,路由/SSE
 *       层据此做可见处置(如提示用户),之后 offer 全部拒绝。</li>
 * </ul>
 *
 * <p>线程安全靠 {@link ConcurrentLinkedQueue} + CAS(depth 只做准入控制,
 * 与队列 size 不做强一致 —— offer 先抢 depth 再入队,drain 逐条 poll 逐条释放,
 * 任何时刻 depth ≥ 队列实际滞留数,只会偏保守拒绝,不会超深排队),不加大锁。
 */
public class SteeringInbox {

    /** 排队深度上限 —— 超限 offer 返回 false(429 语义,防 spam 打爆内存)。 */
    public static final int MAX_DEPTH = 20;

    /**
     * 一条 steering 消息。
     *
     * @param messageId 前端生成的消息 ID,用于 ack 回显与去重审计
     * @param text      用户追加输入正文
     */
    public record SteeringMessage(String messageId, String text) {
    }

    private final ConcurrentLinkedQueue<SteeringMessage> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final AtomicInteger depth = new AtomicInteger(0);

    /**
     * 尝试入队一条 steering 消息。
     *
     * @return true 表示已排队；false 表示被拒绝(closing 收口中,或队列已达 {@link #MAX_DEPTH})
     */
    public boolean offer(String messageId, String text) {
        if (closing.get()) {
            return false;
        }
        // CAS 抢名额：depth 达到上限即拒绝,抢到才入队。
        // 先占 depth 后入队的窗口内,并发 drain 可能见 depth>0 而队列暂空 —— 无害,
        // 下一轮迭代边界会再次 drain 到该条,零丢失由"每条消息 depth 增一、poll 时减一"保证。
        int current;
        do {
            current = depth.get();
            if (current >= MAX_DEPTH) {
                return false;
            }
        } while (!depth.compareAndSet(current, current + 1));
        queue.add(new SteeringMessage(messageId, text));
        return true;
    }

    /**
     * 一次性取走当前全部已排队消息,按入队 FIFO 顺序返回,并逐条释放 depth 名额。
     *
     * @return 已排队消息快照(可能为空列表)
     */
    public List<SteeringMessage> drain() {
        List<SteeringMessage> drained = new ArrayList<>();
        SteeringMessage msg;
        while ((msg = queue.poll()) != null) {
            drained.add(msg);
            depth.decrementAndGet();
        }
        return drained;
    }

    /**
     * 标记收口：此后 {@link #offer} 一律返回 false。已排队消息不在此处清空,
     * 由调用方决定是否用 {@link #closeAndRemaining()} 取残留。
     */
    public void close() {
        closing.set(true);
    }

    /** 是否已进入收口状态。 */
    public boolean isClosing() {
        return closing.get();
    }

    /** 当前排队深度(准入计数)。 */
    public int depth() {
        return depth.get();
    }

    /**
     * 收口并取走未注入残留：{@code closing.set(true)} 后执行一次 {@link #drain()}。
     * 续跑窗口结束时调用,残留交路由/SSE 层做可见处置,不静默吞掉用户输入。
     *
     * <p>已接受的窄竞态：与 close 并发的 in-flight offer(已过 closing 检查、尚未入队)
     * 可能在本方法 drain 之后才落队列 —— 该条不会被本次返回值携带。调用方(SSE 收口)
     * 应在 emit 终帧前再 drain 一次兜底;此为无锁设计的固有窗口,加锁反而违背本类
     * "不加大锁"的约定(spec A2)。
     *
     * @return 收口瞬间仍在队列中的消息(按 FIFO 顺序,可能为空列表)
     */
    public List<SteeringMessage> closeAndRemaining() {
        close();
        return drain();
    }
}
