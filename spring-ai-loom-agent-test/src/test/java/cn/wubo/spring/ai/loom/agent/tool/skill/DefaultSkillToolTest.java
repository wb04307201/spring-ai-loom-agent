package cn.wubo.spring.ai.loom.agent.tool.skill;

import cn.wubo.spring.ai.loom.agent.model.SkillRecord;
import cn.wubo.spring.ai.loom.agent.skill.ISkillStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DefaultSkillTool 单元测试
 * <p>
 * 覆盖：
 * 1. skillContents 列出所有 load=true 的技能
 * 2. skillContents 过滤掉 load=false 的技能
 * 3. skillContents 空技能返回空目录
 * 4. getSkill 按名称获取并展示完整信息
 * 5. username 通过 ToolContext 正确传递到 ISkillStorage
 */
@DisplayName("DefaultSkillTool 单元测试")
class DefaultSkillToolTest {

    private ISkillStorage storage;
    private DefaultSkillTool tool;

    @BeforeEach
    void setUp() {
        storage = mock(ISkillStorage.class);
        tool = new DefaultSkillTool(storage);
    }

    private static ToolContext ctx(String username) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("username", username);
        return new ToolContext(ctx);
    }

    @Test
    @DisplayName("skillContents 仅列出 load=true 的技能")
    void skillContents_listsLoadedSkills() {
        when(storage.list("alice")).thenReturn(List.of(
                new SkillRecord("news-watch", "月度报告生成", true, "content1", "classpath:skills/news-watch.st"),
                new SkillRecord("daily-standup", "每日站会", false, "content2", "classpath:skills/standup.st")
        ));

        String result = tool.skillContents(ctx("alice"));
        assertTrue(result.contains("news-watch"));
        assertTrue(result.contains("月度报告生成"));
        assertTrue(result.contains("包含 1 个技能"), "应只列出已加载的 1 个技能: " + result);
        assertFalse(result.contains("daily-standup"), "未加载技能不应出现: " + result);
    }

    @Test
    @DisplayName("skillContents 无技能时显示空目录")
    void skillContents_emptyList() {
        when(storage.list("bob")).thenReturn(List.of());
        String result = tool.skillContents(ctx("bob"));
        assertTrue(result.contains("包含 0 个技能"), "应提示 0 个技能: " + result);
    }

    @Test
    @DisplayName("skillContents 把 username 透传给 storage")
    void skillContents_passesUsername() {
        when(storage.list("charlie")).thenReturn(List.of());
        tool.skillContents(ctx("charlie"));
        verify(storage).list("charlie");
    }

    @Test
    @DisplayName("getSkill 返回技能完整信息（名称/描述/内容）")
    void getSkill_returnsFullInfo() {
        SkillRecord rec = new SkillRecord("news-watch", "月度报告", true,
                "通过网络搜索...", "classpath:skills/news-watch.st");
        when(storage.get("news-watch", "alice")).thenReturn(rec);

        String result = tool.getSkill("news-watch", ctx("alice"));
        assertTrue(result.contains("技能名:news-watch"), "应包含技能名: " + result);
        assertTrue(result.contains("月度报告"), "应包含描述: " + result);
        assertTrue(result.contains("通过网络搜索"), "应包含内容: " + result);
    }

    @Test
    @DisplayName("getSkill 把 username 透传给 storage")
    void getSkill_passesUsername() {
        SkillRecord rec = new SkillRecord("x", "x", true, "x", "x");
        when(storage.get("x", "dora")).thenReturn(rec);
        tool.getSkill("x", ctx("dora"));
        verify(storage).get("x", "dora");
    }
}
