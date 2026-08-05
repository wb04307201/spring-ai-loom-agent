package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.knowledge.IKnowledge;
import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.model.ChatRequestRecord;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.model.UserConversationRecord;
import cn.wubo.spring.ai.loom.agent.skill.ISkillStorage;
import cn.wubo.spring.ai.loom.agent.user.IUserConversation;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * DefaultChat 单元测试 —— 重点回归 toolContext 注入语义：
 * Spring AI 的 toolContext 断言「values 不能含 null」，前端未选知识库时
 * enabledKnowledgeIds 为 null，必须缺省（不 put）而非 put null。
 */
@DisplayName("DefaultChat 单元测试")
class DefaultChatTest {

    private ChatClient.ChatClientRequestSpec requestSpec;
    private DefaultChat chat;
    private IUserConversation userConversation;
    private HttpServletRequest request;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ChatClient chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.tools(any())).thenReturn(requestSpec);
        when(requestSpec.toolContext(any())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.<ChatResponse>empty());

        IMcp mcp = mock(IMcp.class);
        when(mcp.getVisibleToolCallbackProvider(anyString(), any())).thenReturn(null);
        userConversation = mock(IUserConversation.class);
        IFile file = mock(IFile.class);
        ISkillStorage skillStorage = mock(ISkillStorage.class);
        when(skillStorage.list(anyString())).thenReturn(List.of());
        IKnowledge knowledge = mock(IKnowledge.class);
        when(knowledge.list(anyString())).thenReturn(List.of());
        LoomAgentProperties properties = mock(LoomAgentProperties.class);
        when(properties.getDefaultSystem()).thenReturn("base-system");

        request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(8080);

        chat = new DefaultChat(chatClient, mcp, List.of(), userConversation, file, skillStorage, knowledge, properties);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureToolContext(ChatRequestRecord record) {
        chat.stream(record, "alice", request);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(requestSpec).toolContext(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("enabledKnowledgeIds=null：不抛异常且 key 缺省、全 map 无 null 值")
    void nullKnowledgeIdsKeyAbsentAndNoNullValues() {
        ChatRequestRecord record = new ChatRequestRecord("hi", "conv-1", null, null, null);

        Map<String, Object> props = captureToolContext(record);

        assertFalse(props.containsKey("enabledKnowledgeIds"), "null 时必须缺省，不能 put null（Spring AI 断言）");
        assertTrue(props.values().stream().noneMatch(java.util.Objects::isNull), "toolContext 不允许 null 值");
    }

    @Test
    @DisplayName("enabledKnowledgeIds 非 null：原样注入")
    void nonNullKnowledgeIdsInjected() {
        ChatRequestRecord record = new ChatRequestRecord("hi", "conv-1", null, List.of("kb-1", "kb-2"), null);

        Map<String, Object> props = captureToolContext(record);

        assertEquals(List.of("kb-1", "kb-2"), props.get("enabledKnowledgeIds"));
    }

    @Test
    @DisplayName("username/parentConversationId/baseUrl 恒注入")
    void coreKeysAlwaysPresent() {
        ChatRequestRecord record = new ChatRequestRecord("hi", "conv-9", null, null, null);

        Map<String, Object> props = captureToolContext(record);

        assertEquals("alice", props.get("username"));
        assertEquals("conv-9", props.get("parentConversationId"));
        assertEquals("http://localhost:8080", props.get("baseUrl"));
    }

    @Test
    @DisplayName("客户端未传 conversationId：兜底生成并写入 user_conversation")
    void autoGeneratesConversationIdWhenMissing() {
        when(userConversation.exists(any())).thenReturn(false);
        ChatRequestRecord record = new ChatRequestRecord("hi", null, null, null, null);

        Map<String, Object> props = captureToolContext(record);

        assertNotNull(props.get("parentConversationId"));
        assertFalse(((String) props.get("parentConversationId")).isBlank());
        ArgumentCaptor<UserConversationRecord> insertCaptor = ArgumentCaptor.forClass(UserConversationRecord.class);
        verify(userConversation).insert(insertCaptor.capture());
        assertEquals("alice", insertCaptor.getValue().username());
        assertEquals(props.get("parentConversationId"), insertCaptor.getValue().conversationId());
    }

    @Test
    @DisplayName("会话已存在：不重复 insert")
    void noInsertWhenConversationExists() {
        when(userConversation.exists(any())).thenReturn(true);
        ChatRequestRecord record = new ChatRequestRecord("hi", "conv-1", null, null, null);

        captureToolContext(record);

        verify(userConversation, never()).insert(any());
    }

    @Test
    @DisplayName("上游 Arrearage：Flux 转为可读中文错误，不再抛异常")
    void upstreamArrearageBecomesReadable() {
        ChatClient.ChatClientRequestSpec spec = requestSpec;
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        // 覆盖到 onErrorResume 分支，需要重置 spec 上的 stub 并重新打桩（setUp 中其它 stub 失效可接受）
        org.mockito.Mockito.reset(spec);
        RuntimeException ex = new RuntimeException(
                "HTTP 400 - {\"code\":\"Arrearage\",\"message\":\"Access denied, please make sure your account is in good standing.\"}");
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.tools(any())).thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
        when(spec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(reactor.core.publisher.Flux.error(ex));

        ChatRequestRecord record = new ChatRequestRecord("hi", "conv-err", null, null, null);
        reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> flux = chat.stream(record, "alice", request);

        java.util.List<org.springframework.ai.chat.model.ChatResponse> out = flux.collectList().block();
        assertNotNull(out);
        assertEquals(1, out.size(), "上游异常应被转换为单条 ChatResponse");
        String text = out.get(0).getResult().getOutput().getText();
        assertTrue(text.contains("欠费") || text.contains("充值"), "应给出可读的中文错误消息，实际：" + text);
    }
}
