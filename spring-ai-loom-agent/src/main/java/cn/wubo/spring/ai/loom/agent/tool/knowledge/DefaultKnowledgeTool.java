package cn.wubo.spring.ai.loom.agent.tool.knowledge;

import cn.wubo.spring.ai.loom.agent.knowledge.IKnowledge;
import cn.wubo.spring.ai.loom.agent.model.KnowledgeRecord;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

public class DefaultKnowledgeTool implements IKnowledgeTool {

    private final IKnowledge knowledge;
    private final VectorStore vectorStore;
    private final LoomAgentProperties.RagProperty ragProperty;

    public DefaultKnowledgeTool(IKnowledge knowledge, VectorStore vectorStore, LoomAgentProperties.RagProperty ragProperty) {
        this.knowledge = knowledge;
        this.vectorStore = vectorStore;
        this.ragProperty = ragProperty;
    }

    /**
     * 已删除 listKnowledgeBases(page, size)。
     * 原因：知识库列表在 system prompt【知识库】段自动展示（ID+名称+摘要），
     * 让 LLM 再调工具列一遍是冗余。LLM 应当根据 prompt 中的信息直接决策。
     */

    @Override
    @Tool(description = "在指定知识库中检索相关文档片段。当用户的问题可能涉及知识库中的内容时调用此工具。")
    public String searchKnowledge(
            @ToolParam(description = "知识库ID") String knowledgeId,
            @ToolParam(description = "检索查询关键词") String query,
            @ToolParam(description = "返回结果数量，不传则使用全局默认值") Integer topK,
            ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");

        // 检查用户是否有权限访问该知识库（自己的、订阅的、角色授予的）
        List<KnowledgeRecord> accessible = knowledge.listAccessible(username);
        boolean hasAccess = accessible.stream().anyMatch(kb -> kb.id().equals(knowledgeId));
        if (!hasAccess) {
            return "没有权限访问该知识库";
        }

        int actualTopK = (topK == null || topK <= 0) ? ragProperty.getTopK() : topK;
        double threshold = ragProperty.getSimilarityThreshold();

        // Build SpEL filter expression: type == 'knowledge' && knowledgeId == ?
        String filterExpression = "type == 'knowledge' && knowledgeId == '" + knowledgeId + "'";

        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(actualTopK)
                        .similarityThreshold(threshold)
                        .filterExpression(filterExpression)
                        .build()
        );

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("检索结果（知识库ID: %s，查询: %s，共 %d 条）:%n%n", knowledgeId, query, results.size()));

        if (results.isEmpty()) {
            sb.append("未检索到相关文档片段。");
        } else {
            for (int i = 0; i < results.size(); i++) {
                Document doc = results.get(i);
                sb.append(String.format("--- 片段 %d/%d ---%n", i + 1, results.size()));
                sb.append(String.format("相似度: %.4f%n", doc.getScore() != null ? doc.getScore() : 0.0));
                sb.append(String.format("内容: %s%n%n", doc.getText()));
            }
            sb.append(String.format("%n提示：共返回 %d 条相关片段，可用于回答用户问题。", results.size()));
        }

        return sb.toString();
    }
}
