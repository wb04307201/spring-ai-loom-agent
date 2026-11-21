package cn.wubo.spring.ai.loom.agent.stream;

import cn.wubo.spring.ai.loom.agent.chat.SteeringInbox;
import cn.wubo.spring.ai.loom.agent.model.ChatResponseRecord;
import cn.wubo.spring.ai.loom.agent.model.SteerAck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * SseEmitter 注册表：按用户名 + conversationId 追踪活跃的流。
 * - 支持主动停止（emitter.complete + disposable.dispose 真正中断 DashScope 流）
 * - 支持查询活跃流（管理面板 / 调试）
 * - 自动清理（流完成 / 超时 / 错误时移除）
 *
 * <p>steering（2026-11-21 agentic loop）：每个 {@link Entry} 携带一条 run 生命周期的
 * {@link SteeringInbox}；{@link #steer} 向在册 Entry 的 inbox 入队，{@link #stop} 在
 * dispose/complete 前先 {@link #terminateInbox} 永久收口 + 残留 steerAck(NOT_INJECTED)
 * 可见终态（R11/A2，不静默吞排队消息）；旧流 onComplete 用
 * {@link #autoCleanup(String, String, Entry)} 身份感知移除，不误删新 run Entry（#6 竞态）。
 */
@Component
public class SseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);
    // username -> (conversationId -> Entry)
    private final Map<String, Map<String, Entry>> store = new ConcurrentHashMap<>();

    /**
     * 注册一条活跃流并返回其 {@link Entry}(R10,spec 2026-11-21 Task 6)。
     *
     * <p><b>调用方必须用返回引用</b>做身份感知 {@link #autoCleanup(String, String, Entry)}
     * 与 steering inbox 绑定 —— 禁止 register 后再 {@link #get} 取回:put↔get 窗口里
     * 新 run 可能已覆盖注册,旧流会拿到<b>新</b> Entry,#6 竞态复活。
     *
     * @return 新建的 Entry;username/conversationId 为 null 时不入库但仍返回 Entry
     *         (调用方引用语义统一;旧忽略返回值的调用点源码兼容)
     */
    public Entry register(String username, String conversationId, SseEmitter emitter, Disposable disposable, Runnable onStop) {
        Entry entry = new Entry(emitter, disposable, System.currentTimeMillis(), onStop, new SteeringInbox());
        if (username == null || conversationId == null) return entry;
        store.computeIfAbsent(username, k -> new ConcurrentHashMap<>())
                .put(conversationId, entry);
        return entry;
    }

    public Entry get(String username, String conversationId) {
        if (username == null || conversationId == null) return null;
        Map<String, Entry> byConv = store.get(username);
        return byConv == null ? null : byConv.get(conversationId);
    }

    /**
     * 主动停止某个会话的流。
     *
     * @return true=成功停止；false=没有活跃流
     */
    public boolean stop(String username, String conversationId) {
        Entry e = remove(username, conversationId);
        if (e == null) return false;
        // steering 永久收口(先于 dispose/complete,R11/A2):terminate 拒绝在途入队,
        // 残留未注入消息经 steerAck(NOT_INJECTED) 终帧可见处置("未发送(已停止)"),
        // 绝不静默蒸发。终帧必须在 emitter.complete() 之前发(完成后 send 抛异常)。
        terminateInbox(e);
        // 触发 onStop 回调（用于记录已产生的 token，避免停止后丢数据）
        if (e.onStop() != null) {
            try {
                e.onStop().run();
            } catch (Exception ex) {
                log.warn("onStop 异常：{}", ex.getMessage());
            }
        }
        try {
            if (e.disposable() != null && !e.disposable().isDisposed()) {
                e.disposable().dispose(); // 真正中断 DashScope
            }
        } catch (Exception ex) {
            log.warn("dispose 异常：{}", ex.getMessage());
        }
        try {
            e.emitter().complete(); // 关 SSE
        } catch (Exception ex) {
            // emitter 可能已经完成
        }
        log.info("Stopped stream: user={} conv={} (running {}ms)", username, conversationId,
                System.currentTimeMillis() - e.startMs());
        return true;
    }

    /**
     * 流正常完成 / 超时 / 出错时由 SseController 自动调用（无条件按 key 移除，
     * 兼容旧调用点；新 run 注册后旧流 onComplete 会误删新 Entry —— #6 竞态，
     * 新代码请用身份感知重载 {@link #autoCleanup(String, String, Entry)}）。
     */
    public void autoCleanup(String username, String conversationId) {
        remove(username, conversationId);
    }

    /**
     * 身份感知自动清理：仅当在册 Entry 与 expected <b>引用同一对象</b>才移除。
     *
     * <p>#6 竞态修复：新 run 覆盖注册后，旧流的 onComplete/onTimeout/onError 迟到这里,
     * 按 key 裸 remove 会删掉<b>新</b> run 的 Entry（新流还活着却从注册表消失 ——
     * stop/steer/askUser 侧信道全部失联）。expected==null 视为无条件（委托 2-arg 语义）。
     *
     * <p>⚠️ 不能用 {@code byConv.remove(c, expected)}：Entry 是 record,equals 是<b>值</b>相等,
     * 两个不同实例可能值相等而误删。这里用 {@code computeIfPresent} 内的 {@code cur == expected}
     * 做原子引用比较（与 {@link SteeringInbox} 撤单用 Node 身份比较同一纪律）。
     * 命中移除时顺带清掉空的用户桶,与 {@link #remove} 行为一致。
     */
    public void autoCleanup(String username, String conversationId, Entry expected) {
        if (username == null || conversationId == null) return;
        if (expected == null) {
            remove(username, conversationId);
            return;
        }
        Map<String, Entry> byConv = store.get(username);
        if (byConv == null) return;
        // cur == expected：引用同一对象才映射为 null(移除),否则原样保留(新 run Entry 不误删)
        byConv.computeIfPresent(conversationId, (k, cur) -> cur == expected ? null : cur);
        if (byConv.isEmpty()) {
            // 值条件删除：若并发 register 已重建/复用桶,不会误清整个用户桶
            store.remove(username, byConv);
        }
    }

    /**
     * 向活跃流的 steering inbox 入队一条用户追加输入（agentic loop 续跑）。
     *
     * @return true=已排队,必被 loop 侧 drain 读走;false=无活跃流 / inbox 已收口(stop)
     *         / 排队深度达 {@link SteeringInbox#MAX_DEPTH}(429 语义,由路由层转译)
     */
    public boolean steer(String username, String conversationId, String messageId, String text) {
        Entry e = get(username, conversationId);
        if (e == null) return false;
        SteeringInbox inbox = e.inbox();
        if (inbox.isClosing()) return false;
        return inbox.offer(messageId, text);
    }

    /**
     * 用户登出时停掉该用户全部活跃流
     */
    public int cleanup(String username) {
        Map<String, Entry> byConv = store.remove(username);
        if (byConv == null) return 0;
        int n = 0;
        for (Map.Entry<String, Entry> kv : byConv.entrySet()) {
            try {
                // R11/A2:登出停流同样永久收口 + 残留可见终态(不静默吞排队 steering)
                terminateInbox(kv.getValue());
                if (kv.getValue().disposable() != null && !kv.getValue().disposable().isDisposed()) {
                    kv.getValue().disposable().dispose();
                }
                kv.getValue().emitter().complete();
                n++;
            } catch (Exception ignore) {
            }
        }
        return n;
    }

    /**
     * 永久终止 Entry 的 steering inbox 并对残留发可见终态帧(R11/A2,Task 7)。
     * stop/登出/断连共用:{@link SteeringInbox#terminateAndRemaining()} 原子收口取残留,
     * 逐条经 {@code steerAck(NOT_INJECTED)} 帧标记"未发送(已停止)"。终帧尽力发送 ——
     * 断连场景 emitter 可能已 complete(send 抛异常),捕获降级日志(前端已丢,帧不达不阻断清理)。
     *
     * @return 残留条数(已可见处置)
     */
    public int terminateInbox(Entry e) {
        List<SteeringInbox.SteeringMessage> residual = e.inbox().terminateAndRemaining();
        for (SteeringInbox.SteeringMessage m : residual) {
            try {
                e.emitter().send(new ChatResponseRecord(new SteerAck(m.messageId(), SteerAck.STATUS_NOT_INJECTED)),
                        MediaType.APPLICATION_JSON);
            } catch (Exception ex) {
                log.warn("steering 终态帧发送失败(连接可能已关): msg={} err={}", m.messageId(), ex.getMessage());
            }
        }
        return residual.size();
    }

    /**
     * 调试用：当前活跃流（按用户）
     */
    public Map<String, Set<String>> activeSnapshot() {
        Map<String, Set<String>> snap = new ConcurrentHashMap<>();
        store.forEach((user, byConv) -> {
            if (!byConv.isEmpty()) snap.put(user, new CopyOnWriteArraySet<>(byConv.keySet()));
        });
        return snap;
    }

    public int activeCount() {
        int n = 0;
        for (Map<String, Entry> m : store.values()) n += m.size();
        return n;
    }

    private Entry remove(String username, String conversationId) {
        if (username == null || conversationId == null) return null;
        Map<String, Entry> byConv = store.get(username);
        if (byConv == null) return null;
        Entry e = byConv.remove(conversationId);
        if (byConv.isEmpty()) store.remove(username);
        return e;
    }

    /**
     * 一条活跃流的注册项。
     *
     * <p>{@code inbox} = 本 run 生命周期的 steering 收件箱（{@link #register} 创建 Entry
     * 时随之新建,{@link #stop} 关闭）。record 语义下 equals 为<b>值</b>相等,身份感知
     * autoCleanup 必须用引用比较（见该方法 javadoc ⚠️）。
     */
    public record Entry(SseEmitter emitter, Disposable disposable, long startMs, Runnable onStop, SteeringInbox inbox) {

        /** 兼容旧 4-arg 构造点（含外部消费者）：默认新建一条空 inbox。 */
        public Entry(SseEmitter emitter, Disposable disposable, long startMs, Runnable onStop) {
            this(emitter, disposable, startMs, onStop, new SteeringInbox());
        }

        public Entry {
            Objects.requireNonNull(inbox, "inbox");
        }
    }
}
