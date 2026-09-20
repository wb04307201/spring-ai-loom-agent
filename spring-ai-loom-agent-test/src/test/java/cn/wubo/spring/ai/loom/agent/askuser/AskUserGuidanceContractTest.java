package cn.wubo.spring.ai.loom.agent.askuser;

import cn.wubo.spring.ai.loom.agent.capability.CapabilityService;
import cn.wubo.spring.ai.loom.agent.chat.DefaultChat;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.skill.ISkillStorage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * askUser 触发率引导措辞契约(2026-09-20 优化)。
 * <p>
 * 背景: 旧版 @Tool 描述是抑制型措辞("仅在确实需要用户决策时使用;能自行合理决定的不要问"),
 * 且 system prompt 无任何提问引导 —— 实测模型几乎不主动提问,用户在消息里写
 * "一问一答澄清直到没问题"也只触发一次。研究结论: LLM 能感知歧义但很少行动
 * (基线澄清率 ~5%),需要显式协议 + 正向场景枚举 + few-shot 示例。
 * <p>
 * 本测试锁定三层引导的关键措辞,防止后续改动无意间回退:
 * <ol>
 *   <li>{@code DefaultAskUserTool} 的 @Tool 描述: 正向触发场景 + 澄清模式循环协议
 *       + 推荐项约定 + 可多次调用;旧抑制句不得复现</li>
 *   <li>{@code DefaultChat.buildDynamicSystemPrompt}: 【提问与澄清】段(触发时机 /
 *       澄清模式 / 5 问护栏 / few-shot 示例) + 【平台能力】"交互式提问"行</li>
 * </ol>
 */
class AskUserGuidanceContractTest {

    private static String askUserToolDescription() throws NoSuchMethodException {
        Tool tool = DefaultAskUserTool.class
                .getDeclaredMethod("askUser",
                        String.class, String.class, String.class, String.class,
                        Boolean.class, Boolean.class, ToolContext.class)
                .getAnnotation(Tool.class);
        assertThat(tool).as("askUser must carry @Tool annotation").isNotNull();
        return tool.description();
    }

    @Test
    void toolDescriptionHasPositiveTriggersAndClarifyLoop() throws NoSuchMethodException {
        String desc = askUserToolDescription();
        // 正向触发场景枚举(替代旧版纯抑制措辞)
        assertThat(desc).contains("优先提问而不是猜测");
        assertThat(desc).contains("请求含糊或缺少关键信息");
        // 澄清模式循环协议: 用户要求一问一答时必须连续调用直到无疑问
        assertThat(desc).contains("澄清模式");
        assertThat(desc).contains("每次只问一个问题");
        assertThat(desc).contains("直到没有疑问才开始执行");
        assertThat(desc).contains("可以多次调用");
        // Claude Code 式选项约定: 推荐项放第一位
        assertThat(desc).contains("(推荐)");
    }

    @Test
    void toolDescriptionDropsOldSuppressiveWording() throws NoSuchMethodException {
        String desc = askUserToolDescription();
        // 旧抑制句不得复现 —— 它是低触发率的直接根因之一
        assertThat(desc).doesNotContain("仅在确实需要用户决策时使用");
        assertThat(desc).doesNotContain("能自行合理决定的不要问");
    }

    private static String buildSystemPrompt() {
        CapabilityService capabilityService = mock(CapabilityService.class);
        when(capabilityService.visibleToolGroupsFor(anyString())).thenReturn(Set.of());
        ISkillStorage skillStorage = mock(ISkillStorage.class);
        when(skillStorage.list(anyString())).thenReturn(List.of());
        DefaultChat chat = new DefaultChat(
                null, null, List.of(), null, null,
                skillStorage, null,
                new LoomAgentProperties(), null, capabilityService);
        return chat.buildDynamicSystemPrompt("tester", List.of());
    }

    @Test
    void systemPromptHasAskAndClarifySection() {
        String prompt = buildSystemPrompt();
        assertThat(prompt).contains("【提问与澄清】");
        // 澄清模式协议(与 @Tool 描述同源措辞)
        assertThat(prompt).contains("澄清模式");
        assertThat(prompt).contains("持续调用 askUser 逐轮提问");
        // 防死循环护栏
        assertThat(prompt).contains("连续提问不超过 5 个");
        // few-shot 示例轨迹(LangChain: 示例是提升工具调用率最强杠杆)
        assertThat(prompt).contains("示例（澄清模式）");
        assertThat(prompt).contains("帮我写一个部署脚本");
        // 选项约定
        assertThat(prompt).contains("把你推荐的放第一位");
    }

    @Test
    void platformCapabilitiesListsInteractiveAskUser() {
        String prompt = buildSystemPrompt();
        // 【平台能力】段自述包含提问能力(此前唯独漏了 askUser)
        assertThat(prompt).contains("交互式提问");
    }
}
