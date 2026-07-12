package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.model.SkillRecord;
import cn.wubo.spring.ai.loom.agent.model.UserSkillPatchRequest;

import java.util.List;

public interface ISkillStorage {

    /**
     * 列出该用户名下可用的所有 Skill（admin 特权视图自动合并市场 APPROVED + 自己的 PENDING）。
     * 调用前会自动 computeUserSkills(username) 同步角色授权。
     */
    List<SkillRecord> list(String username);

    /**
     * 创建 / 覆盖一个自建 Skill（仅 USER_CREATED 走这条路径）。
     * 不允许 name 跟已有的 ROLE_GRANTED / MARKET_PULLED 冲突。
     */
    int save(SkillRecord skill, String username);

    /**
     * 按 name 取（admin 特权会从市场兜底）。
     */
    SkillRecord get(String name, String username);

    /**
     * 删（按权限矩阵：ROLE_GRANTED 不允许，USER_CREATED / MARKET_PULLED 允许）。
     */
    int remove(String name, String username);

    /**
     * 修改 desc / default_loaded（按权限矩阵校验；ROLE_GRANTED 整条都拒）。
     */
    int patch(String name, String username, UserSkillPatchRequest req);

    /**
     * 触发 computeUserSkills 同步（角色授权 → user_skill upsert）。
     * 一般不需要外部调用，list() 内部会自动调。
     */
    void sync(String username);

    /**
     * admin 视角：自己写的 PENDING + 所有 APPROVED（不写 user_skill，仅读）。
     * 用于 admin 在聊天界面用 union view。
     */
    List<SkillRecord> listForAdminUnionView(String username);
}
