package cn.wubo.spring.ai.loom.agent.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * SseEmitter 注册表：按用户名 + conversationId 追踪活跃的流。
 * - 支持主动停止（emitter.complete + disposable.dispose 真正中断 DashScope 流）
 * - 支持查询活跃流（管理面板 / 调试）
 * - 自动清理（流完成 / 超时 / 错误时移除）
 */
@Component
public class SseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    public record Entry(SseEmitter emitter, Disposable disposable, long startMs, Runnable onStop) {}

    // username -> (conversationId -> Entry)
    private final Map<String, Map<String, Entry>> store = new ConcurrentHashMap<>();

    public void register(String username, String conversationId, SseEmitter emitter, Disposable disposable, Runnable onStop) {
        if (username == null || conversationId == null) return;
        store.computeIfAbsent(username, k -> new ConcurrentHashMap<>())
                .put(conversationId, new Entry(emitter, disposable, System.currentTimeMillis(), onStop));
    }

    public Entry get(String username, String conversationId) {
        if (username == null || conversationId == null) return null;
        Map<String, Entry> byConv = store.get(username);
        return byConv == null ? null : byConv.get(conversationId);
    }

    /**
     * 主动停止某个会话的流。
     * @return true=成功停止；false=没有活跃流
     */
    public boolean stop(String username, String conversationId) {
        Entry e = remove(username, conversationId);
        if (e == null) return false;
        // 触发 onStop 回调（用于记录已产生的 token，避免停止后丢数据）
        if (e.onStop() != null) {
            try { e.onStop().run(); } catch (Exception ex) { log.warn("onStop 异常：{}", ex.getMessage()); }
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

    /** 流正常完成 / 超时 / 出错时由 SseController 自动调用 */
    public void autoCleanup(String username, String conversationId) {
        remove(username, conversationId);
    }

    /** 用户登出时停掉该用户全部活跃流 */
    public int cleanup(String username) {
        Map<String, Entry> byConv = store.remove(username);
        if (byConv == null) return 0;
        int n = 0;
        for (Map.Entry<String, Entry> kv : byConv.entrySet()) {
            try {
                if (kv.getValue().disposable() != null && !kv.getValue().disposable().isDisposed()) {
                    kv.getValue().disposable().dispose();
                }
                kv.getValue().emitter().complete();
                n++;
            } catch (Exception ignore) {}
        }
        return n;
    }

    /** 调试用：当前活跃流（按用户） */
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
}
