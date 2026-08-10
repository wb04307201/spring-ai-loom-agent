package cn.wubo.spring.ai.loom.agent.document;

import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;

import java.util.List;

public interface IDocumentRead {

    List<Document> read(Resource fileResource, String knowledgeId);
}
