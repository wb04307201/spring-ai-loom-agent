package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.SkillRecord;
import cn.wubo.spring.ai.loom.agent.model.UserSkill;
import cn.wubo.spring.ai.loom.agent.user.IUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * DefaultSkillStorage 单元测试 —— 角色授权同步、locked 语义、admin union 视图。
 */
@DisplayName("DefaultSkillStorage 单元测试")
class DefaultSkillStorageTest {

    private JdbcTemplate jt;
    private IUser user;
    private DefaultSkillStorage storage;

    /** 当前场景的 user_skill 行（name -> 列值） */
    private Map<String, Map<String, Object>> userSkillRows = new HashMap<>();
    private List<String> roles = List.of();
    private List<Map<String, Object>> roleSkillRows = List.of();
    private List<Map<String, Object>> marketRows = List.of();
    private final List<String> updateLog = new java.util.ArrayList<>();
    private final List<Object[]> updateArgs = new java.util.ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jt = mock(JdbcTemplate.class);
        user = mock(IUser.class);
        ResourceLoader loader = mock(ResourceLoader.class);

        // user_role 查询
        lenient().when(jt.queryForList(anyString(), any(Class.class), any()))
                .thenAnswer(inv -> roles);
        // role_skill JOIN market_skill
        lenient().when(jt.queryForList(contains("FROM role_skill"), any(Object[].class)))
                .thenAnswer(inv -> roleSkillRows);
        // market_skill union 视图
        lenient().when(jt.queryForList(contains("FROM market_skill"), any(Object[].class)))
                .thenAnswer(inv -> marketRows);
        // findUserSkill：username = ? AND name = ?（varargs 整体匹配，参数从数组取）
        lenient().when(jt.query(contains("AND name = ?"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    RowMapper<UserSkill> m = inv.getArgument(1);
                    // varargs 在 invocation 中展开：0=sql, 1=mapper, 2=username, 3=name
                    String name = inv.getArgument(3);
                    Map<String, Object> row = userSkillRows.get(name);
                    return row == null ? List.of() : List.of(m.mapRow(rs(row), 0));
                });
        // queryUserSkills：ORDER BY source
        lenient().when(jt.query(contains("ORDER BY source"), any(RowMapper.class), any()))
                .thenAnswer(inv -> {
                    RowMapper<UserSkill> m = inv.getArgument(1);
                    var out = new java.util.ArrayList<UserSkill>();
                    int i = 0;
                    for (Map<String, Object> row : userSkillRows.values()) out.add(m.mapRow(rs(row), i++));
                    return out;
                });
        lenient().when(jt.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            updateLog.add(inv.getArgument(0));
            Object[] a = inv.getArguments();
            updateArgs.add(java.util.Arrays.copyOfRange(a, 1, a.length));
            return 1;
        });

        storage = new DefaultSkillStorage(jt, loader, mock(ISkillRoleAdmin.class), user);
    }

    private static Map<String, Object> row(String name, boolean locked, Long marketSkillId) {
        Map<String, Object> r = new HashMap<>();
        r.put("id", 1L);
        r.put("username", "alice");
        r.put("name", name);
        r.put("description", "desc-" + name);
        r.put("content", "content-" + name);
        r.put("source", marketSkillId != null ? "ROLE_GRANTED" : "USER_CREATED");
        r.put("market_skill_id", marketSkillId);
        r.put("market_version", "1.0");
        r.put("default_loaded", true);
        r.put("locked", locked);
        r.put("created_at", Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0)));
        r.put("updated_at", Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0)));
        return r;
    }

    private static ResultSet rs(Map<String, Object> r) {
        ResultSet m = mock(ResultSet.class);
        try {
            lenient().when(m.getLong("id")).thenReturn((Long) r.get("id"));
            lenient().when(m.getString("username")).thenReturn((String) r.get("username"));
            lenient().when(m.getString("name")).thenReturn((String) r.get("name"));
            lenient().when(m.getString("description")).thenReturn((String) r.get("description"));
            lenient().when(m.getString("content")).thenReturn((String) r.get("content"));
            lenient().when(m.getString("source")).thenReturn((String) r.get("source"));
            lenient().when(m.getObject("market_skill_id")).thenReturn(r.get("market_skill_id"));
            lenient().when(m.getString("market_version")).thenReturn((String) r.get("market_version"));
            lenient().when(m.getBoolean("default_loaded")).thenReturn((Boolean) r.get("default_loaded"));
            lenient().when(m.getBoolean("locked")).thenReturn((Boolean) r.get("locked"));
            lenient().when(m.getTimestamp("created_at")).thenReturn((Timestamp) r.get("created_at"));
            lenient().when(m.getTimestamp("updated_at")).thenReturn((Timestamp) r.get("updated_at"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return m;
    }

    @Test
    @DisplayName("sync：角色授权 skill 不存在时插入 ROLE_GRANTED 且 locked=TRUE")
    void syncInsertsRoleGrantedLocked() {
        roles = List.of("dev");
        Map<String, Object> rs = new HashMap<>();
        rs.put("mid", 7L); rs.put("def", true); rs.put("id", 7L);
        rs.put("name", "granted-skill"); rs.put("description", "d"); rs.put("content", "plain"); rs.put("version", "1.0");
        roleSkillRows = List.of(rs);

        storage.sync("alice");

        String insert = updateLog.stream().filter(s -> s.startsWith("INSERT INTO user_skill")).findFirst().orElse(null);
        assertNotNull(insert, "应插入 user_skill");
        assertTrue(insert.contains("'ROLE_GRANTED'"));
        assertTrue(insert.contains("TRUE"));
        // 展开后 0=username, 1=name, 2=desc, 3=content, 4=load
        assertEquals(1, updateArgs.size());
        assertEquals("granted-skill", updateArgs.get(0)[1]);
    }

    @Test
    @DisplayName("sync：同一 ROLE_GRANTED 实例只同步 default_loaded，不重复插入")
    void syncSameInstanceUpdatesDefaultLoadedOnly() {
        roles = List.of("dev");
        userSkillRows = Map.of("granted-skill", row("granted-skill", true, 7L));
        Map<String, Object> rs = new HashMap<>();
        rs.put("mid", 7L); rs.put("def", false); rs.put("id", 7L);
        rs.put("name", "granted-skill"); rs.put("description", "d"); rs.put("content", "plain"); rs.put("version", "1.0");
        roleSkillRows = List.of(rs);

        storage.sync("alice");

        assertTrue(updateLog.stream().anyMatch(s -> s.contains("UPDATE user_skill SET default_loaded")));
        assertTrue(updateLog.stream().noneMatch(s -> s.startsWith("INSERT INTO user_skill")));
    }

    @Test
    @DisplayName("admin union：list() 合并市场 APPROVED 且与 user_skill 去重")
    void adminUnionViewMergesAndDedupes() {
        when(user.isAdmin("alice")).thenReturn(true);
        userSkillRows = Map.of("skillA", row("skillA", false, null));
        Map<String, Object> mA = new HashMap<>();
        mA.put("name", "skillA"); mA.put("description", "ma"); mA.put("content", "plain"); mA.put("version", "1"); mA.put("status", "APPROVED");
        Map<String, Object> mB = new HashMap<>();
        mB.put("name", "skillB"); mB.put("description", "mb"); mB.put("content", "plain"); mB.put("version", "1"); mB.put("status", "APPROVED");
        marketRows = List.of(mA, mB);

        List<SkillRecord> out = storage.list("alice");

        assertEquals(2, out.size(), "skillA 去重后应只剩 2 条");
        assertTrue(out.stream().anyMatch(s -> s.name().equals("skillB") && "MARKET_VIEW".equals(s.source())));
        assertEquals(1, out.stream().filter(s -> s.name().equals("skillA")).count());
    }

    @Test
    @DisplayName("非 admin：listForAdminUnionView 为空且不查市场表")
    void nonAdminGetsEmptyUnionView() {
        when(user.isAdmin("alice")).thenReturn(false);

        assertEquals(List.of(), storage.listForAdminUnionView("alice"));
        verify(jt, never()).queryForList(contains("FROM market_skill"), any(Object[].class));
    }

    @Test
    @DisplayName("locked skill：save/remove/patch 均拒绝")
    void lockedSkillRejectsMutation() {
        userSkillRows = Map.of("granted-skill", row("granted-skill", true, 7L));

        assertThrows(LoomAgentRuntimeException.class,
                () -> storage.save(new SkillRecord("granted-skill", "d", true, "c", "USER_CREATED"), "alice"));
        assertThrows(LoomAgentRuntimeException.class, () -> storage.remove("granted-skill", "alice"));
        assertThrows(LoomAgentRuntimeException.class,
                () -> storage.patch("granted-skill", "alice", new cn.wubo.spring.ai.loom.agent.model.UserSkillPatchRequest("d2", true)));
    }

    @Test
    @DisplayName("get：不存在抛异常；admin 可回落到市场视图")
    void getFallbackForAdmin() {
        when(user.isAdmin("alice")).thenReturn(true);
        assertThrows(LoomAgentRuntimeException.class, () -> storage.get("nope", "alice"));

        Map<String, Object> mB = new HashMap<>();
        mB.put("name", "market-one"); mB.put("description", "mb"); mB.put("content", "plain"); mB.put("version", "1"); mB.put("status", "APPROVED");
        marketRows = List.of(mB);

        assertEquals("market-one", storage.get("market-one", "alice").name());
    }
}
