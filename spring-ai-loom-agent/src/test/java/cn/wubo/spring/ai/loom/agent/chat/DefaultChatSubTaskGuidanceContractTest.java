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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 【任务分段执行】段契约回归测试（spec § 5.1 / § 5.3）
 * <p>
 * 8 个断言锁住 system prompt 新段的关键词、触发协议、few-shot 模板、护栏。
 * 另一个 Tool description 断言（A8）在 DefaultSubTaskToolTest 同文件里（Task 2）。
 */
@DisplayName("DefaultChat.buildDynamicSystemPrompt【任务分段执行】契约测试")
class DefaultChatSubTaskGuidanceContractTest {

    private DefaultChat chat;
    private Method buildDynamicSystemPrompt;
    private String prompt;

    @BeforeEach
    void setUp() throws Exception {
        LoomAgentProperties properties = new LoomAgentProperties();
        properties.setDefaultSystem("test persona");
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
        buildDynamicSystemPrompt = DefaultChat.class.getDeclaredMethod(
                "buildDynamicSystemPrompt", String.class, List.class);
        buildDynamicSystemPrompt.setAccessible(true);
        prompt = (String) buildDynamicSystemPrompt.invoke(chat, "test-user", List.of());
    }

    @Test
    @DisplayName("A1: 段标题【任务分段执行】存在")
    void title() {
        assertTrue(prompt.contains("【任务分段执行】"),
                "应包含段标题【任务分段执行】,实际:" + prompt);
    }

    @Test
    @DisplayName("A2: 场景 1 — 大型代码关键词")
    void scenario1LargeCode() {
        assertTrue(prompt.contains("大型代码"),
                "场景 1 关键词缺失,实际:" + prompt);
    }

    @Test
    @DisplayName("A3: 场景 2 — 多工具链关键词")
    void scenario2ToolChain() {
        assertTrue(prompt.contains("多工具链"),
                "场景 2 关键词缺失,实际:" + prompt);
    }

    @Test
    @DisplayName("A4: 场景 3 — 长 reasoning 风险关键词")
    void scenario3LongReasoning() {
        assertTrue(prompt.contains("长 reasoning 风险"),
                "场景 3 关键词缺失,实际:" + prompt);
    }

    @Test
    @DisplayName("A5: 场景 4 — 大文件写入/编辑关键词")
    void scenario4LargeFile() {
        assertTrue(prompt.contains("大文件写入"),
                "场景 4 关键词缺失,实际:" + prompt);
    }

    @Test
    @DisplayName("A6: few-shot 模板含 start_sub_task + renderHtmlFile 调用")
    void fewShotCoversAppAndEngine() {
        assertTrue(prompt.contains("start_sub_task"),
                "few-shot 缺 start_sub_task 调用,实际:" + prompt);
        assertTrue(prompt.contains("renderHtmlFile"),
                "few-shot 缺 renderHtmlFile 调用,实际:" + prompt);
    }

    @Test
    @DisplayName("A7: 触发协议含 ≥3 个独立子目标")
    void triggerProtocolThreshold() {
        assertTrue(prompt.contains("≥3 个独立子目标")
                        || prompt.contains("≥ 3 个独立子目标"),
                "触发协议缺 ≥3 个独立子目标阈值,实际:" + prompt);
    }

    @Test
    @DisplayName("A9: 护栏 ≤5（与 askUser 5-ask 护栏对称）")
    void guardrailMaxFive() {
        assertTrue(prompt.contains("≤ 5") || prompt.contains("≤5"),
                "护栏缺 ≤5,实际:" + prompt);
    }

    @Test
    @DisplayName("A8: Tool description 含\"流协议截断\"关键短语（spec § 5.2 D1 改动到位）")
    void toolDescriptionCoversStreamTruncation() throws Exception {
        // 反射读 DefaultSubTaskTool.startSubTask 上的 @Tool(description=...)
        // 注意:Java 方法名是 camelCase 的 startSubTask;LLM-facing 的工具名是 Spring AI
        // 从方法名派生的 startSubTask / start_sub_task（取决于版本）。反射必须用
        // 实际 Java 方法名。
        Class<?> subTaskToolClass = Class.forName("cn.wubo.spring.ai.loom.agent.subtask.DefaultSubTaskTool");
        // 注意:ToolContext 在 spring-ai 1.x 里位于 org.springframework.ai.chat.model 包（不是 .tool.context）
        Class<?> toolContextClass = Class.forName("org.springframework.ai.chat.model.ToolContext");
        Method startSubTask = subTaskToolClass.getDeclaredMethod("startSubTask",
                String.class, String.class, toolContextClass);
        org.springframework.ai.tool.annotation.Tool toolAnnotation =
                startSubTask.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
        assertNotNull(toolAnnotation, "DefaultSubTaskTool.startSubTask 必须有 @Tool 注解");
        String desc = toolAnnotation.description();
        assertTrue(desc.contains("流协议截断"),
                "Tool description 应明确提及流协议截断根因,实际:" + desc);
    }
}