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
 * 1. listSkills 列出 load=true 的技能
 * 2. listSkills 空技能列表
 * 3. listSkills 按 keyword 模糊匹配
 * 4. listSkills 按 source 过滤
 * 5. listSkills maxCount 截断 + 提示
 * 6. username 通过 ToolContext 正确传递到 ISkillStorage
 */
@DisplayName("DefaultSkillTool 单元测试")
class DefaultSkillToolTest {

    private ISkillStorage storage;
    private DefaultSkillTool tool;

    private static ToolContext ctx(String username) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("username", username);
        return new ToolContext(ctx);
    }

    @BeforeEach
    void setUp() {
        storage = mock(ISkillStorage.class);
        tool = new DefaultSkillTool(storage);
    }

    @Test
    @DisplayName("listSkills 仅列出 load=true 的技能")
    void listSkills_listsLoadedSkills() {
        when(storage.list("alice")).thenReturn(List.of(
                new SkillRecord("news-watch", "月度报告生成", true, "content1", "classpath:skills/news-watch.st"),
                new SkillRecord("daily-standup", "每日站会", false, "content2", "classpath:skills/standup.st")
        ));

        String result = tool.listSkills(null, null, null, ctx("alice"));
        assertTrue(result.contains("news-watch"));
        assertTrue(result.contains("月度报告生成"));
        assertTrue(result.contains("命中 1 / 共 1 个"), "应只列出已加载的 1 个技能: " + result);
        assertFalse(result.contains("daily-standup"), "未加载技能不应出现: " + result);
    }

    @Test
    @DisplayName("listSkills 无技能时显示空目录")
    void listSkills_emptyList() {
        when(storage.list("bob")).thenReturn(List.of());
        String result = tool.listSkills(null, null, null, ctx("bob"));
        assertTrue(result.contains("命中 0 / 共 0 个"), "应提示 0 个技能: " + result);
    }

    @Test
    @DisplayName("listSkills 把 username 透传给 storage")
    void listSkills_passesUsername() {
        when(storage.list("charlie")).thenReturn(List.of());
        tool.listSkills(null, null, null, ctx("charlie"));
        verify(storage).list("charlie");
    }

    @Test
    @DisplayName("listSkills keyword 模糊匹配（不区分大小写，匹配 name 或 description）")
    void listSkills_keywordFilter() {
        when(storage.list("dave")).thenReturn(List.of(
                new SkillRecord("deploy-app", "部署应用", true, "c1", "USER_CREATED"),
                new SkillRecord("write-doc", "编写文档", true, "c2", "USER_CREATED"),
                new SkillRecord("network", "部署网络", true, "c3", "MARKET_VIEW")
        ));

        String result = tool.listSkills("部署", null, null, ctx("dave"));
        assertTrue(result.contains("deploy-app"), "name 含 '部署' 的应匹配: " + result);
        assertTrue(result.contains("network"), "description 含 '部署' 的应匹配: " + result);
        assertFalse(result.contains("write-doc"), "name/desc 都不含 '部署' 的不应匹配: " + result);
        assertTrue(result.contains("命中 2 / 共 3 个"));
    }

    @Test
    @DisplayName("listSkills source 精确过滤（移除 MARKET_VIEW，仅支持 USER_CREATED / ROLE_GRANTED / MARKET_PULLED）")
    void listSkills_sourceFilter() {
        when(storage.list("eve")).thenReturn(List.of(
                new SkillRecord("user-skill", "用户自建", true, "c1", "USER_CREATED"),
                new SkillRecord("mkt-skill", "市场技能", true, "c2", "MARKET_PULLED"),
                new SkillRecord("role-skill", "角色授权", true, "c3", "ROLE_GRANTED")
        ));

        String result = tool.listSkills(null, "USER_CREATED", null, ctx("eve"));
        assertTrue(result.contains("user-skill"));
        assertFalse(result.contains("mkt-skill"), "MARKET_PULLED 应被过滤掉: " + result);
        assertFalse(result.contains("role-skill"), "ROLE_GRANTED 应被过滤掉: " + result);
        assertTrue(result.contains("命中 1 / 共 3 个"));
    }

    @Test
    @DisplayName("listSkills maxCount 截断并提示缩小范围")
    void listSkills_maxCountTruncates() {
        List<SkillRecord> skills = List.of(
                new SkillRecord("skill-1", "描述1", true, "c1", "USER_CREATED"),
                new SkillRecord("skill-2", "描述2", true, "c2", "USER_CREATED"),
                new SkillRecord("skill-3", "描述3", true, "c3", "USER_CREATED")
        );
        when(storage.list("frank")).thenReturn(skills);

        String result = tool.listSkills(null, null, 2, ctx("frank"));
        assertTrue(result.contains("skill-1"));
        assertTrue(result.contains("skill-2"));
        assertFalse(result.contains("skill-3"), "maxCount=2 时第 3 个应被截断: " + result);
        assertTrue(result.contains("上限 2"));
        assertTrue(result.contains("已截断"), "应有截断提示: " + result);
        assertTrue(result.contains("建议用 keyword 缩小范围"), "应建议缩小范围: " + result);
    }

    @Test
    @DisplayName("listSkills maxCount > total 时不截断也不提示")
    void listSkills_maxCountAboveTotal() {
        when(storage.list("grace")).thenReturn(List.of(
                new SkillRecord("a", "x", true, "c", "USER_CREATED")
        ));

        String result = tool.listSkills(null, null, 100, ctx("grace"));
        assertFalse(result.contains("已截断"), "总数 < maxCount 时不应有截断提示: " + result);
    }

    @Test
    @DisplayName("listSkills 无参数时全部返回（默认 maxCount=200）")
    void listSkills_defaultMaxCount() {
        when(storage.list("henry")).thenReturn(List.of(
                new SkillRecord("a", "x", true, "c", "USER_CREATED"),
                new SkillRecord("b", "y", true, "c", "USER_CREATED")
        ));
        String result = tool.listSkills(null, null, null, ctx("henry"));
        assertTrue(result.contains("命中 2 / 共 2 个"));
        assertTrue(result.contains("上限 200"), "默认上限应为 200: " + result);
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

        tool.createOrUpdateSkill(" trim-skill ", "d", "c", ctx("alice"));

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
                () -> tool.createOrUpdateSkill("x", "d", " ", ctx("alice")));
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
