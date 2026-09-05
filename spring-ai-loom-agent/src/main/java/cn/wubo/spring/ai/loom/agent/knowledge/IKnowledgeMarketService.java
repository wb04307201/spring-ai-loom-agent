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
     * 列出所有 APPROVED 的市场知识库（所有提交都是 APPROVED）
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
     * 提交知识库到市场（直接 APPROVED，无审批）。
     * 同一 username+name 已存在 → UPSERT（重置 reviewed_at/reviewed_by）；否则 INSERT。
     */
    MarketKnowledgeRecord submit(String knowledgeId);

    /**
     * 列出当前用户提交的市场知识库（所有都是 APPROVED）
     */
    List<MarketKnowledgeRecord> listMySubmitted(String username);

    /* ===== 用户撤回 / admin 删除 ===== */

    /**
     * 撤回或删除市场知识库（仅作者或 admin 可操作；级联清理 user_knowledge + role_knowledge）
     */
    void withdraw(String marketKnowledgeId);

    /* ===== 用户拉取 ===== */

    /**
     * 从市场订阅知识库（不再校验 APPROVED，提交即上架）
     */
    void pull(String username, String marketKnowledgeId);

    /**
     * 列出用户订阅的市场知识库
     */
    List<MarketKnowledgeRecord> listMyPulled(String username);
}
