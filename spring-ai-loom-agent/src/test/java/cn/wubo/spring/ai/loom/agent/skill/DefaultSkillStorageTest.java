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
 @DisplayName("admin list() 不再返回 MARKET_VIEW，只看自己 user_skill")
 void adminNoLongerGetsMarketView() {
 when(user.isAdmin("alice")).thenReturn(true);
 userSkillRows = Map.of("skillA", row("skillA", false, null, "USER_CREATED"));
 // market_skill 表里有数据也不应影响 list 返回
 Map<String, Object> mB = new HashMap<>();
 mB.put("name", "marketB"); mB.put("description", "mb"); mB.put("content", "plain"); mB.put("version", "1"); mB.put("status", "APPROVED");
 marketRows = List.of(mB);

 List<SkillRecord> out = storage.list("alice");

 assertEquals(1, out.size(), "list 只返回 user_skill 行");
 assertEquals("skillA", out.get(0).name());
 assertFalse(out.stream().anyMatch(s -> "MARKET_VIEW".equals(s.source())));
 }

 @Test
 @DisplayName("locked skill：save/remove/patch 均拒绝")
 void lockedSkillRejectsMutation() {
 userSkillRows = Map.of("granted-skill", row("granted-skill", true, 7L, "ROLE_GRANTED"));

 assertThrows(LoomAgentRuntimeException.class,
 () -> storage.save(new SkillRecord("granted-skill", "d", true, "c", "USER_CREATED"), "alice"));
 assertThrows(LoomAgentRuntimeException.class, () -> storage.remove("granted-skill", "alice"));
 assertThrows(LoomAgentRuntimeException.class,
 () -> storage.patch("granted-skill", "alice", new cn.wubo.spring.ai.loom.agent.model.UserSkillPatchRequest("d2", true)));
 }

 @Test
 @DisplayName("MARKET_PULLED save() 同名覆盖抛 403")
 void marketPulledSaveRejectsContentOverwrite() {
 userSkillRows = Map.of("pulled", row("pulled", false, 99L, "MARKET_PULLED"));

 LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
 () -> storage.save(new SkillRecord("pulled", "new desc", true, "new content", "MARKET_PULLED"), "alice"));
 assertEquals(403, ex.getStatusCode());
 assertTrue(ex.getMessage().contains("市场"));
 }

 @Test
 @DisplayName("MARKET_PULLED patch() 改 desc 抛 403；只切 default_loaded 允许")
 void marketPulledPatchOnlyAllowsDefaultLoaded() {
 userSkillRows = Map.of("pulled", row("pulled", false, 99L, "MARKET_PULLED"));

 // 改 desc → 抛 403
 LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
 () -> storage.patch("pulled", "alice",
 new cn.wubo.spring.ai.loom.agent.model.UserSkillPatchRequest("new desc", null)));
 assertEquals(403, ex.getStatusCode());

 // 仅切 defaultLoaded → 走 UPDATE，但不更新 description 列
 updateLog.clear();
 storage.patch("pulled", "alice",
 new cn.wubo.spring.ai.loom.agent.model.UserSkillPatchRequest(null, false));
 String update = updateLog.stream().filter(s -> s.startsWith("UPDATE user_skill")).findFirst().orElse(null);
 assertNotNull(update, "MARKET_PULLED patch 应走 UPDATE");
 assertTrue(update.contains("default_loaded"), "应更新 default_loaded 列");
 assertFalse(update.contains("description ="), "MARKET_PULLED patch 不应更新 description 列");
 }

 @Test
 @DisplayName("duplicate 从 MARKET_PULLED 创建新 USER_CREATED skill")
 void duplicateFromMarketPulledCreatesUserCreated() {
 userSkillRows = Map.of("pulled", row("pulled", false, 99L, "MARKET_PULLED"));

 String actual = storage.duplicate("pulled", null, "alice");

 assertEquals("pulled_副本", actual);
 String insert = updateLog.stream().filter(s -> s.startsWith("INSERT INTO user_skill")).findFirst().orElse(null);
 assertNotNull(insert);
 assertTrue(insert.contains("'USER_CREATED'"));
 assertTrue(insert.contains("FALSE"), "locked 必须 FALSE");
 }

 @Test
 @DisplayName("duplicate 重名时自动加 _2 后缀")
 void duplicateNameConflictAddsSuffix() {
 // 源 "a" 存在；"a_副本" 已存在（占位） → 期望 "a_副本_2"
 Map<String, Object> src = row("a", false, null, "USER_CREATED");
 Map<String, Object> conflict = row("a_副本", false, null, "USER_CREATED");
 // 通过自定义 mock：findUserSkill 按 name 区分返回
 when(jt.query(contains("AND name = ?"), any(RowMapper.class), any(Object[].class)))
 .thenAnswer(inv -> {
 RowMapper<UserSkill> m = inv.getArgument(1);
 String name = inv.getArgument(3);
 Map<String, Object> row = "a".equals(name) ? src
 : ("a_副本".equals(name) ? conflict : null);
 return row == null ? List.of() : List.of(m.mapRow(rs(row), 0));
 });
 // ORDER BY source → 查 user_skill 全表（list 会调）
 when(jt.query(contains("ORDER BY source"), any(RowMapper.class), any()))
 .thenAnswer(inv -> {
 RowMapper<UserSkill> m = inv.getArgument(1);
 return List.of(m.mapRow(rs(src), 0), m.mapRow(rs(conflict), 1));
 });

 String actual = storage.duplicate("a", null, "alice");
 assertEquals("a_副本_2", actual);
 }

 @Test
 @DisplayName("duplicate ROLE_GRANTED（locked=true）抛 403")
 void duplicateRoleGrantedRejected() {
 userSkillRows = Map.of("granted-skill", row("granted-skill", true, 7L, "ROLE_GRANTED"));

 LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
 () -> storage.duplicate("granted-skill", null, "alice"));
 assertEquals(403, ex.getStatusCode());
 }

 @Test
 @DisplayName("+：USER_CREATED save() 关联 market_skill_id → 反向同步到 market_skill")
 void authorSaveUpdatesMarketSkill() {
 userSkillRows = Map.of("authored", row("authored", false, 10L, "USER_CREATED"));

 storage.save(new SkillRecord("authored", "new desc", true, "new content", "USER_CREATED"), "alice");

 String marketUpdate = updateLog.stream()
 .filter(s -> s.startsWith("UPDATE market_skill SET description = ?, content = ?"))
 .findFirst().orElse(null);
 assertNotNull(marketUpdate, "save() 应触发反向同步 market_skill");
 assertTrue(marketUpdate.contains("status IN ('APPROVED', 'PENDING')"));
 assertTrue(marketUpdate.contains("author = ?"));
 }

 @Test
 @DisplayName("+：USER_CREATED save() 关联 market_skill_id → 推送所有 MARKET_PULLED 拉取者")
 void authorSavePushesToAllPulled() {
 userSkillRows = Map.of("authored", row("authored", false, 10L, "USER_CREATED"));

 // 让反向同步的 UPDATE 返回 1（即 market_skill 状态允许改）
 // SmartJdbcTemplateMock 默认 update 返回 1
 storage.save(new SkillRecord("authored", "new desc", true, "new content", "USER_CREATED"), "alice");

 String pushUpdate = updateLog.stream()
 .filter(s -> s.startsWith("UPDATE user_skill SET description = ?, content = ?, updated_at = CURRENT_TIMESTAMP")
 && s.contains("source = 'MARKET_PULLED'"))
 .findFirst().orElse(null);
 assertNotNull(pushUpdate, "save() 应触发推送 MARKET_PULLED 拉取者");
 }

 @Test
 @DisplayName("+：USER_CREATED save() 但 market_skill 拒绝（status=REJECTED）→ 不反向同步也不推送")
 void authorSaveNoPushWhenMarketRejected() {
 userSkillRows = Map.of("authored", row("authored", false, 10L, "USER_CREATED"));
 // stub market_skill UPDATE 返回 0（拒绝）；其他 update 返回 1
 when(jt.update(argThat((String s) -> s.startsWith("UPDATE market_skill")), any(Object[].class)))
 .thenReturn(0);

 storage.save(new SkillRecord("authored", "new desc", true, "new content", "USER_CREATED"), "alice");

 boolean hasPush = updateLog.stream().anyMatch(s ->
 s.startsWith("UPDATE user_skill SET description = ?, content = ?, updated_at = CURRENT_TIMESTAMP")
 && s.contains("source = 'MARKET_PULLED'"));
 assertFalse(hasPush, "market_skill 拒绝时不应推送 MARKET_PULLED");
 }

 @Test
 @DisplayName("get() 不存在抛异常（不再回落到 market_skill）")
 void getNotFoundThrowsWithoutMarketFallback() {
 assertThrows(LoomAgentRuntimeException.class, () -> storage.get("nope", "alice"));
 }

 /** 行构造：可显式指定 source（默认按 marketSkillId 推断） */
 private static Map<String, Object> row(String name, boolean locked, Long marketSkillId, String source) {
 Map<String, Object> r = row(name, locked, marketSkillId);
 r.put("source", source);
 return r;
 }
}
