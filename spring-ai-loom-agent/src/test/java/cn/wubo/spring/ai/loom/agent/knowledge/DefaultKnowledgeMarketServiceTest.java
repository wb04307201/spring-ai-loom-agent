package cn.wubo.spring.ai.loom.agent.knowledge;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.KnowledgeRecord;
import cn.wubo.spring.ai.loom.agent.model.MarketKnowledgeRecord;
import cn.wubo.spring.ai.loom.agent.user.IUser;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DefaultKnowledgeMarketService 单元测试（无审批流）
 */
@DisplayName("DefaultKnowledgeMarketService 单元测试")
class DefaultKnowledgeMarketServiceTest {

 private SmartJdbcTemplateMock jdbcTemplate;
 private IKnowledge knowledge;
 private IUser user;
 private DefaultKnowledgeMarketService marketService;

 @BeforeEach
 void setUp() {
 jdbcTemplate = new SmartJdbcTemplateMock();
 knowledge = mock(IKnowledge.class);
 user = mock(IUser.class);
 marketService = new DefaultKnowledgeMarketService(jdbcTemplate.mock, knowledge, user);
 UserContextHolder.setCurrentUser("testuser");
 }

 @AfterEach
 void tearDown() {
 UserContextHolder.clear();
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

 // queryForList with Class -> empty list
 lenient().doAnswer(inv -> List.of())
 .when(mock).queryForList(anyString(), any(Class.class), any(Object[].class));

 // queryForList without Class -> empty list
 lenient().doAnswer(inv -> List.of())
 .when(mock).queryForList(anyString(), any(Object[].class));

 // queryForObject with Class -> null by default
 lenient().doAnswer(inv -> null)
 .when(mock).queryForObject(anyString(), any(Class.class), any(Object[].class));
 }
 }

 /* ===== submit（直接 APPROVED + UPSERT） ===== */

 @Test
 @DisplayName("submit 直接 APPROVED 状态（不再 PENDING）")
 void testSubmit_directlyApproved() {
 String kbId = "kb-001";
 KnowledgeRecord kb = new KnowledgeRecord(kbId, "testuser", "测试知识库", "描述", "USER_CREATED");
 when(knowledge.list("testuser")).thenReturn(List.of(kb));

 MarketKnowledgeRecord result = new MarketKnowledgeRecord(
 "market-001", "testuser", "测试知识库", "描述",
 "APPROVED", null, null, "testuser", null);
 when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(result);

 MarketKnowledgeRecord returned = marketService.submit(kbId);

 assertEquals(MarketKnowledgeRecord.STATUS_APPROVED, returned.status());
 // 应走 INSERT 路径（不是 UPDATE）
 verify(jdbcTemplate.mock, never()).update(
 argThat((String s) -> s.contains("UPDATE loom_market_knowledge")));
 }

 @Test
 @DisplayName("submit 同一 username+name 已存在 → UPSERT（更新内容）")
 void testSubmit_upsertsExisting() {
 String kbId = "kb-001";
 KnowledgeRecord kb = new KnowledgeRecord(kbId, "testuser", "测试知识库", "新描述", "USER_CREATED");
 when(knowledge.list("testuser")).thenReturn(List.of(kb));
 // SELECT id 走 String.class 路径返回 existingId
 when(jdbcTemplate.mock.queryForObject(
 argThat((String s) -> s.contains("WHERE username = ? AND name = ?")),
 eq(String.class), any(Object[].class)))
 .thenReturn("existing-market-id");
 // getById → 返回 stub
 MarketKnowledgeRecord stub = new MarketKnowledgeRecord(
 "existing-market-id", "testuser", "测试知识库", "新描述",
 "APPROVED", null, null, "testuser", null);
 when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(stub);

 MarketKnowledgeRecord returned = marketService.submit(kbId);

 assertEquals("existing-market-id", returned.id());
 // 应走 UPDATE 而不是 INSERT
 verify(jdbcTemplate.mock).update(
 argThat((String s) -> s.contains("UPDATE loom_market_knowledge SET description")),
 any(Object[].class));
 verify(jdbcTemplate.mock, never()).update(
 argThat((String s) -> s.contains("INSERT INTO loom_market_knowledge")),
 any(Object[].class));
 }

 @Test
 @DisplayName("submit 知识库不属于当前用户")
 void testSubmit_knowledgeNotOwned() {
 when(knowledge.list("testuser")).thenReturn(List.of(
 new KnowledgeRecord("kb-002", "otheruser", "其他", "描述", "USER_CREATED")));

 assertThrows(LoomAgentRuntimeException.class, () -> marketService.submit("kb-001"));
 }

 /* ===== withdraw（admin 也可调用，级联清理 user_knowledge + role_knowledge） ===== */

 @Test
 @DisplayName("withdraw 用户撤回自己的提交（不要求 admin）")
 void testWithdraw_userOwnSubmission() {
 MarketKnowledgeRecord existing = new MarketKnowledgeRecord(
 "market-001", "testuser", "测试知识库", "描述",
 "APPROVED", null, null, null, null);
 when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(existing);
 when(user.isAdmin("testuser")).thenReturn(false);

 assertDoesNotThrow(() -> marketService.withdraw("market-001"));
 // 应级联清理 user_knowledge + role_knowledge + market_knowledge
 verify(jdbcTemplate.mock).update(
 argThat((String s) -> s.contains("DELETE FROM loom_user_knowledge")),
 any(Object[].class));
 verify(jdbcTemplate.mock).update(
 argThat((String s) -> s.contains("DELETE FROM loom_role_knowledge")),
 any(Object[].class));
 }

 @Test
 @DisplayName("withdraw admin 删除他人提交（任意状态）")
 void testWithdraw_adminCanDeleteAny() {
 MarketKnowledgeRecord existing = new MarketKnowledgeRecord(
 "market-001", "otheruser", "其他人的知识库", "描述",
 "APPROVED", null, null, null, null);
 when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(existing);
 when(user.isAdmin("testuser")).thenReturn(true);

 assertDoesNotThrow(() -> marketService.withdraw("market-001"));
 }

 @Test
 @DisplayName("withdraw 非作者非 admin 抛 403")
 void testWithdraw_cannotWithdrawOthers() {
 MarketKnowledgeRecord existing = new MarketKnowledgeRecord(
 "market-001", "otheruser", "其他人的知识库", "描述",
 "APPROVED", null, null, null, null);
 when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(existing);
 when(user.isAdmin("testuser")).thenReturn(false);

 LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
 () -> marketService.withdraw("market-001"));
 assertTrue(ex.getMessage().contains("只能撤回"));
 }

 /* ===== listApproved / listMyPulled / getById ===== */

 @Test
 @DisplayName("listApproved 列出已审批的市场知识库")
 void testListApproved_returnsApprovedOnly() {
 List<MarketKnowledgeRecord> approved = List.of(
 new MarketKnowledgeRecord("m1", "user1", "KB1", "desc1", "APPROVED", null, null, null, null),
 new MarketKnowledgeRecord("m2", "user2", "KB2", "desc2", "APPROVED", null, null, null, null));
 when(jdbcTemplate.mock.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(approved);

 List<MarketKnowledgeRecord> result = marketService.listApproved(1, 20);

 assertEquals(2, result.size());
 assertTrue(result.stream().allMatch(r -> MarketKnowledgeRecord.STATUS_APPROVED.equals(r.status())));
 }

 @Test
 @DisplayName("listMyPulled 列出用户订阅列表")
 void testListMyPulled_returnsSubscribed() {
 List<MarketKnowledgeRecord> pulled = List.of(
 new MarketKnowledgeRecord("m1", "user1", "KB1", "desc1", "APPROVED", null, null, null, null));
 when(jdbcTemplate.mock.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(pulled);

 List<MarketKnowledgeRecord> result = marketService.listMyPulled("testuser");

 assertEquals(1, result.size());
 assertEquals("KB1", result.get(0).name());
 }

 /* ===== pull（不再校验 status='APPROVED'，提交即上架） ===== */

 @Test
 @DisplayName("pull 订阅市场知识库（不再校验 status）")
 void testPull_subscribes() {
 MarketKnowledgeRecord approved = new MarketKnowledgeRecord(
 "market-001", "author1", "公共知识库", "描述",
 "APPROVED", null, null, null, null);
 when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(approved);
 // No existing subscription
 when(jdbcTemplate.mock.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
 // No locked conflicts
 when(jdbcTemplate.mock.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

 marketService.pull("testuser", "market-001");

 verify(jdbcTemplate.mock).update(
 argThat((String s) -> s.contains("INSERT INTO loom_user_knowledge")), any(Object[].class));
 }

 @Test
 @DisplayName("pull 重复订阅抛异常")
 void testPull_duplicateSubscription() {
 MarketKnowledgeRecord approved = new MarketKnowledgeRecord(
 "market-001", "author1", "公共知识库", "描述",
 "APPROVED", null, null, null, null);
 when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(approved);
 when(jdbcTemplate.mock.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);

 LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
 () -> marketService.pull("testuser", "market-001"));
 assertTrue(ex.getMessage().contains("已订阅"));
 }

 @Test
 @DisplayName("getById 市场知识库不存在时抛出异常")
 void testGetById_notFoundThrowsException() {
 when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
 .thenThrow(new EmptyResultDataAccessException(1));

 assertThrows(LoomAgentRuntimeException.class, () -> marketService.getById("nonexistent"));
 }
}