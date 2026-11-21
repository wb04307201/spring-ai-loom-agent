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
 * <p><b>A2 不变式</b>：一条消息只能落入两种终态之一 —— 返回 true ⇒ 必被 {@code drain}/
 * {@code closeAndRemaining} 读走(可见处置),或返回 false ⇒ 被拒(调用方走拒绝通道)。
 * 不存在"返回 true 但消息既不消费也不可见"的第三态(= 对契约说谎)。<b>offer 入队后
 * 复查 closing</b> 闭合收口窗口：若入队竞上了收口(已过首检、收口 drain 也没读到它),
 * 原子撤单返回 false。撤单用<b>身份比较</b>的 Node 包装(而非 record 值相等的
 * {@code queue.remove(msg)})：重复 messageId+text 值相同的两条在途消息,值相等 remove
 * 会撤错孪生节点、把另一条变成孤儿 —— 与缺陷同形态,必须按引用精确移除。
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

    /**
     * 队列内部包装节点：equals/hashCode 用 Object 身份(不覆写),使收口撤单
     * {@code queue.remove(node)} 精确命中"本线程入队的这一个节点" —— 值相等的
     * 两条不同消息(record equals 相同)不会互相误撤。
     */
    private static final class Node {
        final SteeringMessage message;

        Node(SteeringMessage message) {
            this.message = message;
        }
    }

    private final ConcurrentLinkedQueue<Node> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final AtomicInteger depth = new AtomicInteger(0);

    /**
     * 尝试入队一条 steering 消息。
     *
     * @return true 表示已排队且必被读走;false 表示被拒绝(closing 收口中、深度达
     *         {@link #MAX_DEPTH},或入队撞上收口已原子撤单)
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
        Node node = new Node(new SteeringMessage(messageId, text));
        beforeEnqueue(node.message); // 测试 seam(生产 no-op)：定格在"占额成功→入队"竞态窗口
        queue.add(node);
        // 入队后复查 closing(A2 第三态修复)：过了首检后被抢占、期间收口(置 closing 并
        // drain 完,读侧终止)的在途 offer,恢复后绝不能"返回 true + 消息蒸发"。
        // remove 失败 ⇔ 节点已被 drain 取走(消息已可见处置)→ 保持 true;
        // remove 成功 ⇔ 没人读过 → 原子撤单,回滚 depth 预约并返回 false(拒绝即可见通道)。
        if (closing.get() && queue.remove(node)) {
            depth.decrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * 一次性取走当前全部已排队消息,按入队 FIFO 顺序返回,并逐条释放 depth 名额。
     *
     * @return 已排队消息快照(可能为空列表)
     */
    public List<SteeringMessage> drain() {
        List<SteeringMessage> drained = new ArrayList<>();
        Node node;
        while ((node = queue.poll()) != null) {
            drained.add(node.message);
            depth.decrementAndGet();
        }
        return drained;
    }

    /**
     * 标记收口：此后 {@link #offer} 一律返回 false(在途消息由入队后复查撤单,
     * 见类 javadoc)。已排队消息不在此处清空,由调用方决定是否用
     * {@link #closeAndRemaining()} 取残留。
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
     * <p>与在途 offer 的交错由 offer 侧"入队后复查 + 身份撤单"闭合(A2)：
     * 残留没读到的在途消息要么被撤单(false,拒绝通道)、要么已被 drain 读走(true,可见),
     * 不存在"true 却蒸发"。
     *
     * @return 收口瞬间仍在队列中的消息(按 FIFO 顺序,可能为空列表)
     */
    public List<SteeringMessage> closeAndRemaining() {
        close();
        return drain();
    }

    /**
     * 测试 seam —— 生产恒 no-op(空方法体,JIT 内联后零开销)。
     *
     * <p>供回归测试用同包匿名子类覆写,把线程<b>确定性定格在"CAS 占额成功 → queue.add"
     * 之间</b>(即真实世界里被 OS 抢占命中才会出现的 stranding 窗口;该窗口只有纳秒级,
     * 纯靠概率性抢占实测不可复现,见 task-1-report)。生产路径无任何子类,不受影响。
     *
     * @param msg 即将入队但尚未入队的那条消息
     */
    void beforeEnqueue(SteeringMessage msg) {
        // no-op
    }
}
