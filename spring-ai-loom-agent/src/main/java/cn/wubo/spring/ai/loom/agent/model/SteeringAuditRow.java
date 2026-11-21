package cn.wubo.spring.ai.loom.agent.model;

import java.time.Instant;

/**
 * 一条 steering 注入审计行（表 {@code loom_chat_steering}，spec 2026-11-21
 * agentic-loop-steering §5.9 / Task 9 S4）。
 *
 * <p>每次 agentic-loop advisor 真正把一条 steering 消息注入模型（内层 drain 或
 * 外层续跑残留注入）时写一行；未注入（护栏终态 NOT_INJECTED / 被拒）<b>绝不</b>
 * 写行 —— 该表因此是 admin 流水视图 B2「时间戳改写豁免」（Task 10）与历史交错
 * 的确定性标记：行的存在 ⇔ 消息在 {@code injectedAt} 时刻确实进入了模型上下文。
 *
 * @param id             自增主键
 * @param conversationId 所属会话
 * @param username       发起 steering 的用户
 * @param messageText    注入的用户追加输入原文（CLOB，emoji/多字节无损）
 * @param injectedAt     注入时刻
 * @param seq            本 run（一次 SSE 流）内注入序号，从 1 单调递增；
 *                       同 {@code injectedAt} 行的稳定定序依据
 */
public record SteeringAuditRow(
        long id,
        String conversationId,
        String username,
        String messageText,
        Instant injectedAt,
        int seq
) {
}
