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
 * 1. listSkills 分页列出 load=true 的技能，默认每页20条
 * 2. listSkills size=-1 返回全部
 * 3. listSkills 空技能列表
 * 4. listSkills 翻页提示下一页
 * 5. getSkill 按名称获取完整信息
 * 6. username 通过 ToolContext 正确传递到 ISkillStorage
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
    @DisplayName("listSkills 仅列出 load=true 的技能")
    void listSkills_listsLoadedSkills() {
        when(storage.list("alice")).thenReturn(List.of(
                new SkillRecord("news-watch", "月度报告生成", true, "content1", "classpath:skills/news-watch.st"),
                new SkillRecord("daily-standup", "每日站会", false, "content2", "classpath:skills/standup.st")
        ));

        String result = tool.listSkills(1, 20, ctx("alice"));
        assertTrue(result.contains("news-watch"));
        assertTrue(result.contains("月度报告生成"));
        assertTrue(result.contains("共 1 个"), "应只列出已加载的 1 个技能: " + result);
        assertFalse(result.contains("daily-standup"), "未加载技能不应出现: " + result);
    }

    @Test
    @DisplayName("listSkills 无技能时显示空目录")
    void listSkills_emptyList() {
        when(storage.list("bob")).thenReturn(List.of());
        String result = tool.listSkills(1, 20, ctx("bob"));
        assertTrue(result.contains("共 0 个"), "应提示 0 个技能: " + result);
    }

    @Test
    @DisplayName("listSkills 把 username 透传给 storage")
    void listSkills_passesUsername() {
        when(storage.list("charlie")).thenReturn(List.of());
        tool.listSkills(1, 20, ctx("charlie"));
        verify(storage).list("charlie");
    }

    @Test
    @DisplayName("listSkills 默认 null 参数使用默认值")
    void listSkills_defaultParams() {
        when(storage.list("dave")).thenReturn(List.of());
        String result = tool.listSkills(null, null, ctx("dave"));
        assertTrue(result.contains("共 0 个"));
        assertTrue(result.contains("第 1/0 页"));
    }

    @Test
    @DisplayName("listSkills size=-1 返回全部")
    void listSkills_allSkills() {
        List<SkillRecord> skills = List.of(
                new SkillRecord("skill-1", "描述1", true, "c1", "x"),
                new SkillRecord("skill-2", "描述2", true, "c2", "x"),
                new SkillRecord("skill-3", "描述3", true, "c3", "x")
        );
        when(storage.list("eve")).thenReturn(skills);

        String result = tool.listSkills(1, -1, ctx("eve"));
        assertTrue(result.contains("skill-1"));
        assertTrue(result.contains("skill-2"));
        assertTrue(result.contains("skill-3"));
        assertTrue(result.contains("共 3 个"));
        assertFalse(result.contains("下一页"), "全部模式不应有下一页提示: " + result);
    }

    @Test
    @DisplayName("listSkills 翻页有下一页提示")
    void listSkills_nextPageHint() {
        // 创建 30 个技能，每页 20 个
        List<SkillRecord> skills = List.of(
                new SkillRecord("skill-1", "描述1", true, "c1", "x"),
                new SkillRecord("skill-2", "描述2", true, "c2", "x"),
                new SkillRecord("skill-3", "描述3", true, "c3", "x")
        );
        when(storage.list("frank")).thenReturn(skills);

        String result = tool.listSkills(1, 2, ctx("frank"));
        assertTrue(result.contains("下一页"), "应有下一页提示: " + result);
        assertTrue(result.contains("page\": 2"), "下一页应提示 page 2: " + result);
        assertFalse(result.contains("skill-3"), "第1页不应包含第3个技能: " + result);
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
