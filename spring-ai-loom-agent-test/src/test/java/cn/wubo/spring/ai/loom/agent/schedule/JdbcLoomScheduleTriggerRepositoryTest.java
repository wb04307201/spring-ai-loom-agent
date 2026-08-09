package cn.wubo.spring.ai.loom.agent.schedule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link JdbcLoomScheduleTriggerRepository} against an H2
 * in-memory database. Mirrors the DriverManagerDataSource + ensureSchema() pattern
 * used by the (now-deleted) JdbcTaskRepositoryTest in flex-schedule, but with the
 * loom-agent-owned schema and the prompt-aware record shape.
 */
class JdbcLoomScheduleTriggerRepositoryTest {

 private JdbcLoomScheduleTriggerRepository repository;

 @BeforeEach
 void setUp() {
 DriverManagerDataSource ds = new DriverManagerDataSource();
 ds.setDriverClassName("org.h2.Driver");
 ds.setUrl("jdbc:h2:mem:test-loom-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
 ds.setUsername("sa");
 ds.setPassword("");
 JdbcTemplate jdbc = new JdbcTemplate(ds);
 repository = new JdbcLoomScheduleTriggerRepository(jdbc);
 repository.ensureSchema();
 }

 private static LoomScheduleTriggerRecord sample(String taskName, String scheduleType, long nowSec) {
 return sample(taskName, scheduleType, nowSec, "alice", "conv-1");
 }

 private static LoomScheduleTriggerRecord sample(String taskName,
 String scheduleType,
 long nowSec,
 String username,
 String conversationId) {
 Instant now = Instant.now();
 return new LoomScheduleTriggerRecord(
 taskName,
 scheduleType,
 "cron".equals(scheduleType) ? "0 * * * * *" : null,
 ("fixed_delay".equals(scheduleType) || "fixed_rate".equals(scheduleType)) ? nowSec : null,
 null,
 "one_shot".equals(scheduleType) ? nowSec : null,
 "say hi from " + taskName,
 username,
 conversationId,
 false,
 now,
 now);
 }

 @Test
 void saveAndFindByName_roundTrips() {
 repository.save(sample("remind-1", "cron", 0L));

 Optional<LoomScheduleTriggerRecord> found = repository.findByName("remind-1");
 assertThat(found).isPresent();
 LoomScheduleTriggerRecord r = found.get();
 assertThat(r.taskName()).isEqualTo("remind-1");
 assertThat(r.scheduleType()).isEqualTo("cron");
 assertThat(r.cronExpression()).isEqualTo("0 * * * * *");
 assertThat(r.username()).isEqualTo("alice");
 assertThat(r.conversationId()).isEqualTo("conv-1");
 assertThat(r.prompt()).isEqualTo("say hi from remind-1");
 assertThat(r.paused()).isFalse();
 }

 @Test
 void saveIsUpsert_overwritesByName() {
 Instant createdAt = Instant.now();
 repository.save(new LoomScheduleTriggerRecord(
 "remind", "cron", "0 * * * * *",
 null, null, null,
 "first", "alice", "conv-1",
 false, createdAt, createdAt));
 repository.save(new LoomScheduleTriggerRecord(
 "remind", "cron", "0 0 * * * *",
 null, null, null,
 "second", "alice", "conv-1",
 false, createdAt, createdAt));

 List<LoomScheduleTriggerRecord> all = repository.findAll();
 assertThat(all).hasSize(1);
 assertThat(all.get(0).cronExpression()).isEqualTo("0 0 * * * *");
 assertThat(all.get(0).prompt()).isEqualTo("second");
 }

 @Test
 void findAll_returnsOrderedByTaskName() {
 repository.save(sample("charlie", "cron", 0L));
 repository.save(sample("alpha", "fixed_delay", 60L));
 repository.save(sample("bravo", "one_shot", 30L));

 List<String> names = repository.findAll().stream()
 .map(LoomScheduleTriggerRecord::taskName).toList();
 assertThat(names).containsExactly("alpha", "bravo", "charlie");
 }

 @Test
 void findByUserAndConv_isScoped() {
 repository.save(sample("a-conv1", "cron", 0L, "alice", "conv-1"));
 repository.save(sample("a-conv2", "cron", 0L, "alice", "conv-2"));
 repository.save(sample("b-conv1", "cron", 0L, "bob", "conv-1"));

 List<LoomScheduleTriggerRecord> alice = repository.findByUserAndConv("alice", "conv-1");
 assertThat(alice).extracting(LoomScheduleTriggerRecord::taskName).containsExactly("a-conv1");
 }

 @Test
 void deleteAllForConversation_onlyDeletesMatchingScope() {
 repository.save(sample("a-conv1", "cron", 0L, "alice", "conv-1"));
 repository.save(sample("a-conv2", "cron", 0L, "alice", "conv-2"));
 repository.save(sample("b-conv1", "cron", 0L, "bob", "conv-1"));

 int rows = repository.deleteAllForConversation("alice", "conv-1");
 assertThat(rows).isEqualTo(1);
 assertThat(repository.findAll())
 .extracting(LoomScheduleTriggerRecord::taskName)
 .containsExactlyInAnyOrder("a-conv2", "b-conv1");
 }

 @Test
 void deleteByName_removesSingleRow() {
 repository.save(sample("remind", "cron", 0L));
 int rows = repository.delete("remind");
 assertThat(rows).isEqualTo(1);
 assertThat(repository.exists("remind")).isFalse();
 assertThat(repository.count()).isZero();
 }

 @Test
 void count_andExistsReflectState() {
 assertThat(repository.count()).isZero();
 assertThat(repository.exists("anything")).isFalse();

 repository.save(sample("a", "cron", 0L));
 repository.save(sample("b", "cron", 0L));

 assertThat(repository.count()).isEqualTo(2);
 assertThat(repository.exists("a")).isTrue();
 assertThat(repository.exists("missing")).isFalse();
 }

 @Test
 void fixedDelayAndOneShot_persistExpressionOnlyInTheirSlot() {
 repository.save(sample("fd", "fixed_delay", 60L));
 repository.save(sample("os", "one_shot", 30L));

 LoomScheduleTriggerRecord fd = repository.findByName("fd").orElseThrow();
 assertThat(fd.intervalSeconds()).isEqualTo(60L);
 assertThat(fd.cronExpression()).isNull();
 assertThat(fd.oneShotDelaySeconds()).isNull();

 LoomScheduleTriggerRecord os = repository.findByName("os").orElseThrow();
 assertThat(os.oneShotDelaySeconds()).isEqualTo(30L);
 assertThat(os.intervalSeconds()).isNull();
 assertThat(os.cronExpression()).isNull();
 }
}
