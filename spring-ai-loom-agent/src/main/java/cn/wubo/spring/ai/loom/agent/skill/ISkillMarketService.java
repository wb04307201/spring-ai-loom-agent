package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.model.MarketSkill;
import cn.wubo.spring.ai.loom.agent.model.MarketSkillSubmitRequest;
import cn.wubo.spring.ai.loom.agent.model.MarketSkillUpsertRequest;
import cn.wubo.spring.ai.loom.agent.model.UserSkill;

import java.util.List;

public interface ISkillMarketService {

    /* ===== 市场浏览 ===== */

    /** 任意用户：列出所有 APPROVED 的市场 Skill（按 author, version 排序） */
    List<MarketSkill> listApproved();

    /** 任意用户：按 id 查 */
    MarketSkill get(Long id);

    /** admin：列出全部（含 PENDING/REJECTED） */
    List<MarketSkill> listAllForAdmin();

    /** admin：列出 PENDING 等待审批 */
    List<MarketSkill> listPending();

    /* ===== 用户提交 ===== */

    /** 任意用户：提交到市场（status=PENDING，author=username） */
    MarketSkill submit(String username, MarketSkillSubmitRequest req);

    /* ===== admin 审批 ===== */

    MarketSkill approve(String adminUsername, Long id, String comment);

    MarketSkill reject(String adminUsername, Long id, String comment);

    /* ===== admin 直接 CRUD（绕过审批） ===== */

    MarketSkill adminCreate(String adminUsername, MarketSkillUpsertRequest req);

    MarketSkill adminUpdate(String adminUsername, Long id, MarketSkillUpsertRequest req);

    void adminDelete(String adminUsername, Long id);

    /* ===== 用户拉取 ===== */

    /**
     * 任意用户：从市场把 skill 拉到自己的 user_skill（source=MARKET_PULLED）。
     * 若同 name 已被 ROLE_GRANTED 锁定，抛错。
     * 若同 name 已是 MARKET_PULLED，刷新 content。
     */
    UserSkill pull(String username, Long marketSkillId);

    /* ===== 用户查看/撤回 ===== */

    /** 查看我提交到市场的技能（含 PENDING/APPROVED/REJECTED） */
    List<MarketSkill> listMySubmitted(String username);

    /** 撤回 PENDING 状态的提交（仅 author 本人可操作） */
    boolean withdraw(String username, Long marketSkillId);
}
