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

 /** admin：列出全部（含 PENDING/APPROVED/REJECTED 状态——起无审批流，所有提交直接 APPROVED） */
 List<MarketSkill> listAllForAdmin();

 /* ===== 用户提交 ===== */

 /**
 * 任意用户：提交到市场（起无需审批，直接 status='APPROVED'）。
 * 同一作者+name 已存在 → UPSERT（重置 reviewed_at/reviewed_by）；不存在 → INSERT。
 * 成功后反写 author 自己的 user_skill.market_skill_id 指向新行。
 */
 MarketSkill submit(String username, MarketSkillSubmitRequest req);

 /* ===== admin 直接 CRUD ===== */

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

 /** 查看我提交到市场的技能（起所有提交都是 APPROVED） */
 List<MarketSkill> listMySubmitted(String username);

 /** 撤回我自己的市场 Skill（仅 author 本人可操作；任意状态都可撤回，删 market_skill + 反清空 author user_skill.market_skill_id） */
 boolean withdraw(String username, Long marketSkillId);
}
