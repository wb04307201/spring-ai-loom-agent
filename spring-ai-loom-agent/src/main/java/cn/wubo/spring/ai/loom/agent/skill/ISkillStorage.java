package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.model.SkillRecord;
import cn.wubo.spring.ai.loom.agent.model.UserSkillPatchRequest;

import java.util.List;

public interface ISkillStorage {

    /**
     * 列出该用户名下可用的所有 Skill（统一从 user_skill 出；admin 也只看到自己 user_skill，
     * 与普通用户行为一致）。调用前会自动 sync(username) 同步角色授权。
     */
    List<SkillRecord> list(String username);

    /**
     * 创建 / 覆盖一个自建 Skill（仅 USER_CREATED 走这条路径）。
     * 不允许 name 跟已有的 ROLE_GRANTED / MARKET_PULLED 冲突。
     */
    int save(SkillRecord skill, String username);

    /**
     * 按 name 取（仅查当前用户名下；不在 user_skill 则抛 "Skill 不存在或无权限"）。
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
     * 复制一个 Skill 为新的 USER_CREATED Skill（用户可任意修改）。
     * 来源 source 可以是 USER_CREATED / MARKET_PULLED（ROLE_GRANTED 角色授权不允许复制）。
     * newName 可空；空则用「&lt;sourceName&gt;_副本」；冲突自动追加 _2 / _3 ...。
     * 返回实际写入的 name（前端可用来跳转到新 skill 详情）。
     */
    String duplicate(String sourceName, String newName, String username);
}
