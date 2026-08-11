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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DefaultSkillTool 单元测试
 * <p>
 * 已移除 listSkills 相关用例 —— listSkills 工具已删除（设计变更：技能全量列表由
 * buildDynamicSystemPrompt 注入到 system prompt【技能】段，LLM 无需工具列）。
 * <p>
 * 当前覆盖：
 * 1. getSkill 返回完整信息
 * 2. getSkill 把 username 透传给 storage
 * 3. createOrUpdateSkill 新建：name 不存在
 * 4. createOrUpdateSkill 更新：name 已存在
 * 5. createOrUpdateSkill name 去前后空白
 * 6. createOrUpdateSkill 校验：缺 name / 缺 content / 超长 name → 400
 * 7. createOrUpdateSkill 锁定（ROLE_GRANTED / MARKET_PULLED） → 403 透传
 * 8. createOrUpdateSkill description=null 自动视为空串
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
