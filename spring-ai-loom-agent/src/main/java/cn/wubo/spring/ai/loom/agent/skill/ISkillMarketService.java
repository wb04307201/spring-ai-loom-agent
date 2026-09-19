package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.market.IMarketContentAdminService;
import cn.wubo.spring.ai.loom.agent.model.MarketSkill;
import cn.wubo.spring.ai.loom.agent.model.MarketSkillSubmitRequest;
import cn.wubo.spring.ai.loom.agent.model.UserSkill;

import java.util.List;

/**
 * M3+ T3.1 — v1 skill market service interface. <b>Deprecated</b>: the generic
 * {@link IMarketContentAdminService}<code>&lt;Long, MarketSkill, ...&gt;</code>
 * (implemented by {@code DefaultSkillMarketService}) is the v2 path. This
 * interface remains as a shim for 1 minor version per ADR-T03; once the
 * call-site grep {@code ISkillMarketService} drops to 0, delete this file.
 *
 * @deprecated use {@link IMarketContentAdminService} via {@code DefaultSkillMarketService}
 */
@Deprecated
public interface ISkillMarketService {

    /* ===== 市场浏览 ===== */

    /**
     * 任意用户：列出所有 APPROVED 的市场 Skill（按 author, version 排序）
     */
    List<MarketSkill> listApproved();

    /**
     * 任意用户：按 id 查
     */
    MarketSkill get(Long id);

    /**
     * admin：列出全部（含 PENDING / APPROVED / REJECTED 三种状态，审批流 #4）
     */
    List<MarketSkill> listAllForAdmin();

    /* ===== 用户提交 ===== */

    /**
     * 任意用户：提交到市场（审批流 #4：新建行落 status='PENDING'，created_by_kind='USER'，
     * 等 admin approve/reject；reject 必须填评论）。
     * 同一作者+name 已存在：REJECTED → 旧行归档到 market_skill_archive（保留 id + 拒绝评论/审核人/时间）
     * 并新建 PENDING 行（新 id）；PENDING/APPROVED → 仅原地更新内容，状态不动（APPROVED 永不降级）。
     * 成功后反写 author 自己的 user_skill.market_skill_id 指向当前 market_skill 行。
     */
    MarketSkill submit(String username, MarketSkillSubmitRequest req);

    /* ===== 用户拉取 ===== */

    /**
     * 任意用户：从市场把 skill 拉到自己的 user_skill（source=MARKET_PULLED）。
     * 仅 APPROVED 可拉取，否则抛 403（审批流 #4）。
     * 若同 name 已被 ROLE_GRANTED 锁定，抛错。
     * 若同 name 已是 MARKET_PULLED，刷新 content。
     */
    UserSkill pull(String username, Long marketSkillId);

    /* ===== 用户查看/撤回 ===== */

    /**
     * 查看我提交到市场的技能（全状态：PENDING / APPROVED / REJECTED）
     */
    List<MarketSkill> listMySubmitted(String username);

    /**
     * 撤回我自己的市场 Skill（仅 author 本人可操作；任意状态都可撤回，删 market_skill + 反清空 author user_skill.market_skill_id）
     */
    boolean withdraw(String username, Long marketSkillId);
}
