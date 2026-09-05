package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;

/**
 * 市场内容 review 接口(M3+ T1.4)。
 *
 * <p>保留两套主键形态 — 兼容 skill(BIGINT)与 KB (VARCHAR(36) UUID)两类内容:
 * <ul>
 *   <li>{@link Long} 主键版本 — skill review 与既有 tests/LoomAgentConfiguration 调用方</li>
 *   <li>{@link String} 主键版本 — M3+ T1.4 新增,KB router 走
 *       {@code RouterIdParserKnowledge#parse} 后直接传 String,不预先
 *       {@code Long.parseLong};UUID/非数字路径走 graceful-degradation
 *       (抛 {@link LoomAgentRuntimeException}(404) — 与 M0 抽象基类对
 *       不存在 KB 的处理对齐)</li>
 * </ul>
 *
 * <p>String 版本默认实现委托给 Long 版本({@code Long.parseLong + NFE catch});子类
 * 无需重复实现,但若 KB 端想做更细粒度的 UUID-vs-numeric 区分,可 override
 * String 版本直接走 SQL。
 */
public interface IMarketContentReviewService {

    /* ===== Long 主键版本(skill / 既有调用方) ===== */

    Page<ReviewRow> listReviews(Long marketId, int page, int size);
    ReviewRow submit(Long marketId, String username, ReviewSubmitRequest req);
    ReviewRow update(Long marketId, String username, ReviewUpdateRequest req);
    void deleteAsAdmin(Long marketId, String username);
    RatingAggregate aggregate(Long marketId);

    /* ===== String 主键版本(M3+ T1.4:KB router 用) ===== */

    /**
     * KB router 入口 — 接受 UUID/raw 字符串,内部 {@code Long.parseLong};
     * NFE 时抛 {@link LoomAgentRuntimeException}(404, "市场知识库不存在: id=...") —
     * 与 KB 端 {@code getById(String)} 的 404 文案对齐。
     */
    default Page<ReviewRow> listReviews(String marketId, int page, int size) {
        return listReviews(parseMarketIdOrThrow(marketId, "listReviews"), page, size);
    }

    default ReviewRow submit(String marketId, String username, ReviewSubmitRequest req) {
        return submit(parseMarketIdOrThrow(marketId, "submit"), username, req);
    }

    default ReviewRow update(String marketId, String username, ReviewUpdateRequest req) {
        return update(parseMarketIdOrThrow(marketId, "update"), username, req);
    }

    default void deleteAsAdmin(String marketId, String username) {
        deleteAsAdmin(parseMarketIdOrThrow(marketId, "deleteAsAdmin"), username);
    }

    default RatingAggregate aggregate(String marketId) {
        return aggregate(parseMarketIdOrThrow(marketId, "aggregate"));
    }

    /**
     * String → Long 解析 — 失败抛 {@link LoomAgentRuntimeException}(404)。
     * 与既有"router 层 Long.parseLong NFE → 4xx"行为对齐,但错误文案统一为
     * 404 "市场知识库不存在",避免泄露"id 必须是数字"这种 schema mismatch 信息
     * 给 KB 真实用户(UUID 本就是合法形态,只是 review/stats 表 PK 是 BIGINT)。
     */
    private static Long parseMarketIdOrThrow(String s, String op) {
        if (s == null || s.isBlank()) {
            throw new LoomAgentRuntimeException(404, "市场知识库不存在: id=" + s);
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            // KB review/stats 表 PK 是 BIGINT 而 KB 主表是 UUID — UUID 永远不会有
            // 匹配 review/stats row;直接当 KB 不存在处理。
            throw new LoomAgentRuntimeException(404, "市场知识库不存在: id=" + s);
        }
    }
}
