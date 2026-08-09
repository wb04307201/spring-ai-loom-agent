package cn.wubo.spring.ai.loom.agent.tool;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ：工具调用上下文传递 — SseController 在异步任务入口设置，
 * ToolCallLogObservationHandler 在 onStart/onStop 时读取。
 *
 * <p>用 {@code ConcurrentHashMap} 存 conversationId → context。
 * SseController 写，handler 读。Spring AI 1.1.7 的 chat 是同步完成（一个请求
 * 一个 chat），不会两个请求并发修改同一 context。
 *
 * <p>不用 ThreadLocal 因为 Reactor 异步线程拿不到 SseController runAsync
 * 设的值。static volatile 单值也失败因为 chat 调用链在不同线程。
 * Map 提供 O(1) 查找 + 跨线程可见性。
 */
public final class ToolCallContextHolder {

 public record ToolCallContext(String conversationId, String username) {}

 private static final ConcurrentHashMap<String, ToolCallContext> BY_CONVERSATION = new ConcurrentHashMap<>();
 // 用 conversationId 存一个 placeholder，保证 latest 至少有一个非空 entry
 private static final AtomicReference<ToolCallContext> LATEST = new AtomicReference<>();

 private ToolCallContextHolder() {}

 public static void set(String conversationId, String username) {
 ToolCallContext ctx = new ToolCallContext(conversationId, username);
 BY_CONVERSATION.put(conversationId, ctx);
 LATEST.set(ctx);
 }

 public static ToolCallContext get() {
 ToolCallContext latest = LATEST.get();
 if (latest != null) return latest;
 return BY_CONVERSATION.values().stream().findFirst().orElse(null);
 }

 public static void clear() {
 // 清理所有 entries
 BY_CONVERSATION.clear();
 LATEST.set(null);
 }
}
