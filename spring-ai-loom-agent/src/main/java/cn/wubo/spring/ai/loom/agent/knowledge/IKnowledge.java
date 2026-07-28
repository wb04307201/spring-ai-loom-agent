package cn.wubo.spring.ai.loom.agent.knowledge;

import cn.wubo.spring.ai.loom.agent.model.KnowledgeRecord;

import java.util.List;

public interface IKnowledge {

    List<KnowledgeRecord> list();

    List<KnowledgeRecord> list(String username);

    KnowledgeRecord insert(String name, String description);

    KnowledgeRecord update(String id, String name, String description);

    int delete(String id);

}
