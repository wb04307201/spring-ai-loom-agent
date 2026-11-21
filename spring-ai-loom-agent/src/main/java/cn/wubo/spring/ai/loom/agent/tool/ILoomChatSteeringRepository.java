package cn.wubo.spring.ai.loom.agent.tool;

import cn.wubo.spring.ai.loom.agent.model.SteeringAuditRow;

import java.time.Instant;
import java.util.List;

/**
 * {@code loom_chat_steering} steering 注入审计仓储（spec 2026-11-21
 * agentic-loop-steering §5.9，Task 9 / S4）。
 *
 * <p>写入方唯一：{@code LoomAgenticLoopAdvisor.injectSteeringMessages} ——
 * 每条 steering 真正注入模型时（内层 drain / 外层续跑残留两条路径共用该入口）
 * 调一次 {@link #record}。未注入终态（NOT_INJECTED）与 steering 拒绝路径
 * <b>不写行</b> —— 行存在 ⇔ 注入发生，Task 10 的 admin 流水 B2 豁免判定依赖
 * 这一确定性。
 *
 * <p>降级契约：本接口<b>不承诺</b>吞异常（实现按各自语义抛）；聊天流保护由调用方
 * （loop advisor）try/catch + log.warn 承担，与 {@code ToolCallHistory}/
 * {@code ChatUsageRecordingAdvisor} 同策略 —— 审计写失败绝不允许掐断主聊天流。
 *
 * <p>默认实现 {@link JdbcLoomChatSteeringRepository}（H2/JDBC），bean 经
 * {@code @ConditionalOnMissingBean} 注册，可整体替换。
 */
public interface ILoomChatSteeringRepository {

    /**
     * 写一条注入审计行。
     *
     * @param convId     会话 ID（非 null）
     * @param username   发起用户（非 null）
     * @param text       注入的 steering 原文（非 null，CLOB 多字节无损）
     * @param injectedAt 注入时刻
     * @param seq        本 run 内注入序号（从 1 单调递增，由 loop advisor 维护）
     */
    void record(String convId, String username, String text, Instant injectedAt, int seq);

    /**
     * 按会话查全部注入审计行，{@code injected_at} 升序、同刻按 {@code seq} 升序
     * —— Task 10 历史交错按此顺序与 chat memory 行合并。
     */
    List<SteeringAuditRow> findByConversationId(String convId);

    /**
     * 删除某会话的全部审计行（Task 10 / R23：会话删除级联清理）。
     *
     * <p>由 {@code LoomAgentConfiguration.cleanupConversationResources} 在
     * {@code DELETE spring/ai/loom/conversation/{id}} 时调用 —— 审计行随会话消失，
     * 不留悬挂行（与 {@code loom_schedule_execution.deleteByUserAndConversation}
     * 的级联先例一致；本表归属仅 conversation_id 即可唯一定位，无需 username 复核）。
     *
     * @param convId 会话 ID；null/空白 → 0（no-op，与 findByConversationId 同守卫）
     * @return 实际删除的行数
     */
    int deleteByConversation(String convId);
}
