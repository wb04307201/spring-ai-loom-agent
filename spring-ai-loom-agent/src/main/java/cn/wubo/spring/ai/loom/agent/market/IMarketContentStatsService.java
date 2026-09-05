package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;

import java.time.LocalDateTime;

/**
 * 市场内容 stats 接口(M3+ T1.4)。
 *
 * <p>保留两套主键形态 — 兼容 skill(BIGINT)与 KB (VARCHAR(36) UUID)两类内容:
 * <ul>
 *   <li>{@link Long} 主键版本 — skill stats 与既有 tests/LoomAgentConfiguration 调用方</li>
 *   <li>{@link String} 主键版本 — M3+ T1.4 新增,KB router 走
 *       {@code RouterIdParserKnowledge#parse} 后直接传 String,不预先
 *       {@code Long.parseLong}</li>
 * </ul>
 *
 * <p>String 版本按方法语义区分降级:
 * <ul>
 *   <li>{@link #resetStats(String, long, LocalDateTime)} — 严格操作,UUID 抛 404
 *       (admin 行为,真实 UUID 不可能存在于 BIGINT stats 表)</li>
 *   <li>{@link #getStats(String)} — 读取操作,UUID 抛 404(KB 不存在语义)</li>
 *   <li>{@link #incrementStat(String, String)} — best-effort,UUID 静默跳过
 *       (与既有 {@code DefaultKnowledgeTool.searchKnowledge} 的 try/catch 模式对齐,
 *       失败不阻塞主路径)</li>
 * </ul>
 */
public interface IMarketContentStatsService {

    /* ===== Long 主键版本(skill / 既有调用方) ===== */

    void incrementStat(Long marketId, String kind);
    StatsRow getStats(Long marketId);

    /**
     * Reset the counter for {@code marketId} to a specific value (admin use).
     * Implementations should ensure the stats row exists (lazy-upsert) before
     * updating it, so an admin can reset a market-content item even if no
     * increment has ever landed yet.
     *
     * @param marketId the market-content id
     * @param newCount the new counter value (admin typically passes 0)
     * @param newLastAt timestamp to record for the most-recent event; pass
     *                 {@code null} for canonical "reset" semantics
     */
    void resetStats(Long marketId, long newCount, LocalDateTime newLastAt);

    /* ===== String 主键版本(M3+ T1.4:KB router 用) ===== */

    /**
     * Best-effort increment — UUID/非数字静默跳过,与既有
     * {@code DefaultKnowledgeTool.searchKnowledge} 内
     * {@code try { Long.parseLong } catch NFE log.debug} 同款语义,
     * 不抛异常上抛到主路径。
     */
    default void incrementStat(String marketId, String kind) {
        Long parsed = parseMarketIdOrNull(marketId);
        if (parsed == null) return; // UUID → best-effort no-op
        incrementStat(parsed, kind);
    }

    /**
     * 读取 — UUID 抛 404(KB 不存在语义,与既有
     * {@code DefaultKnowledgeStatsService#getStats} 对 numeric 返回空 row、
     * 但 router 端已有 UUID graceful-degradation 分支对齐)。
     */
    default StatsRow getStats(String marketId) {
        Long parsed = parseMarketIdOrThrow(marketId);
        return getStats(parsed);
    }

    /**
     * Admin 重置 — 严格,UUID 抛 404。
     */
    default void resetStats(String marketId, long newCount, LocalDateTime newLastAt) {
        Long parsed = parseMarketIdOrThrow(marketId);
        resetStats(parsed, newCount, newLastAt);
    }

    private static Long parseMarketIdOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private static Long parseMarketIdOrThrow(String s) {
        if (s == null || s.isBlank()) {
            throw new LoomAgentRuntimeException(404, "市场知识库不存在: id=" + s);
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            throw new LoomAgentRuntimeException(404, "市场知识库不存在: id=" + s);
        }
    }
}
