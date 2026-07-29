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
 * DefaultKnowledgeMarketService 单元测试
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

    @Test
    @DisplayName("submit 提交知识库到市场（PENDING 状态）")
    void testSubmit_setsPendingStatus() {
        String kbId = "kb-001";
        KnowledgeRecord kb = new KnowledgeRecord(kbId, "testuser", "测试知识库", "描述");
        when(knowledge.list("testuser")).thenReturn(List.of(kb));

        MarketKnowledgeRecord result = new MarketKnowledgeRecord(
                "market-001", "testuser", "测试知识库", "描述",
                "PENDING", null, null, null, null);
        when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(result);

        MarketKnowledgeRecord returned = marketService.submit(kbId);

        assertEquals(MarketKnowledgeRecord.STATUS_PENDING, returned.status());
        assertEquals("测试知识库", returned.name());
        verify(jdbcTemplate.mock, atLeastOnce()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("submit 重复提交拦截")
    void testSubmit_duplicateSubmissionRejected() {
        String kbId = "kb-001";
        KnowledgeRecord kb = new KnowledgeRecord(kbId, "testuser", "测试知识库", "描述");
        when(knowledge.list("testuser")).thenReturn(List.of(kb));
        when(jdbcTemplate.mock.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);

        assertThrows(LoomAgentRuntimeException.class, () -> marketService.submit(kbId));
    }

    @Test
    @DisplayName("submit 被拒绝的条目允许重新提交")
    void testSubmit_rejectedCanBeResubmitted() {
        String kbId = "kb-001";
        KnowledgeRecord kb = new KnowledgeRecord(kbId, "testuser", "测试知识库", "描述");
        when(knowledge.list("testuser")).thenReturn(List.of(kb));
        // COUNT returns 0 (only rejected entries exist, not counted)
        when(jdbcTemplate.mock.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);

        MarketKnowledgeRecord result = new MarketKnowledgeRecord(
                "market-002", "testuser", "测试知识库", "描述",
                "PENDING", null, null, null, null);
        when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(result);

        MarketKnowledgeRecord returned = marketService.submit(kbId);

        assertEquals(MarketKnowledgeRecord.STATUS_PENDING, returned.status());
    }

    @Test
    @DisplayName("submit 知识库不属于当前用户")
    void testSubmit_knowledgeNotOwned() {
        when(knowledge.list("testuser")).thenReturn(List.of(
                new KnowledgeRecord("kb-002", "otheruser", "其他", "描述")));

        assertThrows(LoomAgentRuntimeException.class, () -> marketService.submit("kb-001"));
    }

    @Test
    @DisplayName("approve 审批通过")
    void testApprove_setsApprovedStatus() {
        when(user.isAdmin("testuser")).thenReturn(true);
        MarketKnowledgeRecord result = new MarketKnowledgeRecord(
                "market-001", "testuser", "测试知识库", "描述",
                "APPROVED", null, null, null, null);
        when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(result);

        MarketKnowledgeRecord returned = marketService.approve("market-001");

        assertEquals(MarketKnowledgeRecord.STATUS_APPROVED, returned.status());
    }

    @Test
    @DisplayName("approve 非管理员抛异常")
    void testApprove_nonAdminThrows() {
        when(user.isAdmin("testuser")).thenReturn(false);
        MarketKnowledgeRecord result = new MarketKnowledgeRecord(
                "market-001", "testuser", "测试知识库", "描述",
                "APPROVED", null, null, null, null);
        lenient().when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(result);

        LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
                () -> marketService.approve("market-001"));
        assertTrue(ex.getMessage().contains("管理员"));
    }

    @Test
    @DisplayName("approve 对非PENDING条目抛异常")
    void testApprove_nonPendingThrows() {
        when(user.isAdmin("testuser")).thenReturn(true);
        // UPDATE returns 0 rows (not PENDING)
        doAnswer(inv -> 0).when(jdbcTemplate.mock).update(anyString(), (Object) any(), (Object) any());
        MarketKnowledgeRecord result = new MarketKnowledgeRecord(
                "market-001", "testuser", "测试知识库", "描述",
                "APPROVED", null, null, null, null);
        when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(result);

        LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
                () -> marketService.approve("market-001"));
        assertTrue(ex.getMessage().contains("只能审批"));
    }

    @Test
    @DisplayName("reject 拒绝提交")
    void testReject_setsRejectedStatus() {
        when(user.isAdmin("testuser")).thenReturn(true);
        MarketKnowledgeRecord result = new MarketKnowledgeRecord(
                "market-001", "testuser", "测试知识库", "描述",
                "REJECTED", null, null, null, null);
        when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(result);

        MarketKnowledgeRecord returned = marketService.reject("market-001");

        assertEquals(MarketKnowledgeRecord.STATUS_REJECTED, returned.status());
    }

    @Test
    @DisplayName("reject 非管理员抛异常")
    void testReject_nonAdminThrows() {
        when(user.isAdmin("testuser")).thenReturn(false);
        MarketKnowledgeRecord result = new MarketKnowledgeRecord(
                "market-001", "testuser", "测试知识库", "描述",
                "REJECTED", null, null, null, null);
        lenient().when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(result);

        LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
                () -> marketService.reject("market-001"));
        assertTrue(ex.getMessage().contains("管理员"));
    }

    @Test
    @DisplayName("reject 对非PENDING条目抛异常")
    void testReject_nonPendingThrows() {
        when(user.isAdmin("testuser")).thenReturn(true);
        doAnswer(inv -> 0).when(jdbcTemplate.mock).update(anyString(), (Object) any(), (Object) any());
        MarketKnowledgeRecord result = new MarketKnowledgeRecord(
                "market-001", "testuser", "测试知识库", "描述",
                "APPROVED", null, null, null, null);
        when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(result);

        LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
                () -> marketService.reject("market-001"));
        assertTrue(ex.getMessage().contains("只能拒绝"));
    }

    @Test
    @DisplayName("withdraw 用户撤回自己的提交")
    void testWithdraw_userOwnSubmission() {
        MarketKnowledgeRecord existing = new MarketKnowledgeRecord(
                "market-001", "testuser", "测试知识库", "描述",
                "PENDING", null, null, null, null);
        when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(existing);

        assertDoesNotThrow(() -> marketService.withdraw("market-001"));
    }

    @Test
    @DisplayName("withdraw 不能撤回他人的提交")
    void testWithdraw_cannotWithdrawOthers() {
        MarketKnowledgeRecord existing = new MarketKnowledgeRecord(
                "market-001", "otheruser", "其他人的知识库", "描述",
                "PENDING", null, null, null, null);
        when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(existing);

        LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
                () -> marketService.withdraw("market-001"));
        assertTrue(ex.getMessage().contains("只能撤回自己的提交"));
    }

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

    @Test
    @DisplayName("pull 订阅已审批的市场知识库")
    void testPull_subscribesToApproved() {
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
    @DisplayName("pull 不能订阅未审批的知识库")
    void testPull_rejectsNonApproved() {
        MarketKnowledgeRecord pending = new MarketKnowledgeRecord(
                "market-001", "author1", "待审批", "描述",
                "PENDING", null, null, null, null);
        when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(pending);

        LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
                () -> marketService.pull("testuser", "market-001"));
        assertTrue(ex.getMessage().contains("只能订阅已审批"));
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

    @Test
    @DisplayName("delete 非管理员不能删除他人提交")
    void testDelete_nonAdminCannotDeleteOthers() {
        MarketKnowledgeRecord existing = new MarketKnowledgeRecord(
                "market-001", "otheruser", "其他人的知识库", "描述",
                "APPROVED", null, null, null, null);
        when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(existing);
        when(user.isAdmin("testuser")).thenReturn(false);

        LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
                () -> marketService.delete("market-001"));
        assertTrue(ex.getMessage().contains("无权限删除他人提交的知识库"));
    }

    @Test
    @DisplayName("delete 管理员可以删除任何提交")
    void testDelete_adminCanDeleteAny() {
        MarketKnowledgeRecord existing = new MarketKnowledgeRecord(
                "market-001", "otheruser", "其他人的知识库", "描述",
                "APPROVED", null, null, null, null);
        when(jdbcTemplate.mock.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(existing);
        when(user.isAdmin("testuser")).thenReturn(true);

        assertDoesNotThrow(() -> marketService.delete("market-001"));
    }
}
