package cn.wubo.spring.ai.loom.agent.skill;

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
    @DisplayName("listMySubmitted 列出用户提交的所有技能")
    void testListMySubmitted_returnsAllStatuses() {
        MarketSkill s1 = new MarketSkill(
                1L, "Skill A", "描述A", "内容A", "1.0.0",
                "testuser", MarketSkill.STATUS_APPROVED,
                LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(1),
                "admin", "OK");
        MarketSkill s2 = new MarketSkill(
                2L, "Skill B", "描述B", "内容B", "1.0.0",
                "testuser", MarketSkill.STATUS_PENDING,
                LocalDateTime.now().minusDays(1), null, null, null);
        MarketSkill s3 = new MarketSkill(
                3L, "Skill C", "描述C", "内容C", "1.0.0",
                "testuser", MarketSkill.STATUS_REJECTED,
                LocalDateTime.now(), LocalDateTime.now(), "admin", "不符合要求");

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
    @DisplayName("withdraw 成功撤回 PENDING 提交")
    void testWithdraw_successForPending() {
        boolean result = marketService.withdraw("testuser", 1L);

        assertTrue(result);
        verify(jdbcTemplate.mock).update(
                argThat((String s) -> s.contains("DELETE FROM market_skill")
                        && s.contains("status = 'PENDING'")),
                eq(1L), eq("testuser"));
    }

    @Test
    @DisplayName("withdraw 返回 false 当没有删除行（非 PENDING 或不是本人）")
    void testWithdraw_returnsFalseWhenNoRows() {
        doAnswer(inv -> 0).when(jdbcTemplate.mock)
                .update(anyString(), any(Object[].class));

        boolean result = marketService.withdraw("testuser", 1L);

        assertFalse(result);
    }

    @Test
    @DisplayName("withdraw 使用 Long 类型 id")
    void testWithdraw_usesLongId() {
        marketService.withdraw("testuser", 42L);

        verify(jdbcTemplate.mock).update(
                anyString(), eq(42L), eq("testuser"));
    }
}
