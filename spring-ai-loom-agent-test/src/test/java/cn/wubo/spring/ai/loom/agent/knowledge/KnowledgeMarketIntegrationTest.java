package cn.wubo.spring.ai.loom.agent.knowledge;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.KnowledgeRecord;
import cn.wubo.spring.ai.loom.agent.model.MarketKnowledgeRecord;
import cn.wubo.spring.ai.loom.agent.model.RoleKnowledgeItem;
import cn.wubo.spring.ai.loom.agent.user.DefaultUser;
import cn.wubo.spring.ai.loom.agent.user.IUser;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 知识库市场完整流程集成测试。
 *
 * 使用内嵌 H2 + 真实 JdbcTemplate 的集成测试（不依赖 Spring Boot auto-config），
 * 验证完整的知识库市场流程：
 * 1. 用户 A 创建知识库
 * 2. 用户 A 提交到市场
 * 3. 管理员审批通过
 * 4. 管理员将知识库分配到角色
 * 5. 用户 B 拥有该角色，自动获得知识库访问权
 * 6. 用户 B 可以查看但不能编辑
 */
class KnowledgeMarketIntegrationTest {

    private JdbcTemplate jdbcTemplate;
    private IKnowledge knowledge;
    private IKnowledgeMarketService marketService;
    private IKnowledgeRoleAdmin roleAdmin;
    private IUser user;
    private Cache sessionCache;

    private static final String USER_A = "testuserA";
    private static final String USER_B = "testuserB";
    private static final String ADMIN_USER = "testadmin";
    private static final String TEST_ROLE = "TEST_ROLE_KB";
    private static final String KB_NAME = "测试市场知识库";

    @BeforeEach
    void setUp() {
        // Create in-memory H2 database
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:test-km-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbcTemplate = new JdbcTemplate(ds);

        sessionCache = new ConcurrentMapCache("sessions");

        // Create schema manually (mimics V1.0 + V3.0 migrations)
        initSchema();

        // Create services
        knowledge = new DefaultKnowledge(jdbcTemplate);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        user = new DefaultUser(jdbcTemplate, encoder, sessionCache);

        marketService = new DefaultKnowledgeMarketService(jdbcTemplate, knowledge, user);
        roleAdmin = new DefaultKnowledgeRoleAdmin(jdbcTemplate, marketService);

        // Seed admin user
        user.createUser(ADMIN_USER, "测试管理员", "admin123", "ADMIN");

        // Create test users
        user.createUser(USER_A, "测试用户A", "password123", "USER");
        user.createUser(USER_B, "测试用户B", "password123", "USER");

        // Grant USER_B the test role
        jdbcTemplate.update(
                "MERGE INTO user_role (username, role_code) KEY(username, role_code) VALUES (?, ?)",
                USER_B, TEST_ROLE);

        // Ensure test role exists
        jdbcTemplate.update(
                "MERGE INTO role (code, name, is_system, description) KEY(code) VALUES (?, ?, FALSE, ?)",
                TEST_ROLE, "测试知识库角色", "测试用角色");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    private void initSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE knowledge (
                    id VARCHAR(64) PRIMARY KEY,
                    username VARCHAR(64) NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    description TEXT,
                    CONSTRAINT uk_username_name UNIQUE (username, name)
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE user_info (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    username VARCHAR(64) NOT NULL UNIQUE,
                    nickname VARCHAR(64) NOT NULL,
                    password VARCHAR(255) NOT NULL,
                    type VARCHAR(20) NOT NULL CHECK (type IN ('ADMIN', 'USER'))
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE user_role (
                    username VARCHAR(64) NOT NULL,
                    role_code VARCHAR(32) NOT NULL,
                    PRIMARY KEY (username, role_code)
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE role (
                    code VARCHAR(32) PRIMARY KEY,
                    name VARCHAR(64) NOT NULL,
                    is_system BOOLEAN NOT NULL DEFAULT FALSE,
                    description TEXT,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE loom_market_knowledge (
                    id VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(64) NOT NULL,
                    name VARCHAR(200) NOT NULL,
                    description TEXT,
                    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
                    submitted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    reviewed_at TIMESTAMP,
                    reviewed_by VARCHAR(64),
                    review_comment TEXT,
                    UNIQUE(username, name)
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE loom_user_knowledge (
                    username VARCHAR(64) NOT NULL,
                    market_knowledge_id VARCHAR(36) NOT NULL,
                    source VARCHAR(20) NOT NULL CHECK (source IN ('USER_CREATED', 'MARKET_PULLED', 'ROLE_GRANTED')),
                    locked BOOLEAN DEFAULT FALSE,
                    PRIMARY KEY (username, market_knowledge_id)
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE loom_role_knowledge (
                    role_code VARCHAR(50) NOT NULL,
                    market_knowledge_id VARCHAR(36) NOT NULL,
                    default_enabled BOOLEAN DEFAULT FALSE,
                    sort_order INT DEFAULT 0,
                    PRIMARY KEY (role_code, market_knowledge_id)
                )
                """);
    }

    // ===== Main Flow Test =====

    @Test
    @DisplayName("完整知识库市场流程：创建 → 提交 → 审批 → 角色分配 → 用户B访问")
    void testFullKnowledgeMarketFlow() {
        // Step 1: User A creates a knowledge base
        UserContextHolder.setCurrentUser(USER_A);
        KnowledgeRecord kb = knowledge.insert(KB_NAME, "这是一个测试用的知识库");
        assertThat(kb.username()).isEqualTo(USER_A);
        assertThat(kb.name()).isEqualTo(KB_NAME);
        String kbId = kb.id();
        UserContextHolder.clear();

        // Step 2: User A submits to market
        UserContextHolder.setCurrentUser(USER_A);
        MarketKnowledgeRecord submitted = marketService.submit(kbId);
        assertThat(submitted.status()).isEqualTo(MarketKnowledgeRecord.STATUS_PENDING);
        assertThat(submitted.username()).isEqualTo(USER_A);
        UserContextHolder.clear();

        // Step 3: Admin approves
        UserContextHolder.setCurrentUser(ADMIN_USER);
        MarketKnowledgeRecord approved = marketService.approve(submitted.id());
        assertThat(approved.status()).isEqualTo(MarketKnowledgeRecord.STATUS_APPROVED);
        assertThat(approved.reviewedBy()).isEqualTo(ADMIN_USER);
        UserContextHolder.clear();

        // Step 4: Admin assigns knowledge base to role
        UserContextHolder.setCurrentUser(ADMIN_USER);
        roleAdmin.setRoleKnowledges(TEST_ROLE, List.of(
                new RoleKnowledgeItem(submitted.id(), true)
        ));
        List<RoleKnowledgeItem> roleKbs = roleAdmin.getRoleKnowledges(TEST_ROLE);
        assertThat(roleKbs).hasSize(1);
        assertThat(roleKbs.get(0).marketKnowledgeId()).isEqualTo(submitted.id());
        UserContextHolder.clear();

        // Step 5: Sync role knowledge to User B, who should now have access
        roleAdmin.syncUserKnowledge(USER_B);
        List<KnowledgeRecord> accessibleKbs = knowledge.listAccessible(USER_B);
        assertThat(accessibleKbs).anySatisfy(kr -> {
            assertThat(kr.name()).isEqualTo(KB_NAME);
            assertThat(kr.id()).isEqualTo(submitted.id());
        });

        // Step 6: User B can view but NOT edit (not original creator in knowledge table)
        UserContextHolder.setCurrentUser(USER_B);
        assertThat(knowledge.canEdit(submitted.id())).isFalse();

        // User A can still edit (original creator)
        UserContextHolder.setCurrentUser(USER_A);
        assertThat(knowledge.canEdit(kbId)).isTrue();
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("市场浏览：审批通过的知识库可被列出")
    void testListApprovedMarketKnowledge() {
        UserContextHolder.setCurrentUser(USER_A);
        KnowledgeRecord kb = knowledge.insert(KB_NAME, "用于浏览测试");
        marketService.submit(kb.id());
        UserContextHolder.clear();

        UserContextHolder.setCurrentUser(ADMIN_USER);
        List<MarketKnowledgeRecord> pending = marketService.listPending();
        assertThat(pending).hasSize(1);
        marketService.approve(pending.get(0).id());
        UserContextHolder.clear();

        List<MarketKnowledgeRecord> approvedList = marketService.listApproved(1, 20);
        assertThat(approvedList).anySatisfy(mk ->
                assertThat(mk.name()).isEqualTo(KB_NAME));
    }

    @Test
    @DisplayName("用户拉取：用户可以主动订阅市场知识库")
    void testPullMarketKnowledge() {
        UserContextHolder.setCurrentUser(USER_A);
        KnowledgeRecord kb = knowledge.insert(KB_NAME, "用于拉取测试");
        marketService.submit(kb.id());
        UserContextHolder.clear();

        UserContextHolder.setCurrentUser(ADMIN_USER);
        List<MarketKnowledgeRecord> pending = marketService.listPending();
        marketService.approve(pending.get(0).id());
        UserContextHolder.clear();

        String marketId = marketService.listApproved(1, 1).get(0).id();
        marketService.pull(USER_B, marketId);

        List<KnowledgeRecord> accessibleKbs = knowledge.listAccessible(USER_B);
        assertThat(accessibleKbs).anySatisfy(kr ->
                assertThat(kr.name()).isEqualTo(KB_NAME));

        UserContextHolder.setCurrentUser(USER_B);
        assertThat(knowledge.canEdit(marketId)).isFalse();
    }

    @Test
    @DisplayName("用户撤回：用户可以撤回自己的市场提交")
    void testWithdrawSubmission() {
        UserContextHolder.setCurrentUser(USER_A);
        KnowledgeRecord kb = knowledge.insert(KB_NAME, "用于撤回测试");
        MarketKnowledgeRecord submitted = marketService.submit(kb.id());

        marketService.withdraw(submitted.id());
        UserContextHolder.clear();

        UserContextHolder.setCurrentUser(ADMIN_USER);
        List<MarketKnowledgeRecord> pending = marketService.listPending();
        assertThat(pending).noneMatch(mk -> mk.id().equals(submitted.id()));
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("重复提交防护：同名知识库不能重复提交")
    void testDuplicateSubmitPrevention() {
        UserContextHolder.setCurrentUser(USER_A);
        KnowledgeRecord kb = knowledge.insert(KB_NAME, "用于重复提交测试");
        marketService.submit(kb.id());

        assertThatThrownBy(() -> marketService.submit(kb.id()))
                .isInstanceOf(LoomAgentRuntimeException.class)
                .hasMessageContaining("已存在同名知识库提交");
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("管理员审批：非管理员不能审批")
    void testApproveRequiresAdmin() {
        UserContextHolder.setCurrentUser(USER_A);
        KnowledgeRecord kb = knowledge.insert(KB_NAME, "用于审批权限测试");
        MarketKnowledgeRecord submitted = marketService.submit(kb.id());
        UserContextHolder.clear();

        // Non-admin should fail
        UserContextHolder.setCurrentUser(USER_A);
        assertThatThrownBy(() -> marketService.approve(submitted.id()))
                .isInstanceOf(LoomAgentRuntimeException.class)
                .hasMessageContaining("需要管理员权限");
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("管理员删除：可以删除市场知识库")
    void testAdminDeleteMarketKnowledge() {
        UserContextHolder.setCurrentUser(USER_A);
        KnowledgeRecord kb = knowledge.insert(KB_NAME, "用于删除测试");
        marketService.submit(kb.id());
        UserContextHolder.clear();

        UserContextHolder.setCurrentUser(ADMIN_USER);
        List<MarketKnowledgeRecord> pending = marketService.listPending();
        String marketId = pending.get(0).id();
        marketService.delete(marketId);

        assertThatThrownBy(() -> marketService.getById(marketId))
                .isInstanceOf(LoomAgentRuntimeException.class)
                .hasMessageContaining("市场知识库不存在");
        UserContextHolder.clear();
    }
}
