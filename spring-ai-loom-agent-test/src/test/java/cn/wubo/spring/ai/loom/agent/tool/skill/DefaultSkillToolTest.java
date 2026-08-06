package cn.wubo.spring.ai.loom.agent.tool.skill;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.SkillRecord;
import cn.wubo.spring.ai.loom.agent.skill.ISkillStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

    // ============ createOrUpdateSkill ============

    @Test
    @DisplayName("createOrUpdateSkill 新建：name 不存在时返回「已创建」并调 storage.save")
    void createOrUpdateSkill_new() {
        when(storage.get("new-skill", "alice")).thenThrow(new LoomAgentRuntimeException("Skill 不存在或无权限: new-skill"));

        String result = tool.createOrUpdateSkill("new-skill", "测试描述", "Prompt 内容", ctx("alice"));

        assertTrue(result.contains("已创建技能 new-skill"), "应返回「已创建」: " + result);
        assertTrue(result.contains("测试描述"), "应回显描述: " + result);
        ArgumentCaptor<SkillRecord> captor = ArgumentCaptor.forClass(SkillRecord.class);
        verify(storage).save(captor.capture(), eq("alice"));
        SkillRecord saved = captor.getValue();
        assertEquals("new-skill", saved.name());
        assertEquals("测试描述", saved.description());
        assertEquals("Prompt 内容", saved.content());
        assertTrue(saved.load(), "load 应为 true");
        assertEquals("USER_CREATED", saved.source());
    }

    @Test
    @DisplayName("createOrUpdateSkill 更新：name 已存在时返回「已更新」")
    void createOrUpdateSkill_update() {
        SkillRecord existing = new SkillRecord("known", "旧描述", true, "旧内容", "USER_CREATED");
        when(storage.get("known", "alice")).thenReturn(existing);

        String result = tool.createOrUpdateSkill("known", "新描述", "新内容", ctx("alice"));

        assertTrue(result.contains("已更新技能 known"), "应返回「已更新」: " + result);
        verify(storage).save(any(SkillRecord.class), eq("alice"));
    }

    @Test
    @DisplayName("createOrUpdateSkill name 去前后空白后存储")
    void createOrUpdateSkill_trimsName() {
        when(storage.get("trim-skill", "alice")).thenThrow(new LoomAgentRuntimeException("Skill 不存在或无权限: trim-skill"));

        tool.createOrUpdateSkill("  trim-skill  ", "d", "c", ctx("alice"));

        ArgumentCaptor<SkillRecord> captor = ArgumentCaptor.forClass(SkillRecord.class);
        verify(storage).save(captor.capture(), eq("alice"));
        assertEquals("trim-skill", captor.getValue().name(), "name 应去前后空白");
    }

    @Test
    @DisplayName("createOrUpdateSkill 缺 name → 400")
    void createOrUpdateSkill_blankName() {
        LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
                () -> tool.createOrUpdateSkill("", "d", "c", ctx("alice")));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("name 不能为空"), "msg: " + ex.getMessage());
        verify(storage, never()).save(any(), anyString());
    }

    @Test
    @DisplayName("createOrUpdateSkill 缺 content → 400")
    void createOrUpdateSkill_blankContent() {
        when(storage.get("x", "alice")).thenThrow(new LoomAgentRuntimeException("Skill 不存在或无权限: x"));
        LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
                () -> tool.createOrUpdateSkill("x", "d", "  ", ctx("alice")));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("content 不能为空"), "msg: " + ex.getMessage());
        verify(storage, never()).save(any(), anyString());
    }

    @Test
    @DisplayName("createOrUpdateSkill name 超 128 字符 → 400")
    void createOrUpdateSkill_nameTooLong() {
        String longName = "a".repeat(129);
        LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
                () -> tool.createOrUpdateSkill(longName, "d", "c", ctx("alice")));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("name 长度超过 128"), "msg: " + ex.getMessage());
        verify(storage, never()).save(any(), anyString());
    }

    @Test
    @DisplayName("createOrUpdateSkill 锁定的技能 → storage.save 抛 403 时透传")
    void createOrUpdateSkill_locked() {
        when(storage.get("locked-skill", "alice"))
                .thenReturn(new SkillRecord("locked-skill", "d", true, "c", "MARKET_PULLED"));
        doThrow(new LoomAgentRuntimeException(403, "该 Skill 已被锁定（ROLE_GRANTED 或 MARKET_PULLED），不能修改"))
                .when(storage).save(any(SkillRecord.class), eq("alice"));

        LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
                () -> tool.createOrUpdateSkill("locked-skill", "d", "c", ctx("alice")));
        assertEquals(403, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("锁定"), "msg: " + ex.getMessage());
    }

    @Test
    @DisplayName("createOrUpdateSkill description=null 自动视为空串")
    void createOrUpdateSkill_nullDescription() {
        when(storage.get("d-null", "alice")).thenThrow(new LoomAgentRuntimeException("Skill 不存在或无权限: d-null"));

        tool.createOrUpdateSkill("d-null", null, "content", ctx("alice"));

        ArgumentCaptor<SkillRecord> captor = ArgumentCaptor.forClass(SkillRecord.class);
        verify(storage).save(captor.capture(), eq("alice"));
        assertEquals("", captor.getValue().description(), "null description 应存为 \"\"");
    }
}
