package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.capability.CapabilityService;
import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.knowledge.IKnowledge;
import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.skill.ISkillStorage;
import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.IToolCallLogRepository;
import cn.wubo.spring.ai.loom.agent.user.IUserConversation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.io.JsonEOFException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * friendlyMessage JsonEOFException 分支回归测试。
 * <p>
 * 背景:2026-09-21 生产事故 —— Spring AI 1.1.8 的
 * {@code StreamHelper.mergeToolUseEvents → ToolUseAggregationEvent.squashIntoContentBlock → ModelOptionsUtils.jsonToMap}
 * 在 tool_use input_json 流被截断时,对不完整字符串触发 Jackson 的
 * {@link JsonEOFException}("Unexpected end-of-input: was expecting closing quote for a string value")。
 * 修复前 DefaultChat.friendlyMessage 把它包成 "聊天服务异常(RuntimeException),请稍后重试",
 * 把根因误导给用户。修复后(JsonEOFException 分支)返回具体可操作提示。
 */
@DisplayName("DefaultChat.friendlyMessage JsonEOFException 回归测试")
class FriendlyMessageJsonEofTest {

    private DefaultChat chat;
    private Method friendlyMessage;

    @BeforeEach
    void setUp() throws Exception {
        LoomAgentProperties properties = new LoomAgentProperties();
        properties.setDefaultSystem("test");
        chat = new DefaultChat(
                mock(ChatClient.class),
                mock(IMcp.class),
                List.<IEmbedTool>of(),
                mock(IUserConversation.class),
                mock(IFile.class),
                mock(ISkillStorage.class),
                mock(IKnowledge.class),
                properties,
                mock(IToolCallLogRepository.class),
                mock(CapabilityService.class));
        friendlyMessage = DefaultChat.class.getDeclaredMethod("friendlyMessage", Throwable.class);
        friendlyMessage.setAccessible(true);
    }

    @Test
    @DisplayName("直接 JsonEOFException → 返回 tool_use JSON 截断提示")
    void directJsonEOFException() throws Exception {
        JsonEOFException eof = new JsonEOFException(mock(JsonParser.class), null, "Unexpected end-of-input: was expecting closing quote for a string value");
        String msg = (String) friendlyMessage.invoke(chat, eof);
        assertNotNull(msg);
        // 新文案用自然语言"tool_use JSON 未完整到达" + "Spring AI 1.1.8" 标识根因
        assertTrue(msg.contains("tool_use") && msg.contains("Spring AI"),
                "应指明 tool_use / Spring AI 关联,实际:" + msg);
        assertTrue(msg.contains("重发该对话"),
                "应给出可操作建议(重发对话),实际:" + msg);
        // 验证不再是误导性的 RuntimeException 兜底
        assertFalse(msg.contains("聊天服务异常"),
                "不应再退回到泛化 RuntimeException 兜底文案");
    }

    @Test
    @DisplayName("RuntimeException 包 JsonEOFException（生产实际链路） → 走 cause 链命中")
    void wrappedJsonEOFException() throws Exception {
        JsonEOFException eof = new JsonEOFException(mock(JsonParser.class), null, "Unexpected end-of-input: was expecting closing quote for a string value");
        RuntimeException wrapped = new RuntimeException(
                "com.fasterxml.jackson.core.io.JsonEOFException: "
                        + "Unexpected end-of-input: was expecting closing quote for a string value\n"
                        + "at [Source: REDACTED; line: 1, column: 33252]", eof);
        String msg = (String) friendlyMessage.invoke(chat, wrapped);
        assertNotNull(msg);
        // cause 链上包含 "Unexpected end-of-input" 字符串的 RuntimeException 应被识别
        assertTrue(msg.contains("tool_use") || msg.contains("Spring AI"),
                "cause 链上的 JsonEOFException 应被识别,实际:" + msg);
        assertFalse(msg.contains("聊天服务异常"),
                "包装过的 JsonEOFException 不应再退回到 RuntimeException 兜底文案");
    }

    @Test
    @DisplayName("非 JsonEOFException 异常不应触碰到这套文案（回归 non-bug 路径）")
    void nonBugPath() throws Exception {
        IllegalArgumentException iae = new IllegalArgumentException("normal error");
        String msg = (String) friendlyMessage.invoke(chat, iae);
        assertNotNull(msg);
        assertTrue(msg.startsWith("聊天服务异常"),
                "非 JsonEOFException 应走通用兜底文案,实际:" + msg);
    }
}