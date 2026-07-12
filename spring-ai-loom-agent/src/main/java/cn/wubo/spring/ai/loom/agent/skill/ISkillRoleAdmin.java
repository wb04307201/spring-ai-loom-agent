package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.model.MarketSkill;
import cn.wubo.spring.ai.loom.agent.model.RoleSkillItem;

import java.util.List;

public interface ISkillRoleAdmin {

    /** 列出某角色已授权的市场 Skill（带 defaultLoaded） */
    List<RoleSkillItem> getRoleSkills(String roleCode);

    /** 列出某角色已授权的市场 Skill 完整信息（按 sort_order） */
    List<MarketSkill> listRoleSkills(String roleCode);

    /** 覆盖式设置角色授权的 Skill 列表 */
    void setRoleSkills(String roleCode, List<RoleSkillItem> items);
}
