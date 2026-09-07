package cn.wubo.spring.ai.loom.agent.knowledge;

import cn.wubo.spring.ai.loom.agent.market.IMarketContentAdminService;
import cn.wubo.spring.ai.loom.agent.model.MarketKnowledgeRecord;

import java.util.List;

/**
 * M3+ T3.1 — v1 knowledge market service interface. <b>Deprecated</b>: the
 * generic {@link IMarketContentAdminService}<code>&lt;String, MarketKnowledgeRecord, ...&gt;</code>
 * (implemented by {@code DefaultKnowledgeMarketService}) is the v2 path. This
 * interface remains as a shim for 1 minor version per ADR-T03; once the
 * call-site grep {@code IKnowledgeMarketService} drops to 0, delete this file.
 *
 * @deprecated use {@link IMarketContentAdminService} via {@code DefaultKnowledgeMarketService}
 */
@Deprecated
public interface IKnowledgeMarketService {

    /* ===== 市场浏览 ===== */

    /**
     * 列出所有 APPROVED 的市场知识库（公开列表仅展示审批通过的条目；
     * 用户提交先落 PENDING，admin approve 后才可见）
     */
    List<MarketKnowledgeRecord> listApproved(int page, int size);

    /**
     * 按 id 查询
     */
    MarketKnowledgeRecord getById(String marketKnowledgeId);

    /**
     * admin：列出全部
     */
    List<MarketKnowledgeRecord> listAllForAdmin();

    /* ===== 用户提交 ===== */

    /**
     * 提交知识库到市场（审批流 #4：新建行落 PENDING，created_by_kind='USER'，等 admin approve/reject）。
     * 同一 username+name 已存在：REJECTED → 旧行归档到 loom_market_knowledge_archive（保留 id + 拒绝评论/审核人/时间）
     * 并新建 PENDING 行（新 id）；PENDING/APPROVED → 仅原地更新内容，状态不动（APPROVED 永不降级）。
     */
    MarketKnowledgeRecord submit(String knowledgeId);

    /**
     * 列出当前用户提交的市场知识库（全状态：PENDING / APPROVED / REJECTED）
     */
    List<MarketKnowledgeRecord> listMySubmitted(String username);

    /* ===== 用户撤回 / admin 删除 ===== */

    /**
     * 撤回或删除市场知识库（仅作者或 admin 可操作；级联清理 user_knowledge + role_knowledge）
     */
    void withdraw(String marketKnowledgeId);

    /* ===== 用户拉取 ===== */

    /**
     * 从市场订阅知识库（仅 APPROVED 可拉取，否则抛 403）
     */
    void pull(String username, String marketKnowledgeId);

    /**
     * 列出用户订阅的市场知识库
     */
    List<MarketKnowledgeRecord> listMyPulled(String username);
}
