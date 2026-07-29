package cn.wubo.spring.ai.loom.agent.knowledge;

import cn.wubo.spring.ai.loom.agent.model.MarketKnowledgeRecord;

import java.util.List;

/** 知识库市场服务：市场浏览、审批、拉取 */
public interface IKnowledgeMarketService {

    /* ===== 市场浏览 ===== */

    /** 列出所有 APPROVED 的市场知识库 */
    List<MarketKnowledgeRecord> listApproved(int page, int size);

    /** 按 id 查询 */
    MarketKnowledgeRecord getById(String marketKnowledgeId);

    /** admin：列出全部（含 PENDING/REJECTED） */
    List<MarketKnowledgeRecord> listAllForAdmin();

    /** admin：列出 PENDING 等待审批 */
    List<MarketKnowledgeRecord> listPending();

    /* ===== 用户提交 ===== */

    /** 提交知识库到市场（status=PENDING） */
    MarketKnowledgeRecord submit(String knowledgeId);

    /* ===== admin 审批 ===== */

    /** 审批通过 */
    MarketKnowledgeRecord approve(String marketKnowledgeId);

    /** 拒绝 */
    MarketKnowledgeRecord reject(String marketKnowledgeId);

    /** 用户撤回自己的提交 */
    void withdraw(String marketKnowledgeId);

    /* ===== 用户拉取 ===== */

    /** 从市场订阅知识库 */
    void pull(String username, String marketKnowledgeId);

    /** 列出用户订阅的市场知识库 */
    List<MarketKnowledgeRecord> listMyPulled(String username);

    /* ===== 删除 ===== */

    /** 删除市场知识库（仅管理员或创建者） */
    void delete(String marketKnowledgeId);
}
