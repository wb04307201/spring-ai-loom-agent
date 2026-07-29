package cn.wubo.spring.ai.loom.agent.knowledge;

import cn.wubo.spring.ai.loom.agent.model.KnowledgeRecord;

import java.util.List;

public interface IKnowledge {

    List<KnowledgeRecord> list();

    List<KnowledgeRecord> list(String username);

    KnowledgeRecord insert(String name, String description);

    KnowledgeRecord update(String id, String name, String description);

    int delete(String id);

    /**
     * 获取用户可用的知识库（自己的 + 市场订阅的 + 角色授予的）
     */
    List<KnowledgeRecord> listAccessible(String username);

    /**
     * 检查当前用户是否有权编辑该知识库（仅原始创建者可编辑）
     */
    boolean canEdit(String knowledgeId);

}
