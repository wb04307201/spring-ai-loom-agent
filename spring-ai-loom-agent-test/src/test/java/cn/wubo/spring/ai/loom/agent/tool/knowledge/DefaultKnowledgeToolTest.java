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
 * 1. listKnowledgeBases 分页列出知识库，默认每页20条
 * 2. listKnowledgeBases size=-1 返回全部
 * 3. listKnowledgeBases 空列表
 * 4. searchKnowledge 使用 VectorStore 带 filterExpression 检索
 * 5. username 通过 ToolContext 正确传递
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

    // ──────────── listKnowledgeBases ────────────

    @Test
    @DisplayName("listKnowledgeBases 列出当前用户的知识库")
    void listKnowledgeBases_listsUserKnowledgeBases() {
        when(knowledge.list("alice")).thenReturn(List.of(
                new KnowledgeRecord("kb-1", "alice", "产品手册", "包含产品文档"),
                new KnowledgeRecord("kb-2", "alice", "技术文档", "包含技术资料")
        ));

        String result = tool.listKnowledgeBases(1, 20, ctx("alice"));
        assertTrue(result.contains("kb-1"));
        assertTrue(result.contains("产品手册"));
        assertTrue(result.contains("包含产品文档"));
        assertTrue(result.contains("kb-2"));
        assertTrue(result.contains("技术文档"));
        assertTrue(result.contains("共 2 个"), "应只列出 alice 的 2 个知识库: " + result);
        verify(knowledge).list("alice");
    }

    @Test
    @DisplayName("listKnowledgeBases 无知识库时显示空列表")
    void listKnowledgeBases_emptyList() {
        when(knowledge.list("bob")).thenReturn(List.of());
        String result = tool.listKnowledgeBases(1, 20, ctx("bob"));
        assertTrue(result.contains("共 0 个"), "应提示 0 个知识库: " + result);
        assertTrue(result.contains("暂无知识库"), "应提示暂无知识库: " + result);
    }

    @Test
    @DisplayName("listKnowledgeBases 默认 null 参数使用默认值")
    void listKnowledgeBases_defaultParams() {
        when(knowledge.list("dave")).thenReturn(List.of());
        String result = tool.listKnowledgeBases(null, null, ctx("dave"));
        assertTrue(result.contains("共 0 个"));
        assertTrue(result.contains("第 1/0 页"));
    }

    @Test
    @DisplayName("listKnowledgeBases size=-1 返回全部")
    void listKnowledgeBases_allKnowledgeBases() {
        List<KnowledgeRecord> kbs = List.of(
                new KnowledgeRecord("kb-1", "eve", "描述1", "详情1"),
                new KnowledgeRecord("kb-2", "eve", "描述2", "详情2"),
                new KnowledgeRecord("kb-3", "eve", "描述3", "详情3")
        );
        when(knowledge.list("eve")).thenReturn(kbs);

        String result = tool.listKnowledgeBases(1, -1, ctx("eve"));
        assertTrue(result.contains("kb-1"));
        assertTrue(result.contains("kb-2"));
        assertTrue(result.contains("kb-3"));
        assertTrue(result.contains("共 3 个"));
        assertFalse(result.contains("下一页"), "size=-1 不应有下一页提示: " + result);
    }

    @Test
    @DisplayName("listKnowledgeBases 翻页提示下一页")
    void listKnowledgeBases_nextPageHint() {
        List<KnowledgeRecord> kbs = List.of(
                new KnowledgeRecord("kb-1", "frank", "d1", "c1"),
                new KnowledgeRecord("kb-2", "frank", "d2", "c2"),
                new KnowledgeRecord("kb-3", "frank", "d3", "c3")
        );
        when(knowledge.list("frank")).thenReturn(kbs);

        String result = tool.listKnowledgeBases(1, 2, ctx("frank"));
        assertTrue(result.contains("第 1/2 页"), "应显示第1页: " + result);
        assertTrue(result.contains("下一页"), "应有下一页提示: " + result);
    }

    @Test
    @DisplayName("listKnowledgeBases enabledKnowledgeIds 只列出选中的知识库")
    void listKnowledgeBases_filtersByEnabledIds() {
        when(knowledge.list("alice")).thenReturn(List.of(
                new KnowledgeRecord("kb-1", "alice", "产品手册", "包含产品文档"),
                new KnowledgeRecord("kb-2", "alice", "技术文档", "包含技术资料"),
                new KnowledgeRecord("kb-3", "alice", "其他", "其他内容")
        ));

        Map<String, Object> ctxMap = new HashMap<>();
        ctxMap.put("username", "alice");
        ctxMap.put("enabledKnowledgeIds", List.of("kb-1", "kb-3"));
        ToolContext ctx = new ToolContext(ctxMap);

        String result = tool.listKnowledgeBases(1, 20, ctx);
        assertTrue(result.contains("kb-1"));
        assertTrue(result.contains("kb-3"));
        assertFalse(result.contains("技术文档"), "未选中的 kb-2 不应出现: " + result);
        assertTrue(result.contains("共 2 个"), "应只列出 2 个启用的知识库: " + result);
    }

    // ──────────── searchKnowledge ────────────

    @Test
    @DisplayName("searchKnowledge 构建正确的 filterExpression")
    void searchKnowledge_buildsFilterExpression() {
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
        assertTrue(filterStr.contains("alice"), "应包含 username: " + filterStr);
        assertTrue(filterStr.contains("type") && filterStr.contains("knowledge"), "应包含 type 过滤: " + filterStr);
    }

    @Test
    @DisplayName("searchKnowledge 使用自定义 topK")
    void searchKnowledge_customTopK() {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        when(vectorStore.similaritySearch(captor.capture())).thenReturn(List.of());

        tool.searchKnowledge("kb-1", "测试", 10, ctx("grace"));

        SearchRequest req = captor.getValue();
        assertEquals(10, req.getTopK());
    }

    @Test
    @DisplayName("searchKnowledge 空结果提示")
    void searchKnowledge_emptyResults() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        String result = tool.searchKnowledge("kb-1", "不存在的内容", null, ctx("henry"));
        assertTrue(result.contains("未检索到"), "空结果应有提示: " + result);
    }

    @Test
    @DisplayName("searchKnowledge 有结果时显示文档片段")
    void searchKnowledge_withResults() {
        Document doc = new Document("这是产品文档内容");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        String result = tool.searchKnowledge("kb-1", "产品", null, ctx("ivan"));
        assertTrue(result.contains("这是产品文档内容"), "应包含文档内容: " + result);
        assertTrue(result.contains("片段 1/1"), "应显示片段编号: " + result);
    }

    @Test
    @DisplayName("searchKnowledge 未启用的知识库返回错误")
    void searchKnowledge_rejectsDisabledKnowledgeId() {
        Map<String, Object> ctxMap = new HashMap<>();
        ctxMap.put("username", "alice");
        ctxMap.put("enabledKnowledgeIds", List.of("kb-1", "kb-2"));
        ToolContext ctx = new ToolContext(ctxMap);

        String result = tool.searchKnowledge("kb-999", "产品", null, ctx);
        assertTrue(result.contains("错误"), "应返回错误信息: " + result);
        assertTrue(result.contains("kb-999"), "应包含被拒绝的 ID: " + result);
        verifyNoInteractions(vectorStore);
    }

    @Test
    @DisplayName("searchKnowledge enabledKnowledgeIds 为空时不做校验")
    void searchKnowledge_noEnabledIds_noValidation() {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        when(vectorStore.similaritySearch(captor.capture())).thenReturn(List.of());

        tool.searchKnowledge("kb-1", "产品", null, ctx("alice"));

        SearchRequest req = captor.getValue();
        assertEquals("产品", req.getQuery());
        verify(vectorStore).similaritySearch(any(SearchRequest.class));
    }
}
