package cn.wubo.spring.ai.loom.agent.schedule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link JdbcLoomScheduleExecutionRepository} against an
 * H2 in-memory database. Mirrors the pattern of
 * {@link JdbcLoomScheduleTriggerRepositoryTest} so all four data-layer classes
 * in the schedule package have parallel coverage.
 *
 * <p>Covers the seven surface methods declared on
 * {@link ILoomScheduleExecutionRepository}: {@code save}, {@code ensureSchema},
 * {@code findByTaskName}, {@code countByTaskName}, {@code trimTaskHistory},
 * {@code deleteOlderThan}, {@code deleteByUserAndConversation}.</p>
 */
class JdbcLoomScheduleExecutionRepositoryTest {

    private JdbcLoomScheduleExecutionRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:loom-exec-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        repository = new JdbcLoomScheduleExecutionRepository(jdbc);
        repository.ensureSchema();
    }

    private static LoomScheduleExecutionRecord fire(String taskName, Instant when, boolean success) {
        return new LoomScheduleExecutionRecord(
                null, taskName, when, 42L, success,
                success ? null : "boom", LoomScheduleExecutionRecord.FIRED_BY_SCHEDULER);
    }

    @Test
    void save_thenFindByTaskName_returnsRowsInDescendingFireTime() {
        Instant t1 = Instant.parse("2026-07-23T10:00:00Z");
        Instant t2 = Instant.parse("2026-07-23T11:00:00Z");
        Instant t3 = Instant.parse("2026-07-23T12:00:00Z");

        repository.save(fire("remind", t1, true));
        repository.save(fire("remind", t2, true));
        repository.save(fire("remind", t3, false));

        List<LoomScheduleExecutionRecord> all = repository.findByTaskName("remind", 0);
        assertThat(all).hasSize(3);
        // Newest first (matches ORDER BY fire_time DESC).
        assertThat(all.get(0).fireTime()).isEqualTo(t3);
        assertThat(all.get(1).fireTime()).isEqualTo(t2);
        assertThat(all.get(2).fireTime()).isEqualTo(t1);
        assertThat(all.get(0).success()).isFalse();
        assertThat(all.get(0).errorMessage()).isEqualTo("boom");
    }

    @Test
    void save_setsIdentityColumnAndDefaultsFiredByToScheduler() {
        repository.save(fire("remind", Instant.now(), true));
        LoomScheduleExecutionRecord stored = repository.findByTaskName("remind", 0).get(0);

        assertThat(stored.executionId()).isNotNull();
        assertThat(stored.executionId()).isPositive();
        assertThat(stored.firedBy()).isEqualTo(LoomScheduleExecutionRecord.FIRED_BY_SCHEDULER);
    }

    @Test
    void save_withNullFireTime_defaultsToNow() {
        LoomScheduleExecutionRecord r = new LoomScheduleExecutionRecord(
                null, "remind", null, 10L, true, null,
                LoomScheduleExecutionRecord.FIRED_BY_SCHEDULER);

        Instant before = Instant.now();
        repository.save(r);
        Instant after = Instant.now();

        LoomScheduleExecutionRecord stored = repository.findByTaskName("remind", 0).get(0);
        assertThat(stored.fireTime()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
    }

    @Test
    void findByTaskName_respectsLimit_andReturnsEmptyForUnknown() {
        for (int i = 0; i < 5; i++) {
            repository.save(fire("remind", Instant.now().plusSeconds(i), true));
        }

        assertThat(repository.findByTaskName("remind", 3)).hasSize(3);
        assertThat(repository.findByTaskName("does-not-exist", 10)).isEmpty();
        assertThat(repository.findByTaskName(null, 10)).isEmpty();
        assertThat(repository.findByTaskName("", 10)).isEmpty();
    }

    @Test
    void countByTaskName_returnsExactCount() {
        assertThat(repository.countByTaskName("remind")).isZero();
        repository.save(fire("remind", Instant.now(), true));
        repository.save(fire("remind", Instant.now(), false));
        repository.save(fire("other", Instant.now(), true));

        assertThat(repository.countByTaskName("remind")).isEqualTo(2);
        assertThat(repository.countByTaskName("other")).isEqualTo(1);
        assertThat(repository.countByTaskName("missing")).isZero();
    }

    @Test
    void trimTaskHistory_keepsTheNewestN_orderedByFireTimeDesc() {
        Instant base = Instant.parse("2026-07-23T00:00:00Z");
        // Insert 5 rows, oldest first → so the natural insert order is oldest.
        for (int i = 0; i < 5; i++) {
            repository.save(fire("remind", base.plusSeconds(i * 60), true));
        }
        assertThat(repository.countByTaskName("remind")).isEqualTo(5);

        int removed = repository.trimTaskHistory("remind", 2);

        assertThat(removed).isEqualTo(3);
        assertThat(repository.countByTaskName("remind")).isEqualTo(2);

        List<LoomScheduleExecutionRecord> remaining = repository.findByTaskName("remind", 0);
        // The kept rows are the 2 newest (fire_time DESC).
        assertThat(remaining.get(0).fireTime()).isEqualTo(base.plusSeconds(4 * 60));
        assertThat(remaining.get(1).fireTime()).isEqualTo(base.plusSeconds(3 * 60));
    }

    @Test
    void trimTaskHistory_withNegativeKeep_isNoOp() {
        repository.save(fire("remind", Instant.now(), true));
        int removed = repository.trimTaskHistory("remind", -1);
        assertThat(removed).isZero();
        assertThat(repository.countByTaskName("remind")).isEqualTo(1);
    }

    @Test
    void trimTaskHistory_withKeepEqualToAll_keepsEverything() {
        for (int i = 0; i < 4; i++) {
            repository.save(fire("remind", Instant.now().plusSeconds(i), true));
        }
        int removed = repository.trimTaskHistory("remind", 100);
        assertThat(removed).isZero();
        assertThat(repository.countByTaskName("remind")).isEqualTo(4);
    }

    @Test
    void deleteOlderThan_removesOnlyRowsStrictlyOlderThanCutoff() {
        Instant old = Instant.parse("2026-01-01T00:00:00Z");
        Instant recent = Instant.parse("2026-07-23T00:00:00Z");
        repository.save(fire("remind", old, true));
        repository.save(fire("remind", recent, true));

        Instant cutoff = Instant.parse("2026-06-01T00:00:00Z");
        int removed = repository.deleteOlderThan(cutoff);

        assertThat(removed).isEqualTo(1);
        assertThat(repository.countByTaskName("remind")).isEqualTo(1);
        assertThat(repository.findByTaskName("remind", 0).get(0).fireTime()).isEqualTo(recent);
    }

    @Test
    void deleteOlderThan_withNullCutoff_isNoOp() {
        repository.save(fire("remind", Instant.now(), true));
        int removed = repository.deleteOlderThan(null);
        assertThat(removed).isZero();
        assertThat(repository.countByTaskName("remind")).isEqualTo(1);
    }

    @Test
    void deleteByUserAndConversation_narrowsByNamespacedPrefix() {
        repository.save(fire("loom-sched-alice-conv-1-remind", Instant.now(), true));
        repository.save(fire("loom-sched-alice-conv-1-daily", Instant.now(), true));
        repository.save(fire("loom-sched-alice-conv-2-other", Instant.now(), true));
        repository.save(fire("loom-sched-bob-conv-1-foreign", Instant.now(), true));

        int removed = repository.deleteByUserAndConversation("alice", "conv-1");

        assertThat(removed).isEqualTo(2);
        assertThat(repository.countByTaskName("loom-sched-alice-conv-1-remind")).isZero();
        assertThat(repository.countByTaskName("loom-sched-alice-conv-2-other")).isEqualTo(1);
        assertThat(repository.countByTaskName("loom-sched-bob-conv-1-foreign")).isEqualTo(1);
    }

    @Test
    void deleteByUserAndConversation_withBlankArgs_isNoOp() {
        repository.save(fire("loom-sched-alice-conv-1-remind", Instant.now(), true));
        assertThat(repository.deleteByUserAndConversation(null, "conv-1")).isZero();
        assertThat(repository.deleteByUserAndConversation("", "conv-1")).isZero();
        assertThat(repository.deleteByUserAndConversation("alice", null)).isZero();
        assertThat(repository.deleteByUserAndConversation("alice", "")).isZero();
        assertThat(repository.countByTaskName("loom-sched-alice-conv-1-remind")).isEqualTo(1);
    }
}
