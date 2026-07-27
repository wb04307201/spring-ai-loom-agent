package cn.wubo.spring.ai.loom.agent.tool.knowledge;

import cn.wubo.spring.ai.loom.agent.knowledge.IKnowledge;
import cn.wubo.spring.ai.loom.agent.model.KnowledgeRecord;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.util.List;

public class DefaultKnowledgeTool implements IKnowledgeTool {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final IKnowledge knowledge;
    private final VectorStore vectorStore;
    private final LoomAgentProperties.RagProperty ragProperty;

    public DefaultKnowledgeTool(IKnowledge knowledge, VectorStore vectorStore, LoomAgentProperties.RagProperty ragProperty) {
        this.knowledge = knowledge;
        this.vectorStore = vectorStore;
        this.ragProperty = ragProperty;
    }

    @Override
    @Tool(description = "分页列出当前用户启用的知识库，包含知识库名称和描述。默认每页20条。")
    public String listKnowledgeBases(
        @ToolParam(description = "页码，从1开始") Integer page,
        @ToolParam(description = "每页数量，-1表示全部") Integer size,
        ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        List<KnowledgeRecord> allKnowledgeBases = knowledge.list();

        // Filter to current user's knowledge bases (IKnowledge.list() already does this, but be explicit)
        List<KnowledgeRecord> userKnowledgeBases = allKnowledgeBases.stream()
            .filter(kb -> username != null && username.equals(kb.username()))
            .toList();

        int total = userKnowledgeBases.size();
        int pageSize = (size == null || size <= 0) ? DEFAULT_PAGE_SIZE : size;
        int currentPage = (page == null || page < 1) ? 1 : page;

        List<KnowledgeRecord> pageItems;
        int totalPages;

        if (pageSize == -1) {
            pageItems = userKnowledgeBases;
            totalPages = 1;
            currentPage = 1;
        } else {
            totalPages = (int) Math.ceil((double) total / pageSize);
            int fromIndex = (currentPage - 1) * pageSize;
            int toIndex = Math.min(fromIndex + pageSize, total);
            pageItems = (fromIndex < total) ? userKnowledgeBases.subList(fromIndex, toIndex) : List.of();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("知识库目录（共 %d 个，第 %d/%d 页）:%n%n", total, currentPage, totalPages));
        sb.append(String.format("%-36s %-20s %-50s%n", "知识库ID", "知识库名称", "知识库描述"));
        sb.append("-".repeat(110)).append("\n");

        for (KnowledgeRecord kb : pageItems) {
            sb.append(String.format("%-36s %-20s %-50s%n", kb.id(), kb.name(), kb.description()));
        }

        if (totalPages > 1 && pageSize != -1) {
            sb.append(String.format("%n提示：共 %d 页，调用 @listKnowledgeBases {\"page\": %d} 查看下一页，或 @listKnowledgeBases {\"size\": -1} 查看全部",
                totalPages, currentPage + 1));
        }

        if (total == 0) {
            sb.append("当前用户暂无知识库。");
        }

        return sb.toString();
    }

    @Override
    @Tool(description = "在指定知识库中检索相关文档片段。当用户的问题可能涉及知识库中的内容时调用此工具。")
    public String searchKnowledge(
        @ToolParam(description = "知识库ID") String knowledgeId,
        @ToolParam(description = "检索查询关键词") String query,
        @ToolParam(description = "返回结果数量，不传则使用全局默认值") Integer topK,
        ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        int actualTopK = (topK == null || topK <= 0) ? ragProperty.getTopK() : topK;
        double threshold = ragProperty.getSimilarityThreshold();

        // Build SpEL filter expression: type == 'knowledge' && knowledgeId == ? && username == ?
        String filterExpression = "type == 'knowledge' && knowledgeId == '" + knowledgeId + "' && username == '" + username + "'";

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
