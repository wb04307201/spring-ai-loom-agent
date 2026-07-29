package cn.wubo.spring.ai.loom.agent.knowledge;

import cn.wubo.spring.ai.loom.agent.model.MarketKnowledgeRecord;
import cn.wubo.spring.ai.loom.agent.model.RoleKnowledgeItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DefaultKnowledgeRoleAdmin 单元测试
 */
@DisplayName("DefaultKnowledgeRoleAdmin 单元测试")
class DefaultKnowledgeRoleAdminTest {

    private JdbcTemplate jdbcTemplate;
    private IKnowledgeMarketService marketService;
    private DefaultKnowledgeRoleAdmin roleAdmin;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        marketService = mock(IKnowledgeMarketService.class);
        roleAdmin = new DefaultKnowledgeRoleAdmin(jdbcTemplate, marketService);

        // Stub all update overloads
        lenient().doAnswer(inv -> 1).when(jdbcTemplate).update(anyString());
        lenient().doAnswer(inv -> 1).when(jdbcTemplate).update(anyString(), (Object) any());
        lenient().doAnswer(inv -> 1).when(jdbcTemplate).update(anyString(), (Object) any(), (Object) any());
        lenient().doAnswer(inv -> 1).when(jdbcTemplate).update(anyString(), (Object) any(), (Object) any(), (Object) any());
        lenient().doAnswer(inv -> 1).when(jdbcTemplate).update(anyString(), (Object) any(), (Object) any(), (Object) any(), (Object) any());
        lenient().doAnswer(inv -> 1).when(jdbcTemplate).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("getRoleKnowledges 返回角色关联的知识库列表")
    void testGetRoleKnowledges_returnsItems() {
        List<RoleKnowledgeItem> items = List.of(
                new RoleKnowledgeItem("mk-1", true),
                new RoleKnowledgeItem("mk-2", false));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(items);

        List<RoleKnowledgeItem> result = roleAdmin.getRoleKnowledges("ADMIN");

        assertEquals(2, result.size());
        assertEquals("mk-1", result.get(0).marketKnowledgeId());
        assertTrue(result.get(0).defaultEnabled());
        assertFalse(result.get(1).defaultEnabled());
    }

    @Test
    @DisplayName("getRoleKnowledges 空角色返回空列表")
    void testGetRoleKnowledges_emptyRoleReturnsEmpty() {
        List<RoleKnowledgeItem> result = roleAdmin.getRoleKnowledges("NOBODY");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("setRoleKnowledges 覆盖式设置角色知识库")
    void testSetRoleKnowledges_overwrites() {
        List<RoleKnowledgeItem> items = List.of(
                new RoleKnowledgeItem("mk-1", true),
                new RoleKnowledgeItem("mk-2", false));

        roleAdmin.setRoleKnowledges("ADMIN", items);

        verify(jdbcTemplate).update(argThat((String s) -> s.startsWith("DELETE")), eq("ADMIN"));
    }

    @Test
    @DisplayName("setRoleKnowledges null 列表清空")
    void testSetRoleKnowledges_nullListClears() {
        roleAdmin.setRoleKnowledges("ADMIN", null);

        verify(jdbcTemplate).update(argThat((String s) -> s.startsWith("DELETE")), eq("ADMIN"));
    }

    @Test
    @DisplayName("setRoleKnowledges 空列表清空")
    void testSetRoleKnowledges_emptyListClears() {
        roleAdmin.setRoleKnowledges("ADMIN", List.of());

        verify(jdbcTemplate).update(argThat((String s) -> s.startsWith("DELETE")), eq("ADMIN"));
    }

    @Test
    @DisplayName("listRoleKnowledges 返回完整信息")
    void testListRoleKnowledges_returnsFullInfo() {
        List<MarketKnowledgeRecord> kbs = List.of(
                new MarketKnowledgeRecord("mk-1", "user1", "KB1", "desc1", "APPROVED", null, null, null, null));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(kbs);

        List<MarketKnowledgeRecord> result = roleAdmin.listRoleKnowledges("ADMIN");

        assertEquals(1, result.size());
        assertEquals("KB1", result.get(0).name());
        assertEquals("APPROVED", result.get(0).status());
    }

    @Test
    @DisplayName("syncUserKnowledge 同步角色知识库到用户")
    void testSyncUserKnowledge_syncsToUser() {
        // Default stubs
        lenient().when(jdbcTemplate.queryForList(anyString(), (Object[]) any())).thenReturn(List.of());
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        lenient().when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        // User has ADMIN role
        when(jdbcTemplate.queryForList(
                eq("SELECT role_code FROM user_role WHERE username = ?"), eq(String.class), eq("testuser")))
                .thenReturn(List.of("ADMIN"));

        // Role has one knowledge base
        when(jdbcTemplate.queryForList(
                argThat((String s) -> s.contains("role_knowledge")), eq("ADMIN")))
                .thenReturn(List.of(Map.<String, Object>of("mid", "mk-1", "name", "KB1", "def", true)));

        roleAdmin.syncUserKnowledge("testuser");

        // Verify INSERT was called
        verify(jdbcTemplate, atLeastOnce()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("syncUserKnowledge 无角色时不做任何事")
    void testSyncUserKnowledge_noRolesDoesNothing() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), (Object[]) any())).thenReturn(List.of());

        roleAdmin.syncUserKnowledge("testuser");

        verify(jdbcTemplate, never()).update(anyString(), (Object[]) any());
    }
}
