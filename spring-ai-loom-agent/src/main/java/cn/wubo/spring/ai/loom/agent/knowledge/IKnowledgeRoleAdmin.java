package cn.wubo.spring.ai.loom.agent.knowledge;

import cn.wubo.spring.ai.loom.agent.model.MarketKnowledgeRecord;
import cn.wubo.spring.ai.loom.agent.model.RoleKnowledgeItem;

import java.util.List;

/** 知识库角色管理：角色绑定、同步到用户 */
public interface IKnowledgeRoleAdmin {

    /** 列出角色关联的市场知识库（带 defaultEnabled） */
    List<RoleKnowledgeItem> getRoleKnowledges(String roleCode);

    /** 列出角色关联的市场知识库完整信息（按 sort_order） */
    List<MarketKnowledgeRecord> listRoleKnowledges(String roleCode);

    /** 覆盖式设置角色关联的知识库列表 */
    void setRoleKnowledges(String roleCode, List<RoleKnowledgeItem> items);

    /** 同步角色知识库到用户（插入 user_knowledge, source=ROLE_GRANTED, locked=TRUE） */
    void syncUserKnowledge(String username);
}
