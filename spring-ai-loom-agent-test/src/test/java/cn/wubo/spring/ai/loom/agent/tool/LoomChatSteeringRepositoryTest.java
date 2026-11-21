package cn.wubo.spring.ai.loom.agent.tool;

import cn.wubo.spring.ai.loom.agent.model.SteeringAuditRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Task 9(spec 2026-11-21 agentic-loop-steering S4)—— {@code loom_chat_steering}
 * 审计仓储回归锁(H2 内存库,仿 {@code JdbcLoomScheduleExecutionRepositoryTest} 模式)。
 *
 * <p>锁定语义(spec §5.9):
 * <ul>
 *   <li>record → findByConversationId 文本完整 round-trip —— emoji/中文走 CLOB
 *       多字节无损(Review Focus 5);</li>
 *   <li>{@code ensureSchema()} 幂等 —— 重复调用不抛(Flyway 已建表 + bean 重建/
 *       Flyway 被绕过两种场景共用此兜底);</li>
 *   <li>同 conv 多条按 seq 排序(注入时刻相同也必须按 seq 定序 —— Task 10 历史
 *       交错依赖该确定性);</li>
 *   <li>会话隔离:find 不得串会话。</li>
 * </ul>
 */
@DisplayName("Task 9:loom_chat_steering 审计仓储(H2 round-trip / 幂等 / seq 定序)")
class LoomChatSteeringRepositoryTest {

    private DriverManagerDataSource ds;
    private JdbcLoomChatSteeringRepository repository;

    @BeforeEach
    void setUp() {
        ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:loom-steering-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        repository = new JdbcLoomChatSteeringRepository(new JdbcTemplate(ds));
        repository.ensureSchema();
    }

    @Test
    @DisplayName("emoji/中文文本 CLOB round-trip 无损,全部字段原样存活")
    void roundtrip_emoji_text() {
        Instant at = Instant.parse("2026-11-22T10:00:00Z");
        repository.record("conv-rt", "alice", "🚀部署dev分支", at, 1);

        List<SteeringAuditRow> rows = repository.findByConversationId("conv-rt");
        assertThat(rows).hasSize(1);
        SteeringAuditRow row = rows.get(0);
        assertThat(row.id()).as("自增主键由数据库回填").isPositive();
        assertThat(row.conversationId()).isEqualTo("conv-rt");
        assertThat(row.username()).isEqualTo("alice");
        assertThat(row.messageText())
                .as("emoji + 中文经 CLOB 必须逐字符完整(审计文案 = 用户原文)")
                .isEqualTo("🚀部署dev分支");
        assertThat(row.injectedAt()).isEqualTo(at);
        assertThat(row.seq()).isEqualTo(1);
    }

    @Test
    @DisplayName("ensureSchema 幂等 —— 重复调用(含同库新实例)不抛")
    void ensureSchema_idempotent() {
        assertThatCode(() -> repository.ensureSchema()).doesNotThrowAnyException();
        // 模拟第二个 bean 实例对同一已存在库再兜底建表(bean 重建 / Flyway 与兜底双写)
        JdbcLoomChatSteeringRepository another =
                new JdbcLoomChatSteeringRepository(new JdbcTemplate(ds));
        assertThatCode(another::ensureSchema).doesNotThrowAnyException();
        // 兜底后既有数据不受影响
        repository.record("conv-none", "alice", "x", Instant.now(), 1);
        assertThat(another.findByConversationId("conv-none")).hasSize(1);
    }

    @Test
    @DisplayName("同 conv 多条按 seq 定序(注入时刻相同也必须稳定),且跨会话不串")
    void seq_ordering() {
        Instant same = Instant.parse("2026-11-22T12:00:00Z");
        // 故意乱序写入,断言读侧按 seq 升序
        repository.record("conv-seq", "bob", "第三条", same, 3);
        repository.record("conv-seq", "bob", "第一条", same, 1);
        repository.record("conv-seq", "bob", "第二条", same, 2);
        repository.record("conv-other", "bob", "别处", same, 1);

        List<SteeringAuditRow> rows = repository.findByConversationId("conv-seq");
        assertThat(rows).extracting(SteeringAuditRow::seq).containsExactly(1, 2, 3);
        assertThat(rows).extracting(SteeringAuditRow::messageText)
                .containsExactly("第一条", "第二条", "第三条");
        assertThat(repository.findByConversationId("conv-unknown")).isEmpty();
    }

    @Test
    @DisplayName("V1.0 Flyway 迁移建表可用 —— 全新库跑完迁移后仓储零兜底直写直读")
    void flywayV10_createsTable_usableWithoutEnsureSchema() {
        org.flywaydb.core.Flyway flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(freshDs())
                .locations("classpath:db/migration")
                .load();
        assertThat(flyway.migrate().success).isTrue();
        // 仓储实例不调 ensureSchema —— 表必须已由 V1.0 段建好(V1.0 新段语法锁)
        JdbcLoomChatSteeringRepository viaFlyway = new JdbcLoomChatSteeringRepository(
                new JdbcTemplate(flyway.getConfiguration().getDataSource()));
        viaFlyway.record("conv-flyway", "carol", "🚀部署dev分支", Instant.parse("2026-11-22T13:00:00Z"), 7);
        assertThat(viaFlyway.findByConversationId("conv-flyway"))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.messageText()).isEqualTo("🚀部署dev分支");
                    assertThat(r.seq()).isEqualTo(7);
                });
    }

    @Test
    @DisplayName("deleteByConversation 真 DB:删本会话全部行并返回行数,他会话零波及,null/blank 防御 0 行不抛(R23)")
    void deleteByConversation_realDb() {
        Instant base = Instant.parse("2026-11-22T14:00:00Z");
        repository.record("conv-del", "alice", "第一条", base, 1);
        repository.record("conv-del", "alice", "第二条", base.plusSeconds(1), 2);
        repository.record("conv-del-keep", "alice", "别家会话", base, 1);

        assertThat(repository.deleteByConversation("conv-del"))
                .as("返回实际删除行数")
                .isEqualTo(2);
        assertThat(repository.findByConversationId("conv-del"))
                .as("R23 验收:删会话后审计行清零")
                .isEmpty();
        assertThat(repository.findByConversationId("conv-del-keep"))
                .as("隔离:conversation_id 精确匹配,他会话行不得受波及")
                .hasSize(1);

        // 幂等/防御:不存在会话、null、空白都返回 0 且不抛(级联路径可放心调用)
        assertThat(repository.deleteByConversation("conv-not-exist")).isZero();
        assertThat(repository.deleteByConversation(null)).isZero();
        assertThat(repository.deleteByConversation("   ")).isZero();
        // 二次删除仍 0(不抛)
        assertThat(repository.deleteByConversation("conv-del")).isZero();
    }

    private static DriverManagerDataSource freshDs() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:loom-steering-flyway-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        return ds;
    }
}
