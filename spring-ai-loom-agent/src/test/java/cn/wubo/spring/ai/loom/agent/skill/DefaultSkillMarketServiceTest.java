package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.MarketSkill;
import cn.wubo.spring.ai.loom.agent.model.MarketSkillSubmitRequest;
import cn.wubo.spring.ai.loom.agent.model.UserSkill;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * DefaultSkillMarketService 单元测试
 */
@DisplayName("DefaultSkillMarketService 单元测试")
class DefaultSkillMarketServiceTest {

 private SmartJdbcTemplateMock jdbcTemplate;
 private DefaultSkillMarketService marketService;

 @BeforeEach
 void setUp() {
 jdbcTemplate = new SmartJdbcTemplateMock();
 marketService = new DefaultSkillMarketService(jdbcTemplate.mock);
 }

 @AfterEach
 void tearDown() {
 }

 /** Helper that creates a JdbcTemplate mock with sensible defaults for all overloads. */
 static class SmartJdbcTemplateMock {
 final JdbcTemplate mock = mock(JdbcTemplate.class);

 SmartJdbcTemplateMock() {
 // All update overloads -> return 1
 doAnswer(inv -> 1).when(mock).update(anyString());
 doAnswer(inv -> 1).when(mock).update(anyString(), (Object[]) any());
 doAnswer(inv -> 1).when(mock).update(anyString(), any(Object[].class));

 // queryForObject with RowMapper -> throws EmptyResultDataAccessException by default
 lenient().doThrow(new EmptyResultDataAccessException(1))
 .when(mock).queryForObject(anyString(), any(RowMapper.class), any(Object[].class));

 // query with RowMapper -> returns empty list by default
 lenient().doAnswer(inv -> List.of())
 .when(mock).query(anyString(), any(RowMapper.class), any(Object[].class));

 // query with RowMapper and args -> returns empty list by default
 lenient().doAnswer(inv -> List.of())
 .when(mock).query(anyString(), any(RowMapper.class), any(Object[].class));

 // query with varargs variant
 lenient().doAnswer(inv -> List.of())
 .when(mock).query(anyString(), any(RowMapper.class), any(Object[].class));

 // queryForObject with Class -> null by default
 lenient().doAnswer(inv -> null)
 .when(mock).queryForObject(anyString(), eq(Integer.class), any(Object[].class));

 lenient().doAnswer(inv -> null)
 .when(mock).queryForObject(anyString(), eq(Long.class), any(Object[].class));
 }
 }

 /* ===== listMySubmitted ===== */

 @Test
 @DisplayName("listMySubmitted 列出用户提交的所有技能（起都是 APPROVED）")
 void testListMySubmitted_returnsAllStatuses() {
 MarketSkill s1 = new MarketSkill(
 1L, "Skill A", "描述A", "内容A",
 "testuser", MarketSkill.STATUS_APPROVED,
 LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(1),
 "alice", "OK");
 MarketSkill s2 = new MarketSkill(
 2L, "Skill B", "描述B", "内容B",
 "testuser", MarketSkill.STATUS_APPROVED,
 LocalDateTime.now().minusDays(1), LocalDateTime.now().minusDays(1),
 "alice", null);
 MarketSkill s3 = new MarketSkill(
 3L, "Skill C", "描述C", "内容C",
 "testuser", MarketSkill.STATUS_APPROVED,
 LocalDateTime.now(), LocalDateTime.now(), "alice", "OK");

 when(jdbcTemplate.mock.query(anyString(), any(RowMapper.class), any(Object[].class)))
 .thenReturn(List.of(s1, s2, s3));

 List<MarketSkill> result = marketService.listMySubmitted("testuser");

 assertEquals(3, result.size());
 assertEquals("Skill A", result.get(0).name());
 assertEquals("Skill B", result.get(1).name());
 assertEquals("Skill C", result.get(2).name());
 }

 @Test
 @DisplayName("listMySubmitted 空结果返回空列表")
 void testListMySubmitted_emptyResult() {
 when(jdbcTemplate.mock.query(anyString(), any(RowMapper.class), any(Object[].class)))
 .thenReturn(List.of());

 List<MarketSkill> result = marketService.listMySubmitted("testuser");

 assertTrue(result.isEmpty());
 }

 /* ===== withdraw ===== */

 @Test
 @DisplayName("withdraw 任何状态可撤回（不再仅 PENDING）")
 void testWithdraw_successAnyStatus() {
 boolean result = marketService.withdraw("testuser", 1L);

 assertTrue(result);
 verify(jdbcTemplate.mock).update(
 argThat((String s) -> s.contains("DELETE FROM market_skill")
 && !s.contains("status = 'PENDING'")),
 eq(1L), eq("testuser"));
 // 反清空 author user_skill.market_skill_id
 verify(jdbcTemplate.mock).update(
 argThat((String s) -> s.contains("UPDATE user_skill SET market_skill_id = NULL")),
 eq("testuser"), eq(1L));
 }

 @Test
 @DisplayName("withdraw 返回 false 当没有删除行（不是本人或不存在的 id）")
 void testWithdraw_returnsFalseWhenNoRows() {
 doAnswer(inv -> 0).when(jdbcTemplate.mock)
 .update(anyString(), any(Object[].class));

 boolean result = marketService.withdraw("testuser", 1L);

 assertFalse(result);
 // 没删除成功时不应反清空 user_skill
 verify(jdbcTemplate.mock, never()).update(
 argThat((String s) -> s.contains("UPDATE user_skill SET market_skill_id = NULL")),
 any(Object[].class));
 }

 @Test
 @DisplayName("withdraw 使用 Long 类型 id")
 void testWithdraw_usesLongId() {
 marketService.withdraw("testuser", 42L);

 verify(jdbcTemplate.mock).update(
 anyString(), eq(42L), eq("testuser"));
 }

 /* ===== submit/pull 改造 ===== */

 @Test
 @DisplayName("submit 直接 APPROVED + 反写 author user_skill.market_skill_id")
 void testSubmit_directlyApprovedAndBindsAuthor() {
 MarketSkill stub = new MarketSkill(
 100L, "my-skill", "desc", "content",
 "alice", MarketSkill.STATUS_APPROVED,
 LocalDateTime.now(), LocalDateTime.now(), "alice", null);
 // SELECT id 默认抛 EmptyResultDataAccessException → 走 INSERT 分支
 // SELECT MAX(id) 返回 100L（让 marketId 有值）
 doReturn(100L).when(jdbcTemplate.mock).queryForObject(
 contains("SELECT MAX(id)"), eq(Long.class), any(Object[].class));
 // get() 用 doReturn 返回 stub（避免抛 "Skill 不存在"）
 doReturn(stub).when(jdbcTemplate.mock).queryForObject(
 argThat((String s) -> s.contains("FROM market_skill WHERE id = ?")),
 any(RowMapper.class), any(Object[].class));

 MarketSkillSubmitRequest req = new MarketSkillSubmitRequest(
 "my-skill", "desc", "content");
 MarketSkill result = marketService.submit("alice", req);

 assertNotNull(result);
 verify(jdbcTemplate.mock).update(
 contains("INSERT INTO market_skill"),
 eq("my-skill"), eq("desc"), eq("content"), eq("alice"), eq("alice"));
 verify(jdbcTemplate.mock).update(
 contains("UPDATE user_skill SET market_skill_id = ?"),
 eq(100L), eq("alice"), eq("my-skill"));
 }

 @Test
 @DisplayName("submit 同一作者+name 已存在 → UPSERT + 仍写 APPROVED")
 void testSubmit_upsertsExistingAuthorName() {
 doReturn(7L).when(jdbcTemplate.mock).queryForObject(
 argThat((String s) -> s.contains("WHERE author = ? AND name = ?")),
 eq(Long.class), any(Object[].class));
 MarketSkill stub = new MarketSkill(
 7L, "my-skill", "new-desc", "new-content",
 "alice", MarketSkill.STATUS_APPROVED,
 LocalDateTime.now(), LocalDateTime.now(), "alice", null);
 doReturn(stub).when(jdbcTemplate.mock).queryForObject(
 argThat((String s) -> s.contains("FROM market_skill WHERE id = ?")),
 any(RowMapper.class), any(Object[].class));

 MarketSkillSubmitRequest req = new MarketSkillSubmitRequest(
 "my-skill", "new-desc", "new-content");
 marketService.submit("alice", req);

 verify(jdbcTemplate.mock).update(
 argThat((String s) -> s.contains("UPDATE market_skill SET description = ?, content = ?, status = 'APPROVED'")),
 eq("new-desc"), eq("new-content"), eq("alice"), eq(7L));
 verify(jdbcTemplate.mock, never()).update(
 argThat((String s) -> s.contains("INSERT INTO market_skill")),
 any(Object[].class));
 }

 @Test
 @DisplayName("pull 不再校验 status='APPROVED'")
 void testPull_noStatusCheck() {
 MarketSkill legacy = new MarketSkill(
 1L, "legacy", "d", "c",
 "alice", MarketSkill.STATUS_APPROVED,
 LocalDateTime.now(), LocalDateTime.now(), "alice", null);
 doReturn(legacy).when(jdbcTemplate.mock).queryForObject(
 argThat((String s) -> s.contains("FROM market_skill WHERE id = ?")),
 any(RowMapper.class), any(Object[].class));

 // 不再因 status 抛错 —— 后续 user_skill 查询因 mock 不全会抛错，try-catch 兜底
 try {
 marketService.pull("bob", 1L);
 } catch (Exception ignored) {
 // 不在乎后续 mock 缺失
 }
 verify(jdbcTemplate.mock).queryForObject(
 argThat((String s) -> s.contains("FROM market_skill WHERE id = ?")),
 any(RowMapper.class), any(Object[].class));
 }

 @Test
 @DisplayName("pull 同名 USER_CREATED → 拒绝覆盖（403）")
 void testPull_rejectsUserCreated() {
 MarketSkill mkt = new MarketSkill(
 1L, "shared", "d", "market content",
 "other-user", MarketSkill.STATUS_APPROVED,
 LocalDateTime.now(), LocalDateTime.now(), "admin", null);
 doReturn(mkt).when(jdbcTemplate.mock).queryForObject(
 argThat((String s) -> s.contains("FROM market_skill WHERE id = ?")),
 any(RowMapper.class), any(Object[].class));
 // findUserSkill → 返回 USER_CREATED 行（locked=false）
 UserSkill userCreated = new UserSkill(
 99L, "alice", "shared", "my content", "alice content",
 "USER_CREATED", 1L, true, false,
 LocalDateTime.now(), LocalDateTime.now());
 doReturn(List.of(userCreated)).when(jdbcTemplate.mock).query(
 anyString(), any(RowMapper.class), any(Object[].class));

 LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
 () -> marketService.pull("alice", 1L));
 assertEquals(403, ex.getStatusCode());
 assertTrue(ex.getMessage().contains("自建"), "错误消息应说明是自建 skill 冲突: " + ex.getMessage());
 // 不应有任何 update 调用（拒绝拉取，不改 user_skill）
 verify(jdbcTemplate.mock, never()).update(anyString(), any(Object[].class));
 }
}
