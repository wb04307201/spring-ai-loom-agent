package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.capability.CapabilityService;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.skill.ISkillStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 15(spec 2026-11-21 §5.2 / Task 7):steering 引导句契约锁。
 *
 * <p>背景:运行中用户补充的消息经 {@code LoomAgenticLoopAdvisor} 在下一迭代边界注入为
 * USER 行(A4),模型必须<b>优先回应插话再继续原任务</b> —— 这是 steering 语义在
 * system prompt 侧的落点(Task 7 在【平台能力】段追加"运行中补充"行)。若后续改动
 * 无意间删除/改写该句,模型会对中段插话视而不见(功能静默回退,后端注入链本身无异常),
 * 故措辞逐字锁定 —— 与 {@code AskUserGuidanceContractTest} 同一范式。
 *
 * <p>三层锁定:
 * <ol>
 *   <li>整行逐字(含全角冒号 + 半角逗号 + 行尾 \n):防任何标点/用词漂移;</li>
 *   <li>两条语义关键词:最高优先级 + 先回应再继续;</li>
 *   <li>结构位置:必须位于【平台能力】段内、"交互式提问"行之后、【提问与澄清】段之前,
 *       且与"未授权能力绝不承诺"的 RBAC 动态段无关(恒在,不受角色影响)。</li>
 * </ol>
 *
 * <p>变异自证(Task 15 Step 3 附加探针):删除 DefaultChat 该 append ⇒ 三个用例全红。
 */
@DisplayName("Task 15:steering 引导句契约锁(Task 7【平台能力】运行中补充行)")
class DefaultChatSteeringGuidanceContractTest {

    /** Task 7 落地的整行原文(逐字锁定,标点敏感)。 */
    private static final String STEERING_LINE =
            "• 运行中补充：运行中用户补充的消息具有最高优先级,先回应补充再继续原任务\n";

    private static String buildSystemPrompt(LoomAgentProperties properties, Set<String> grantedToolGroups) {
        CapabilityService capabilityService = mock(CapabilityService.class);
        when(capabilityService.visibleToolGroupsFor(anyString())).thenReturn(grantedToolGroups);
        ISkillStorage skillStorage = mock(ISkillStorage.class);
        when(skillStorage.list(anyString())).thenReturn(List.of());
        DefaultChat chat = new DefaultChat(
                null, null, List.of(), null, null,
                skillStorage, null,
                properties == null ? new LoomAgentProperties() : properties,
                null, capabilityService,
                null);
        return chat.buildDynamicSystemPrompt("tester", List.of());
    }

    @Test
    @DisplayName("整行逐字:运行中补充 = 最高优先级 + 先回应继续原任务")
    void system_prompt_contains_steering_priority_guidance() {
        String prompt = buildSystemPrompt(null, Set.of());
        assertThat(prompt).as("steering 引导句逐字锁定(Task 7,spec §5.2)").contains(STEERING_LINE);
        // 关键语义单独重复锁定(防整行被替换 yet 语义丢失的镜像断言)
        assertThat(prompt).contains("运行中用户补充的消息具有最高优先级");
        assertThat(prompt).contains("先回应补充再继续原任务");
    }

    @Test
    @DisplayName("结构位置:位于【平台能力】段内(交互式提问之后、【提问与澄清】之前)")
    void steeringGuidanceSitsInsidePlatformCapabilitySection() {
        String prompt = buildSystemPrompt(null, Set.of());
        int capabilityHeader = prompt.indexOf("【平台能力】");
        int askLine = prompt.indexOf("交互式提问");
        int steeringLine = prompt.indexOf("运行中补充：");
        int askSection = prompt.indexOf("【提问与澄清】");
        assertThat(capabilityHeader).as("平台能力段存在").isNotNegative();
        assertThat(askLine).as("平台能力段含交互式提问行(引导句在其后)").isNotNegative();
        assertThat(steeringLine).as("steering 引导句存在").isNotNegative();
        assertThat(steeringLine)
                .as("引导句在【平台能力】段内:平台能力头之后")
                .isGreaterThan(capabilityHeader);
        assertThat(steeringLine)
                .as("引导句紧随静态能力行(交互式提问之后)")
                .isGreaterThan(askLine);
        assertThat(steeringLine)
                .as("引导句在【提问与澄清】段之前(属于平台能力段,不是提问协议段)")
                .isLessThan(askSection);
    }

    @Test
    @DisplayName("恒在性:与 RBAC 授权集 / rag.enabled 无关(steering 是平台恒有语义)")
    void steeringGuidanceIndependentOfRbacAndRag() {
        // 角色全授权形态
        assertThat(buildSystemPrompt(null,
                Set.of("tool_git", "tool_maven", "tool_compile", "tool_render")))
                .contains(STEERING_LINE);
        // rag 关闭形态:知识空间行消失,但 steering 行不得联动消失
        LoomAgentProperties noRag = new LoomAgentProperties();
        noRag.getRag().setEnabled(false);
        String prompt = buildSystemPrompt(noRag, Set.of());
        assertThat(prompt).doesNotContain("知识空间");
        assertThat(prompt).contains(STEERING_LINE);
    }
}
