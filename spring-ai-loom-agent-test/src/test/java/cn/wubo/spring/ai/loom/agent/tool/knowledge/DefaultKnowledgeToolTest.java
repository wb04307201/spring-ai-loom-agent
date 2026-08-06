package cn.wubo.spring.ai.loom.agent.tool.knowledge;

import cn.wubo.spring.ai.loom.agent.knowledge.IKnowledge;
import cn.wubo.spring.ai.loom.agent.model.KnowledgeRecord;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * DefaultKnowledgeTool 单元测试
 * <p>
 * 覆盖：
 * 1. searchKnowledge 使用 VectorStore 带 filterExpression 检索
 * 2. username 通过 ToolContext 正确传递
 * <p>
 * 移除：listKnowledgeBases（已删除；KB 列表在 system prompt【知识库】段自动展示）
 */
@DisplayName("DefaultKnowledgeTool 单元测试")
class DefaultKnowledgeToolTest {

    private IKnowledge knowledge;
    private VectorStore vectorStore;
    private LoomAgentProperties.RagProperty ragProperty;
    private DefaultKnowledgeTool tool;

    @BeforeEach
    void setUp() {
        knowledge = mock(IKnowledge.class);
        vectorStore = mock(VectorStore.class);
        ragProperty = new LoomAgentProperties.RagProperty();
        ragProperty.setTopK(4);
        ragProperty.setSimilarityThreshold(0.0);
        tool = new DefaultKnowledgeTool(knowledge, vectorStore, ragProperty);
    }

    private static ToolContext ctx(String username) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("username", username);
        return new ToolContext(ctx);
    }

    // ──────────── searchKnowledge ────────────

    @Test
    @DisplayName("searchKnowledge 构建 filterExpression 不再包含 username")
    void searchKnowledge_buildsFilterExpression() {
        when(knowledge.listAccessible("alice")).thenReturn(List.of(
                new KnowledgeRecord("kb-1", "alice", "产品手册", "包含产品文档")
        ));
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        when(vectorStore.similaritySearch(captor.capture())).thenReturn(List.of());

        tool.searchKnowledge("kb-1", "产品", null, ctx("alice"));

        SearchRequest req = captor.getValue();
        assertEquals("产品", req.getQuery());
        assertEquals(4, req.getTopK());
        var filter = req.getFilterExpression();
        assertNotNull(filter, "filterExpression 不应为 null");
        String filterStr = filter.toString();
        assertTrue(filterStr.contains("kb-1"), "应包含 knowledgeId: " + filterStr);
        assertFalse(filterStr.contains("alice"), "不应包含 username: " + filterStr);
        assertTrue(filterStr.contains("type") && filterStr.contains("knowledge"), "应包含 type 过滤: " + filterStr);
        verify(knowledge).listAccessible("alice");
    }

    @Test
    @DisplayName("searchKnowledge 使用自定义 topK")
    void searchKnowledge_customTopK() {
        when(knowledge.listAccessible("grace")).thenReturn(List.of(
                new KnowledgeRecord("kb-1", "grace", "KB", "desc")
        ));
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        when(vectorStore.similaritySearch(captor.capture())).thenReturn(List.of());

        tool.searchKnowledge("kb-1", "测试", 10, ctx("grace"));

        SearchRequest req = captor.getValue();
        assertEquals(10, req.getTopK());
    }

    @Test
    @DisplayName("searchKnowledge 空结果提示")
    void searchKnowledge_emptyResults() {
        when(knowledge.listAccessible("henry")).thenReturn(List.of(
                new KnowledgeRecord("kb-1", "henry", "KB", "desc")
        ));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        String result = tool.searchKnowledge("kb-1", "不存在的内容", null, ctx("henry"));
        assertTrue(result.contains("未检索到"), "空结果应有提示: " + result);
    }

    @Test
    @DisplayName("searchKnowledge 有结果时显示文档片段")
    void searchKnowledge_withResults() {
        when(knowledge.listAccessible("ivan")).thenReturn(List.of(
                new KnowledgeRecord("kb-1", "ivan", "KB", "desc")
        ));
        Document doc = new Document("这是产品文档内容");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        String result = tool.searchKnowledge("kb-1", "产品", null, ctx("ivan"));
        assertTrue(result.contains("这是产品文档内容"), "应包含文档内容: " + result);
        assertTrue(result.contains("片段 1/1"), "应显示片段编号: " + result);
    }

    @Test
    @DisplayName("searchKnowledge 权限不足时返回错误信息")
    void searchKnowledge_rejectsWithoutAccess() {
        when(knowledge.listAccessible("alice")).thenReturn(List.of(
                new KnowledgeRecord("kb-1", "alice", "产品手册", "包含产品文档")
        ));

        String result = tool.searchKnowledge("kb-999", "产品", null, ctx("alice"));
        assertTrue(result.contains("没有权限访问该知识库"), "应返回权限错误: " + result);
        verifyNoInteractions(vectorStore);
    }

    @Test
    @DisplayName("searchKnowledge 有权限时正常检索")
    void searchKnowledge_withAccess_searches() {
        when(knowledge.listAccessible("alice")).thenReturn(List.of(
                new KnowledgeRecord("kb-1", "alice", "产品手册", "包含产品文档")
        ));
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        when(vectorStore.similaritySearch(captor.capture())).thenReturn(List.of());

        tool.searchKnowledge("kb-1", "产品", null, ctx("alice"));

        SearchRequest req = captor.getValue();
        assertEquals("产品", req.getQuery());
        verify(vectorStore).similaritySearch(any(SearchRequest.class));
    }
}
