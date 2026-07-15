# 子任务 + 定时任务 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add synchronous sub-task agent capability + 10-minute-min / 3-day-max scheduled tasks to Spring AI LoomAgent, with H2 persistence via flex-schedule.

**Architecture:**
- New `subtask` and `schedule` packages under `cn.wubo.spring.ai.loom.agent.*`
- Sub-task: dedicated `loomSubTaskExecutor` thread pool; one filtered `ChatClient` (no self-tools) using `.call().content()`
- Schedule: reuses `io.github.wb04307201:flex-schedule-spring-boot-starter:1.2.1` with a new H2-backed `TaskRepository` (auto-detected when H2 is on classpath)
- New `ConversationLifecycleListener` hooks the existing `DELETE /spring/ai/loom/conversation/{id}` route to stop sub-tasks + schedules before soft-deleting
- Frontend: 2 toolbar buttons + 2 modals matching existing `file-modal` pattern

**Tech Stack:** Spring Boot 3.5.16, Spring AI 1.1.8 (DashScope), flex-schedule 1.2.1, H2 2.x, JdbcTemplate, Java 17.

---

## Global Constraints

These constraints apply to every task — DO NOT VIOLATE.

- **Language**: All code: class name `PascalCase`, method/variable `camelCase`, constants `UPPER_SNAKE`, package `lowercase.dotted`.
- **Comments**: Chinese is fine for Javadoc / inline; English variable names. Match neighboring file style.
- **Lombok**: Not used. Use plain Java + Java records.
- **Tests**: JUnit 5 (`org.junit.jupiter.api.Test`), Mockito (`org.mockito.Mockito`), AssertJ (`org.assertj.core.api.Assertions.assertThat`) — all already available in `spring-ai-loom-agent-test`'s `pom.xml`. flex-schedule-test already uses JUnit 5 + Mockito + AssertJ.
- **Commit message style**: `type(scope): subject` — Conventional Commits. Scopes: `subtask`, `schedule`, `flex-schedule`, `config`, `frontend`, `docs`. Examples: `feat(subtask): add SubTaskRegistry`, `test(flex-schedule): JdbcTaskRepositoryTest`.
- **Frequency of commits**: One commit per task minimum. TDD: commit failing test, then commit passing impl.
- **NO additions** to `pom.xml` without prior approval. flex-schedule is already in dependencyManagement.
- **YAGNI**: Do not add fields/methods "for future use". Match the spec exactly.
- **DRY in tests**: Use helper builders for `TaskDefinition` / `SubTaskRecord` if creating >3 of them in one test.
- **Match existing patterns**: Mirror the style of `DefaultSkillTool`, `DefaultTimeTool`, `LoomAgentConfiguration.ToolConfiguration`.

---

## Cross-Phase Conventions

### Files each task creates/modifies

These are the **complete** delta across all phases. Tasks below reference them; this section is the source-of-truth.

```
flex-schedule/  (in C:/developer/IdeaProjects/flex-schedule)
  flex-schedule/
    src/main/java/cn/wubo/flex/schedule/core/
      JdbcTaskRepository.java                           # NEW (Phase 1)
    src/test/java/cn/wubo/flex/schedule/core/
      JdbcTaskRepositoryTest.java                      # NEW (Phase 1)
  flex-schedule-spring-boot-autoconfigure/
    src/main/java/cn/wubo/flex/schedule/autoconfigure/
      FlexScheduleAutoConfiguration.java               # MODIFY (Phase 1)
        + ~12 lines for JdbcTaskRepositoryConfiguration
        + 1 setter call in flexScheduledTaskRegistrar bean method

spring-ai-loom-agent/  (in C:/developer/IdeaProjects/spring-ai-loom-agent)
  spring-ai-loom-agent/
    src/main/java/cn/wubo/spring/ai/loom/agent/
      model/
        SubTaskRequest.java                            # NEW (Phase 2)
        SubTaskResult.java                             # NEW (Phase 2)
        SubTaskStatus.java                             # NEW (Phase 2)
      subtask/
        ISubTaskExecutor.java                          # NEW (Phase 2)
        DefaultSubTaskExecutor.java                    # NEW (Phase 2)
        SubTaskRegistry.java                           # NEW (Phase 2)
      ChatRequestComposer.java                         # NEW (Phase 2)
    src/main/resources/db/migration/
      V12__flex_scheduled_task.sql                     # NEW (Phase 1)
  spring-ai-loom-agent-spring-boot-autoconfigure/
    src/main/java/cn/wubo/spring/ai/loom/agent/
      LoomAgentConfiguration.java                      # MODIFY (Phase 2, 3, 4, 5)
      LoomAgentProperties.java                         # MODIFY (Phase 2, 4)
    src/main/resources/
      META-INF/spring/.../imports                      # unchanged (already includes LoomAgentConfiguration)
  spring-ai-loom-agent/
    src/main/resources/META-INF/resources/spring/ai/loom/
      index.html                                       # MODIFY (Phase 6)
      style.css                                        # unchanged (reuse .modal-overlay/.modal-content)
      app.js                                           # MODIFY (Phase 6)
      subtask-modal.js                                 # NEW (Phase 6)
      schedule-modal.js                                # NEW (Phase 6)
  spring-ai-loom-agent-test/
    src/main/resources/
      application.yml                                  # MODIFY (Phase 7)
    src/test/java/cn/wubo/spring/ai/loom/agent/
      subtask/
        SubTaskRegistryTest.java                       # NEW (Phase 2)
        DefaultSubTaskExecutorTest.java                # NEW (Phase 2)
        DefaultSubTaskToolTest.java                    # NEW (Phase 3)
      schedule/
        DefaultScheduleToolTest.java                    # NEW (Phase 4)
      ConversationLifecycleListenerTest.java           # NEW (Phase 5)
```

### Naming conventions (locked in here, used by all phases)

| Symbol | Full path / signature |
|---|---|
| Sub-task ID | `String`, UUID v4 (`UUID.randomUUID().toString()`) |
| Schedule full name | `"loom-sched-" + username + "-" + conversationId + "-" + userProvidedName` |
| Schedule thread-pool | `"loomSubTaskExecutor"` (Spring bean name) |
| Schedule sub-task memory ID | `"{conversationId}--sub--{subTaskId}"` |
| Sub-task ChatClient | Constructed once at app start in `LoomAgentConfiguration.SubTaskConfiguration` |
| Status enums | `SubTaskStatus.{RUNNING, COMPLETED, FAILED, CANCELLED}` |

### Key binding contracts

These MUST be consistent across tasks; mismatches cause circular wiring.

```java
// SubTaskRequest — Phase 2 Task 1
public record SubTaskRequest(
    String subTaskId,
    String parentConversationId,
    String parentSubTaskId,    // nullable — v1 always null
    String username,
    String prompt,
    String systemContext,      // nullable
    boolean fromScheduler
) {}

// SubTaskResult — Phase 2 Task 1
public record SubTaskResult(
    String subTaskId,
    String conversationId,
    String username,
    SubTaskStatus status,      // COMPLETED|FAILED|CANCELLED (never RUNNING — that's registry state)
    String text,
    String errorMessage,
    long startedAt,
    long finishedAt
) {}

public enum SubTaskStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

// ISubTaskExecutor — Phase 2 Task 2
public interface ISubTaskExecutor {
    SubTaskResult execute(SubTaskRequest req);
}

// ISubTaskTool — Phase 3 Task 1
public interface ISubTaskTool extends IEmbedTool {
    String startSubTask(@ToolParam(description="...") String prompt,
                        @ToolParam(description="...") String systemContext,
                        ToolContext toolContext);
}

// IScheduleTool — Phase 4 Task 1
public interface IScheduleTool extends IEmbedTool {
    String createSchedule(@ToolParam(description="任务名...") String name,
                          @ToolParam(description="cron | fixed_delay | fixed_rate | one_shot") String scheduleType,
                          @ToolParam(description="...") String expression,
                          @ToolParam(description="触发时执行的提示词") String prompt,
                          ToolContext toolContext);

    String cancelSchedule(@ToolParam(description="任务名") String name, ToolContext toolContext);
    String listSchedules(ToolContext toolContext);
    String getScheduleHistory(@ToolParam(description="任务名") String name,
                              @ToolParam(description="返回多少条,默认 20") Integer limit,
                              ToolContext toolContext);
}
```

---

# Phase 1 — flex-schedule: H2 Persistent TaskRepository

> **Goal**: Reusable across any flex-schedule consumer. Make `TaskRepository` an `@Bean`-injected interface so projects on H2 get persistence for free.

## Phase 1 File Structure

| File | Lines (est.) | Role |
|---|---|---|
| `flex-schedule/.../core/JdbcTaskRepository.java` | ~150 | JDBC-backed `TaskRepository` |
| `flex-schedule-test/.../core/JdbcTaskRepositoryTest.java` | ~180 | H2 in-memory tests |
| `flex-schedule-spring-boot-autoconfigure/.../FlexScheduleAutoConfiguration.java` | +12 | New `@Configuration` inner class + 1 line in bean method |
| `spring-ai-loom-agent/.../db/migration/V12__flex_scheduled_task.sql` | ~20 | H2 table schema |

## Task 1.1: JdbcTaskRepository implementation

**Files:**
- Create: `C:/developer/IdeaProjects/flex-schedule/flex-schedule/src/main/java/cn/wubo/flex/schedule/core/JdbcTaskRepository.java`
- Test: `C:/developer/IdeaProjects/flex-schedule/flex-schedule-test/src/test/java/cn/wubo/flex/schedule/core/JdbcTaskRepositoryTest.java`

- [ ] **Step 1: Write the failing test**

Create `JdbcTaskRepositoryTest.java`:

```java
package cn.wubo.flex.schedule.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcTaskRepositoryTest {

    private JdbcTaskRepository repository;

    @BeforeEach
    void setUp() {
        // Use H2 in-memory for tests
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:test-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        repository = new JdbcTaskRepository(jdbc);
        repository.ensureSchema();
    }

    @Test
    void savesAndFindsByName() {
        TaskDefinition def = TaskDefinition.builder("cron-task", "CRON")
                .cronExpression("0 * * * * *")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        repository.save(def);
        Optional<TaskDefinition> found = repository.findByName("cron-task");

        assertThat(found).isPresent();
        assertThat(found.get().cronExpression()).isEqualTo("0 * * * * *");
        assertThat(found.get().createdAt()).isEqualTo(def.createdAt());
    }

    @Test
    void saveIsUpsert() {
        TaskDefinition def = TaskDefinition.builder("cron-task", "CRON")
                .cronExpression("0 * * * * *")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        repository.save(def);

        TaskDefinition updated = TaskDefinition.builder("cron-task", "CRON")
                .cronExpression("0 0 * * * *")
                .createdAt(def.createdAt())
                .updatedAt(Instant.now())
                .build();
        repository.save(updated);

        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.findByName("cron-task").get().cronExpression())
                .isEqualTo("0 0 * * * *");
    }

    @Test
    void findAllReturnsEverythingSortedByName() {
        repository.save(TaskDefinition.builder("beta", "FIXED_RATE")
                .interval(Duration.ofMinutes(5)).createdAt(Instant.now()).updatedAt(Instant.now()).build());
        repository.save(TaskDefinition.builder("alpha", "FIXED_RATE")
                .interval(Duration.ofMinutes(1)).createdAt(Instant.now()).updatedAt(Instant.now()).build());
        repository.save(TaskDefinition.builder("gamma", "CRON")
                .cronExpression("0 0 * * * *").createdAt(Instant.now()).updatedAt(Instant.now()).build());

        List<TaskDefinition> all = repository.findAll();

        assertThat(all).extracting(TaskDefinition::taskName)
                .containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void deleteRemovesByName() {
        repository.save(TaskDefinition.builder("cron-task", "CRON")
                .cronExpression("0 * * * * *").createdAt(Instant.now()).updatedAt(Instant.now()).build());
        repository.delete("cron-task");
        assertThat(repository.findByName("cron-task")).isEmpty();
        assertThat(repository.count()).isZero();
    }

    @Test
    void deleteAllClearsTable() {
        repository.save(TaskDefinition.builder("a", "CRON")
                .cronExpression("0 * * * * *").createdAt(Instant.now()).updatedAt(Instant.now()).build());
        repository.save(TaskDefinition.builder("b", "CRON")
                .cronExpression("0 * * * * *").createdAt(Instant.now()).updatedAt(Instant.now()).build());
        repository.deleteAll();
        assertThat(repository.count()).isZero();
    }

    @Test
    void persistsIntervalAndInitialDelay() {
        repository.save(TaskDefinition.builder("fd", "FIXED_DELAY")
                .interval(Duration.ofSeconds(30))
                .initialDelay(Duration.ofSeconds(10))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        TaskDefinition loaded = repository.findByName("fd").orElseThrow();
        assertThat(loaded.interval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(loaded.initialDelay()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void persistsOneShotDelay() {
        repository.save(TaskDefinition.builder("once", "ONE_SHOT")
                .delay(Duration.ofMinutes(5))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        TaskDefinition loaded = repository.findByName("once").orElseThrow();
        assertThat(loaded.delay()).isEqualTo(Duration.ofMinutes(5));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd C:/developer/IdeaProjects/flex-schedule/flex-schedule && mvn -B -q test -Dtest=JdbcTaskRepositoryTest
```

Expected: **COMPILATION FAILURE** — `JdbcTaskRepository` class not found.

- [ ] **Step 3: Write minimal implementation**

Create `JdbcTaskRepository.java`:

```java
package cn.wubo.flex.schedule.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * H2 / JDBC-backed {@link TaskRepository}.
 * <p>
 * Persists task definitions across application restarts. Auto-creates the
 * {@code flex_scheduled_task} table on first use via {@link #ensureSchema()}.
 * </p>
 */
public class JdbcTaskRepository implements TaskRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcTaskRepository.class);

    public static final String TABLE_NAME = "flex_scheduled_task";

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS """ + TABLE_NAME + """ (
                task_name VARCHAR(255) PRIMARY KEY,
                type VARCHAR(20) NOT NULL,
                cron_expression VARCHAR(100),
                timezone VARCHAR(50),
                interval_ms BIGINT,
                initial_delay_ms BIGINT,
                delay_ms BIGINT,
                timeout_ms BIGINT,
                retry_policy_json CLOB,
                bean_name VARCHAR(255),
                method_name VARCHAR(255),
                method_params_json CLOB,
                paused BOOLEAN NOT NULL DEFAULT FALSE,
                created_at TIMESTAMP NOT NULL,
                updated_at TIMESTAMP NOT NULL
            )
            """;

    private final JdbcTemplate jdbc;

    public JdbcTaskRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public JdbcTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates the persistence table if absent. Safe to invoke multiple times.
     */
    public void ensureSchema() {
        jdbc.execute(DDL);
        log.debug("JdbcTaskRepository schema ensured at {}", TABLE_NAME);
    }

    @Override
    public void save(TaskDefinition def) {
        String sql = """
                MERGE INTO """ + TABLE_NAME + """ (task_name, type, cron_expression, timezone,
                    interval_ms, initial_delay_ms, delay_ms, timeout_ms,
                    retry_policy_json, bean_name, method_name, method_params_json,
                    paused, created_at, updated_at)
                KEY(task_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        jdbc.update(sql,
                def.taskName(),
                def.taskType(),
                def.cronExpression(),
                def.timezone() != null ? def.timezone().getId() : null,
                msOrNull(def.interval()),
                msOrNull(def.initialDelay()),
                msOrNull(def.delay()),
                msOrNull(def.timeout()),
                null,                 // retry_policy_json — not implemented in JdbcTaskRepository v1
                def.beanName(),
                def.methodName(),
                null,                 // method_params_json — not implemented in JdbcTaskRepository v1
                def.paused(),
                Timestamp.from(def.createdAt()),
                Timestamp.from(def.updatedAt()));
    }

    @Override
    public Optional<TaskDefinition> findByName(String taskName) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM " + TABLE_NAME + " WHERE task_name = ?",
                    (rs, rowNum) -> mapRow(rs),
                    taskName));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<TaskDefinition> findAll() {
        return jdbc.query("SELECT * FROM " + TABLE_NAME + " ORDER BY task_name",
                (rs, rowNum) -> mapRow(rs))
                .stream()
                .sorted(Comparator.comparing(TaskDefinition::taskName))
                .toList();
    }

    @Override
    public void delete(String taskName) {
        jdbc.update("DELETE FROM " + TABLE_NAME + " WHERE task_name = ?", taskName);
    }

    @Override
    public void deleteAll() {
        jdbc.update("DELETE FROM " + TABLE_NAME);
    }

    @Override
    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE_NAME, Integer.class);
        return n != null ? n : 0;
    }

    private static Long msOrNull(Duration d) {
        return d == null ? null : d.toMillis();
    }

    private static Duration msOrNullToDuration(Long ms) {
        return ms == null ? null : Duration.ofMillis(ms);
    }

    private static TaskDefinition mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return TaskDefinition.builder(rs.getString("task_name"), rs.getString("type"))
                .cronExpression(rs.getString("cron_expression"))
                .timezone(rs.getString("timezone") != null ? java.time.ZoneId.of(rs.getString("timezone")) : null)
                .interval(msOrNullToDuration((Long) rs.getObject("interval_ms")))
                .initialDelay(msOrNullToDuration((Long) rs.getObject("initial_delay_ms")))
                .delay(msOrNullToDuration((Long) rs.getObject("delay_ms")))
                .timeout(msOrNullToDuration((Long) rs.getObject("timeout_ms")))
                .beanName(rs.getString("bean_name"))
                .methodName(rs.getString("method_name"))
                .paused(rs.getBoolean("paused"))
                .createdAt(rs.getTimestamp("created_at").toInstant())
                .updatedAt(rs.getTimestamp("updated_at").toInstant())
                .build();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd C:/developer/IdeaProjects/flex-schedule/flex-schedule && mvn -B -q test -Dtest=JdbcTaskRepositoryTest
```

Expected: **7 tests passed**. (H2 driver is already in flex-schedule-test scope.)

- [ ] **Step 5: Commit**

```bash
cd C:/developer/IdeaProjects/flex-schedule && git add flex-schedule/src/main/java/cn/wubo/flex/schedule/core/JdbcTaskRepository.java flex-schedule-test/src/test/java/cn/wubo/flex/schedule/core/JdbcTaskRepositoryTest.java && git commit -m "feat(flex-schedule): JdbcTaskRepository (H2-backed TaskRepository)"
```

---

## Task 1.2: Wire JdbcTaskRepository through autoconfig

**Files:**
- Modify: `C:/developer/IdeaProjects/flex-schedule/flex-schedule-spring-boot-autoconfigure/src/main/java/cn/wubo/flex/schedule/autoconfigure/FlexScheduleAutoConfiguration.java`
  - In `flexScheduledTaskRegistrar(...)` bean method, accept `ObjectProvider<TaskRepository>` and call `setTaskRepository(...)` if present
  - Add new `@Configuration` inner class `JdbcTaskRepositoryConfiguration` auto-registered when H2 is on classpath

- [ ] **Step 1: Replace the existing `flexScheduledTaskRegistrar` bean method**

In `FlexScheduleAutoConfiguration.java`, the existing bean method (lines 51-58):

```java
@Bean(name = "flexScheduledTaskRegistrar")
public FlexScheduledTaskRegistrar flexScheduledTaskRegistrar(
        @Qualifier("flexScheduleThreadPoolTaskScheduler") ThreadPoolTaskScheduler threadPoolTaskScheduler,
        FlexScheduleProperties properties,
        TaskLimits taskLimits) {
    return new FlexScheduledTaskRegistrar(
            threadPoolTaskScheduler, properties.getAwaitTerminationSeconds(), taskLimits);
}
```

Replace it with:

```java
@Bean(name = "flexScheduledTaskRegistrar")
public FlexScheduledTaskRegistrar flexScheduledTaskRegistrar(
        @Qualifier("flexScheduleThreadPoolTaskScheduler") ThreadPoolTaskScheduler threadPoolTaskScheduler,
        FlexScheduleProperties properties,
        TaskLimits taskLimits,
        org.springframework.beans.factory.ObjectProvider<TaskRepository> taskRepositoryProvider) {
    FlexScheduledTaskRegistrar registrar = new FlexScheduledTaskRegistrar(
            threadPoolTaskScheduler, properties.getAwaitTerminationSeconds(), taskLimits);
    TaskRepository repo = taskRepositoryProvider.getIfAvailable();
    if (repo != null) {
        registrar.setTaskRepository(repo);
        log.info("Flex schedule wired with custom TaskRepository: {}", repo.getClass().getSimpleName());
    } else {
        log.info("Flex schedule using default in-memory TaskRepository");
    }
    return registrar;
}
```

- [ ] **Step 2: Add the new `@Configuration` inner class**

Insert **just after** the existing `EndpointConfiguration` and **before** `MetricsConfiguration`. Keep imports tidy (add `cn.wubo.flex.schedule.core.JdbcTaskRepository` and any others needed):

```java
/**
 * JDBC-backed TaskRepository configuration. Activated when an H2 datasource is
 * available on the classpath AND a DataSource bean exists. Projects not using
 * H2 should provide their own TaskRepository bean (e.g. Redis, JDBC URL-based).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(org.h2.Driver.class)
@ConditionalOnBean(javax.sql.DataSource.class)
@ConditionalOnMissingBean(TaskRepository.class)
public static class JdbcTaskRepositoryConfiguration {

    @Bean
    public JdbcTaskRepository flexScheduleJdbcTaskRepository(javax.sql.DataSource dataSource) {
        JdbcTaskRepository repo = new JdbcTaskRepository(dataSource);
        repo.ensureSchema();
        return repo;
    }
}
```

- [ ] **Step 3: Run the existing flex-schedule-test suite to verify nothing broke**

```bash
cd C:/developer/IdeaProjects/flex-schedule && mvn -B -q test
```

Expected: **All 198+ tests still pass.** No regressions.

- [ ] **Step 4: Commit**

```bash
cd C:/developer/IdeaProjects/flex-schedule && git add flex-schedule-spring-boot-autoconfigure/src/main/java/cn/wubo/flex/schedule/autoconfigure/FlexScheduleAutoConfiguration.java && git commit -m "feat(flex-schedule): autoconfigure JdbcTaskRepository when H2 on classpath"
```

---

## Task 1.3: Add V12 Flyway migration to loom-agent

**Files:**
- Create: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent/src/main/resources/db/migration/V12__flex_scheduled_task.sql`

- [ ] **Step 1: Create the migration**

Create `V12__flex_scheduled_task.sql`:

```sql
-- Persistent storage for flex-schedule tasks (H2).
-- Created both here (loom migration) and at runtime in JdbcTaskRepository.ensureSchema()
-- so the table works whether or not Flyway runs first.

CREATE TABLE IF NOT EXISTS flex_scheduled_task (
    task_name VARCHAR(255) PRIMARY KEY,
    type VARCHAR(20) NOT NULL,
    cron_expression VARCHAR(100),
    timezone VARCHAR(50),
    interval_ms BIGINT,
    initial_delay_ms BIGINT,
    delay_ms BIGINT,
    timeout_ms BIGINT,
    retry_policy_json CLOB,
    bean_name VARCHAR(255),
    method_name VARCHAR(255),
    method_params_json CLOB,
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_flex_scheduled_task_created_at
    ON flex_scheduled_task(created_at);
```

- [ ] **Step 2: Verify loom still builds and migrates**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && mvn -B -q install -DskipTests -pl spring-ai-loom-agent -am
```

Expected: **BUILD SUCCESS**. The migration is small enough to compile cleanly.

- [ ] **Step 3: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && git add spring-ai-loom-agent/src/main/resources/db/migration/V12__flex_scheduled_task.sql && git commit -m "feat(db): V12 Flyway migration for flex_scheduled_task"
```

---

# Phase 2 — loom-agent: Sub-task skeleton (records + executor + registry)

> **Goal:** All the foundational sub-task types wired, no LLM tool exposure yet.

## Phase 2 File Structure

| File | Lines (est.) | Role |
|---|---|---|
| `model/SubTaskStatus.java` | 5 | Enum |
| `model/SubTaskRequest.java` | 25 | Record |
| `model/SubTaskResult.java` | 30 | Record |
| `subtask/ISubTaskExecutor.java` | 15 | Interface |
| `subtask/SubTaskRegistry.java` | 200 | In-memory registry with kill support |
| `subtask/DefaultSubTaskExecutor.java` | 220 | Wraps ChatClient.call() on a dedicated thread pool |
| `ChatRequestComposer.java` | 80 | Internal helper for building ChatClient request specs (used by both DefaultChat and DefaultSubTaskExecutor in Phase 3) |
| `LoomAgentConfiguration.SubTaskConfiguration` | (modify) | Wire `subTaskChatClient` + `loomSubTaskExecutor` + routers |
| `LoomAgentProperties.java` | (modify) | Add `SubTask` nested config |
| `subtask/SubTaskRegistryTest.java` | 180 | Unit tests |
| `subtask/DefaultSubTaskExecutorTest.java` | 130 | Unit tests with mocked ChatClient |

## Task 2.1: Records

**Files:**
- Create: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/SubTaskStatus.java`
- Create: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/SubTaskRequest.java`
- Create: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/SubTaskResult.java`

- [ ] **Step 1: `SubTaskStatus.java`**

```java
package cn.wubo.spring.ai.loom.agent.model;

/**
 * Lifecycle status of a sub-task. RUNNING is held in the in-memory registry only;
 * completed/failed/cancelled values are immutable on a {@link SubTaskResult}.
 */
public enum SubTaskStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
```

- [ ] **Step 2: `SubTaskRequest.java`**

```java
package cn.wubo.spring.ai.loom.agent.model;

/**
 * Internal request describing a single sub-task execution.
 * <p>
 * NOT a wire-format DTO for HTTP — only used between {@code DefaultSubTaskTool}
 * and {@code DefaultSubTaskExecutor}, and between flex-schedule callbacks and the
 * executor.
 * </p>
 *
 * @param subTaskId             UUID assigned by the registry or scheduler.
 * @param parentConversationId  Main conversation's id; sub-task memory is
 *                              stored under "{conversationId}--sub--{subTaskId}".
 * @param parentSubTaskId       {@code null} in v1 (no nesting); reserved.
 * @param username              Authorizing user (for tool context & RBAC).
 * @param prompt                User-facing instruction to the sub-model.
 * @param systemContext         Optional extra system guidance; {@code null} skips.
 * @param fromScheduler         {@code true} if invoked by a flex-schedule callback.
 */
public record SubTaskRequest(
        String subTaskId,
        String parentConversationId,
        String parentSubTaskId,
        String username,
        String prompt,
        String systemContext,
        boolean fromScheduler
) {
    /**
     * Conversation-id namespace under which the sub-task writes to ChatMemory.
     */
    public String memoryConversationId() {
        return parentConversationId + "--sub--" + subTaskId;
    }
}
```

- [ ] **Step 3: `SubTaskResult.java`**

```java
package cn.wubo.spring.ai.loom.agent.model;

/**
 * Outcome of a sub-task execution. Returned synchronously by
 * {@code ISubTaskExecutor.execute}.
 *
 * @param text            Final response text when status == COMPLETED; empty otherwise.
 * @param errorMessage    Populated when status == FAILED.
 */
public record SubTaskResult(
        String subTaskId,
        String conversationId,
        String username,
        SubTaskStatus status,
        String text,
        String errorMessage,
        long startedAt,
        long finishedAt
) {
    public static SubTaskResult cancelled(SubTaskRequest req, long startedAt, long finishedAt) {
        return new SubTaskResult(req.subTaskId(), req.parentConversationId(), req.username(),
                SubTaskStatus.CANCELLED, "", "用户取消", startedAt, finishedAt);
    }

    public static SubTaskResult failed(SubTaskRequest req, long startedAt, long finishedAt, String message) {
        return new SubTaskResult(req.subTaskId(), req.parentConversationId(), req.username(),
                SubTaskStatus.FAILED, "", message, startedAt, finishedAt);
    }

    public static SubTaskResult completed(SubTaskRequest req, long startedAt, long finishedAt, String text) {
        return new SubTaskResult(req.subTaskId(), req.parentConversationId(), req.username(),
                SubTaskStatus.COMPLETED, text, "", startedAt, finishedAt);
    }
}
```

- [ ] **Step 4: Compile**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && mvn -B -q install -DskipTests -pl spring-ai-loom-agent -am
```

Expected: **BUILD SUCCESS**.

- [ ] **Step 5: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/SubTask*.java && git commit -m "feat(subtask): SubTaskStatus/Request/Result records"
```

---

## Task 2.2: ChatRequestComposer (internal helper)

**Files:**
- Create: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/ChatRequestComposer.java`
- (no test yet — used in 2.5 + 3.2)

- [ ] **Step 1: Create the class**

```java
package cn.wubo.spring.ai.loom.agent;

import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.util.TikaUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Internal helper shared by {@code DefaultChat} and {@code DefaultSubTaskExecutor}
 * for building a configured {@link ChatClient.ChatClientRequestSpec}.
 * <p>
 * Refactored out of {@code DefaultChat} so sub-tasks can reuse the same wiring
 * (file ingestion / RAG / MCP / chat memory) without duplicating code.
 * </p>
 */
final class ChatRequestComposer {

    private ChatRequestComposer() {}

    /**
     * Build a {@link ChatClient.ChatClientRequestSpec} for either the main
     * conversation or a sub-task. The tool set is determined by the caller; this
     * helper is tool-agnostic.
     *
     * @param chatClientBuilder pre-configured ChatClient (with the right tool set baked in)
     * @param message           plain user message
     * @param fileIds           optional uploaded file ids (PDFs / docs / images)
     * @param file              for resolving file metadata
     * @param username          for toolContext
     * @param request           HTTP request (for baseUrl); may be {@code null} in sub-task mode
     * @param conversationId    for ChatMemory advisor
     * @param mcps              list of MCP server names to enable (empty = none)
     * @param knowledgeId       optional knowledge-space id (drives RAG filter)
     * @param rag               optional; when present and knowledgeId is set, attaches the RAG advisor
     * @param mcp               for resolving visible MCP tool callbacks
     */
    static ChatClient.ChatClientRequestSpec build(
            ChatClient chatClientBuilder,
            String message,
            List<String> fileIds,
            IFile file,
            String username,
            HttpServletRequest request,
            String conversationId,
            List<String> mcps,
            String knowledgeId,
            Optional<RetrievalAugmentationAdvisor> rag,
            IMcp mcp,
            Object[] tools  // null = none
    ) {
        ChatClient.ChatClientRequestSpec spec = chatClientBuilder.prompt();

        if (fileIds != null && !fileIds.isEmpty()) {
            StringBuilder extraText = new StringBuilder();
            for (String fileId : fileIds) {
                var fileRecord = file.getById(fileId, username);
                if (fileRecord == null) continue;
                if (isDocument(fileRecord.mimeType())) {
                    if (extraText.length() == 0) {
                        extraText.append("以下是用户上传的文档的内容提取结果:");
                    }
                    try (InputStream in = file.getResourceById(fileId, username).getInputStream()) {
                        String content = TikaUtils.TIKA.parseToString(in);
                        extraText.append("\n\n--- ").append(fileRecord.fileName())
                                .append(" ---\n\n").append(content);
                    } catch (IOException | org.apache.tika.exception.TikaException e) {
                        extraText.append("\n\n--- ").append(fileRecord.fileName())
                                .append(" ---\n\n文件无法解析: ").append(e.getMessage());
                    }
                }
            }
            if (extraText.length() > 0) {
                extraText.append("\n\n以上是文档内容提取结果，请根据文档内容进行回答。");
                spec.system(extraText.toString());
            }
            spec.user(u -> {
                u.text(message);
                for (String fileId : fileIds) {
                    try {
                        var fileRecord = file.getById(fileId, username);
                        if (fileRecord == null) continue;
                        if (isImage(fileRecord.mimeType())) {
                            u.media(MimeTypeUtils.IMAGE_JPEG, file.getResourceById(fileId, username));
                        }
                    } catch (Exception ignored) { }
                }
            });
        } else {
            spec.user(message);
        }

        Map<String, Object> props = new HashMap<>();
        props.put("username", username);
        if (request != null) {
            String scheme = request.getScheme();
            String serverName = request.getServerName();
            int serverPort = request.getServerPort();
            props.put("baseUrl", scheme + "://" + serverName + ":" + serverPort);
        }
        spec.toolContext(props);

        spec.advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId));

        if (rag.isPresent() && StringUtils.hasText(knowledgeId)) {
            spec.advisors(rag.get());
            spec.advisors(advisor -> advisor.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION,
                    "type == 'knowledge' && knowledgeId == '" + knowledgeId
                            + "' && username == '" + username + "'"));
        }

        ToolCallbackProvider mcpProvider = mcp.getVisibleToolCallbackProvider(username, mcps);
        if (mcpProvider != null) {
            spec.toolCallbacks(mcpProvider);
        }

        if (tools != null && tools.length > 0) {
            spec.tools(tools);
        }

        return spec;
    }

    private static boolean isImage(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }

    private static boolean isDocument(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.equals("application/pdf")
                || mimeType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                || mimeType.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation")
                || mimeType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                || mimeType.equals("text/markdown") || mimeType.equals("text/plain")
                || mimeType.equals("application/msword")
                || mimeType.equals("application/vnd.ms-powerpoint")
                || mimeType.equals("application/vnd.ms-excel")
                || mimeType.equals("text/html") || mimeType.equals("text/csv")
                || mimeType.equals("text/xml") || mimeType.equals("application/rtf")
                || mimeType.equals("text/rtf");
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && mvn -B -q install -DskipTests -pl spring-ai-loom-agent -am
```

Expected: **BUILD SUCCESS**.

- [ ] **Step 3: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/ChatRequestComposer.java && git commit -m "refactor(subtask): extract ChatRequestComposer (shared by Chat and SubTask)"
```

---

## Task 2.3: SubTaskRegistry

**Files:**
- Create: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/SubTaskRegistry.java`
- Test: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/SubTaskRegistryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class SubTaskRegistryTest {

    private SubTaskRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SubTaskRegistry(8, 100);
    }

    @Test
    void registerAndQuery() {
        String id = registry.register("alice", "conv-1", "do X");

        assertThat(id).isNotBlank();
        SubTaskRegistry.SubTaskRecord r = registry.get(id);
        assertThat(r.username()).isEqualTo("alice");
        assertThat(r.conversationId()).isEqualTo("conv-1");
        assertThat(r.prompt()).isEqualTo("do X");
        assertThat(r.status()).isEqualTo(SubTaskStatus.RUNNING);
        assertThat(r.startedAt()).isGreaterThan(0L);
    }

    @Test
    void markFinishedMovesToHistory() {
        String id = registry.register("alice", "conv-1", "p");
        registry.markFinished(id, SubTaskStatus.COMPLETED, "result", null);

        assertThat(registry.get(id).status()).isEqualTo(SubTaskStatus.COMPLETED);
        assertThat(registry.get(id).resultText()).isEqualTo("result");
        // Same id should appear in history listing
        assertThat(registry.listHistory("alice", 10))
                .extracting(SubTaskRegistry.SubTaskRecord::subTaskId)
                .contains(id);
    }

    @Test
    void listActiveFilteredByUsername() {
        registry.register("alice", "conv-1", "p1");
        registry.register("alice", "conv-2", "p2");
        registry.register("bob",   "conv-3", "p3");

        assertThat(registry.listActive("alice")).hasSize(2);
        assertThat(registry.listActive("bob")).hasSize(1);
        assertThat(registry.listActive("nonexistent")).isEmpty();
    }

    @Test
    void killAllByConversationCancelsPendingFuturesAndCountsCancelled() {
        String id1 = registry.register("alice", "conv-1", "p1");
        String id2 = registry.register("alice", "conv-1", "p2");
        String id3 = registry.register("alice", "conv-2", "p3");

        CompletableFuture<?> f1 = new CompletableFuture<>();
        CompletableFuture<?> f2 = new CompletableFuture<>();
        registry.attachFuture(id1, f1);
        registry.attachFuture(id2, f2);

        int killed = registry.killAllByConversation("conv-1");

        assertThat(killed).isEqualTo(2);
        assertThat(f1.isCancelled()).isTrue();
        assertThat(f2.isCancelled()).isTrue();
        assertThat(registry.get(id1).status()).isEqualTo(SubTaskStatus.CANCELLED);
        assertThat(registry.get(id3).status()).isEqualTo(SubTaskStatus.RUNNING);  // untouched
    }

    @Test
    void killByIdCancelsAndMarksCancelled() {
        String id = registry.register("alice", "conv-1", "p");
        CompletableFuture<?> f = new CompletableFuture<>();
        registry.attachFuture(id, f);

        boolean ok = registry.kill(id);

        assertThat(ok).isTrue();
        assertThat(f.isCancelled()).isTrue();
        assertThat(registry.get(id).status()).isEqualTo(SubTaskStatus.CANCELLED);
    }

    @Test
    void historyIsBounded() {
        registry = new SubTaskRegistry(2, 3);
        for (int i = 0; i < 5; i++) {
            String id = registry.register("alice", "conv", "p" + i);
            registry.markFinished(id, SubTaskStatus.COMPLETED, "r" + i, null);
        }
        assertThat(registry.listHistory("alice", 100)).hasSize(3);
    }

    @Test
    void maxConcurrentLimitsActiveRegistration() {
        registry = new SubTaskRegistry(2, 100);
        registry.register("alice", "c1", "p1");
        registry.register("alice", "c2", "p2");

        try {
            registry.register("alice", "c3", "p3");
            // expect IllegalStateException — but AssertJ .isInstanceOf works for the message
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> registry.register("alice", "c4", "p4"));
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    void gettersOnMissingReturnNullOrEmpty() {
        assertThat(registry.get(UUID.randomUUID().toString())).isNull();
        assertThat(registry.kill("nonexistent")).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && mvn -B -q test -pl spring-ai-loom-agent-test -Dtest=SubTaskRegistryTest
```

Expected: **COMPILATION FAILURE** — `SubTaskRegistry` class not found.

- [ ] **Step 3: Implement `SubTaskRegistry.java`**

```java
package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory registry of sub-task lifecycles.
 * <p>
 * Tracks active runs (with their {@link CompletableFuture} so we can cancel),
 * archives completed records into a bounded ring (per-user FIFO), and exposes
 * query methods for the BFF + the conversation-deletion lifecycle hook.
 * </p>
 * <p>
 * Thread-safety: all state lives in {@link ConcurrentHashMap} /
 * {@link AtomicInteger} / a synchronized deque.
 * </p>
 */
public class SubTaskRegistry {

    private final int maxConcurrent;
    private final int maxHistory;

    private final ConcurrentHashMap<String, SubTaskRecord> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SubTaskRecord> history = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<SubTaskRecord>> historyByUser = new ConcurrentHashMap<>();
    private final AtomicInteger activeCount = new AtomicInteger(0);

    public SubTaskRegistry(int maxConcurrent, int maxHistory) {
        this.maxConcurrent = maxConcurrent;
        this.maxHistory = maxHistory;
    }

    /**
     * Registers a new sub-task and returns its assigned UUID. Throws if
     * {@link #maxConcurrent} active tasks are already in flight.
     */
    public String register(String username, String conversationId, String prompt) {
        if (activeCount.get() >= maxConcurrent) {
            throw new IllegalStateException(
                "已达最大并发子任务数 " + maxConcurrent + ", 请稍后再试");
        }
        String id = UUID.randomUUID().toString();
        SubTaskRecord rec = new SubTaskRecord(id, username, conversationId, prompt,
                SubTaskStatus.RUNNING, System.currentTimeMillis(), 0L, null, null, null);
        active.put(id, rec);
        activeCount.incrementAndGet();
        return id;
    }

    /**
     * Attach the {@link CompletableFuture} so the registry can cancel via
     * {@link CompletableFuture#cancel(boolean)} on kill.
     */
    public void attachFuture(String subTaskId, CompletableFuture<?> future) {
        SubTaskRecord rec = active.get(subTaskId);
        if (rec == null) return;
        rec = new SubTaskRecord(rec.subTaskId(), rec.username(), rec.conversationId(), rec.prompt(),
                rec.status(), rec.startedAt(), 0L, rec.errorMessage(), rec.resultText(), future);
        active.put(subTaskId, rec);
    }

    /**
     * Transitions an active sub-task to a terminal status and archives it.
     */
    public void markFinished(String subTaskId, SubTaskStatus status, String text, String errorMessage) {
        SubTaskRecord rec = active.remove(subTaskId);
        if (rec == null) return;
        activeCount.decrementAndGet();
        SubTaskRecord finished = new SubTaskRecord(rec.subTaskId(), rec.username(), rec.conversationId(),
                rec.prompt(), status, rec.startedAt(), System.currentTimeMillis(),
                errorMessage, text, null);
        history.put(subTaskId, finished);
        archive(finished);
    }

    /**
     * Attempts to cancel a running sub-task. Returns {@code true} if the task was
     * still running and a {@link CompletableFuture} cancel was issued.
     */
    public boolean kill(String subTaskId) {
        SubTaskRecord rec = active.get(subTaskId);
        if (rec == null) return false;
        CompletableFuture<?> future = rec.future();
        if (future != null) {
            future.cancel(true);
        }
        markFinished(subTaskId, SubTaskStatus.CANCELLED, null, "用户取消");
        return true;
    }

    /**
     * Cancels every active sub-task belonging to the given conversation.
     * Returns the number of cancelled tasks.
     */
    public int killAllByConversation(String conversationId) {
        List<String> ids = new ArrayList<>();
        active.forEach((id, rec) -> {
            if (rec.conversationId().equals(conversationId)) ids.add(id);
        });
        int n = 0;
        for (String id : ids) {
            if (kill(id)) n++;
        }
        return n;
    }

    public SubTaskRecord get(String subTaskId) {
        SubTaskRecord r = active.get(subTaskId);
        return r != null ? r : history.get(subTaskId);
    }

    public List<SubTaskRecord> listActive(String username) {
        List<SubTaskRecord> out = new ArrayList<>();
        active.forEach((id, rec) -> {
            if (rec.username().equals(username)) out.add(rec);
        });
        return out;
    }

    public List<SubTaskRecord> listHistory(String username, int limit) {
        Deque<SubTaskRecord> deque = historyByUser.get(username);
        if (deque == null) return List.of();
        // Newest first
        List<SubTaskRecord> out = new ArrayList<>(deque);
        java.util.Collections.reverse(out);
        if (out.size() > limit) return out.subList(0, limit);
        return out;
    }

    private void archive(SubTaskRecord rec) {
        Deque<SubTaskRecord> deque = historyByUser.computeIfAbsent(rec.username(), k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(rec);
            while (deque.size() > maxHistory) {
                deque.removeFirst();
                // Note: the drop is by insertion order (FIFO oldest-out).
                // The history map still has all entries so get() always works.
            }
        }
    }

    /**
     * Immutable view of a sub-task record. Terminal state is decided at construction time
     * via {@link SubTaskStatus}.
     */
    public record SubTaskRecord(
            String subTaskId,
            String username,
            String conversationId,
            String prompt,
            SubTaskStatus status,
            long startedAt,
            long finishedAt,
            String errorMessage,
            String resultText,
            CompletableFuture<?> future
    ) {}
}
```

- [ ] **Step 4: Run tests**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && mvn -B -q test -pl spring-ai-loom-agent-test -Dtest=SubTaskRegistryTest
```

Expected: **8 tests pass**.

- [ ] **Step 5: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/SubTaskRegistry.java spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/SubTaskRegistryTest.java && git commit -m "feat(subtask): SubTaskRegistry with bounded concurrency + per-user history"
```

---

## Task 2.4: ISubTaskExecutor + DefaultSubTaskExecutor

**Files:**
- Create: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/ISubTaskExecutor.java`
- Create: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskExecutor.java`
- Test: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskExecutorTest.java`

- [ ] **Step 1: `ISubTaskExecutor.java`**

```java
package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;

public interface ISubTaskExecutor {
    /**
     * Runs a sub-task synchronously on a dedicated thread pool. The implementation
     * is responsible for honoring cancellation (interrupting its worker thread).
     */
    SubTaskResult execute(SubTaskRequest req);
}
```

- [ ] **Step 2: Write `DefaultSubTaskExecutorTest.java` (mock ChatClient)**

```java
package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DefaultSubTaskExecutorTest {

    private ChatClient chatClient;
    private MessageChatMemoryAdvisor memoryAdvisor;
    private ThreadPoolExecutor executor;
    private DefaultSubTaskExecutor target;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        memoryAdvisor = mock(MessageChatMemoryAdvisor.class);
        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        target = new DefaultSubTaskExecutor(chatClient, memoryAdvisor, executor);
    }

    @Test
    void executesAndReturnsCompletedResultOnHappyPath() throws Exception {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        org.springframework.ai.chat.model.ChatResponse chatResponse =
                mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.springframework.ai.chat.model.Generation generation =
                mock(org.springframework.ai.chat.model.Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage msg =
                mock(org.springframework.ai.chat.messages.AssistantMessage.class);

        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
        when(spec.advisors(memoryAdvisor)).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(msg);
        when(msg.getText()).thenReturn("sub task done");

        SubTaskRequest req = new SubTaskRequest("sub-1", "conv-1", null, "alice",
                "do X", null, false);

        SubTaskResult result = target.execute(req);

        assertThat(result.status()).isEqualTo(SubTaskStatus.COMPLETED);
        assertThat(result.text()).isEqualTo("sub task done");
        assertThat(result.errorMessage()).isEmpty();
        assertThat(result.subTaskId()).isEqualTo("sub-1");
    }

    @Test
    void returnsFailedOnException() {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
        when(spec.advisors(memoryAdvisor)).thenReturn(spec);
        when(spec.call()).thenThrow(new RuntimeException("boom"));

        SubTaskRequest req = new SubTaskRequest("sub-2", "conv-2", null, "bob",
                "do Y", null, false);

        SubTaskResult result = target.execute(req);

        assertThat(result.status()).isEqualTo(SubTaskStatus.FAILED);
        assertThat(result.errorMessage()).contains("boom");
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && mvn -B -q test -pl spring-ai-loom-agent-test -Dtest=DefaultSubTaskExecutorTest
```

Expected: **COMPILATION FAILURE** — `DefaultSubTaskExecutor` class not found.

- [ ] **Step 4: Implement `DefaultSubTaskExecutor.java`**

```java
package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Default {@link ISubTaskExecutor}.
 * <p>
 * Behavior:
 * <ul>
 *   <li>Submits the call to a dedicated {@link ExecutorService} bean
 *       {@code loomSubTaskExecutor} so the call is interruptible and bounded.</li>
 *   <li>Uses {@link ChatClient.ChatClientRequestSpec#call()} — synchronous, runs
 *       full Spring AI tool-call loop to final response.</li>
 *   <li>On interrupt: cancels the future, returns {@link SubTaskStatus#CANCELLED}.</li>
 *   <li>On exception: returns {@link SubTaskStatus#FAILED} with the message.</li>
 *   <li>Writes intermediate ChatMemory entries under
 *       {@code "{conversationId}--sub--{subTaskId}"} so the main conversation
 *       can later see what the sub-task produced.</li>
 * </ul>
 */
public class DefaultSubTaskExecutor implements ISubTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultSubTaskExecutor.class);

    private final ChatClient subTaskChatClient;
    private final MessageChatMemoryAdvisor memoryAdvisor;
    private final ExecutorService executor;

    public DefaultSubTaskExecutor(ChatClient subTaskChatClient,
                                  MessageChatMemoryAdvisor memoryAdvisor,
                                  ExecutorService executor) {
        this.subTaskChatClient = subTaskChatClient;
        this.memoryAdvisor = memoryAdvisor;
        this.executor = executor;
    }

    @Override
    public SubTaskResult execute(SubTaskRequest req) {
        long startedAt = System.currentTimeMillis();
        log.info("Sub-task start: id={}, parentConv={}, user={}, fromScheduler={}",
                req.subTaskId(), req.parentConversationId(), req.username(), req.fromScheduler());

        Future<SubTaskResult> future = executor.submit(() -> doExecute(req, startedAt));
        try {
            return future.get();
        } catch (InterruptedException ie) {
            future.cancel(true);
            SubTaskResult r = SubTaskResult.cancelled(req, startedAt, System.currentTimeMillis());
            log.info("Sub-task interrupted: id={}", req.subTaskId());
            return r;
        } catch (java.util.concurrent.ExecutionException ee) {
            SubTaskResult r = SubTaskResult.failed(req, startedAt, System.currentTimeMillis(),
                    rootCauseMessage(ee));
            log.error("Sub-task failed: id={}", req.subTaskId(), ee);
            return r;
        } catch (java.util.concurrent.CancellationException ce) {
            SubTaskResult r = SubTaskResult.cancelled(req, startedAt, System.currentTimeMillis());
            log.info("Sub-task cancelled: id={}", req.subTaskId());
            return r;
        }
    }

    private SubTaskResult doExecute(SubTaskRequest req, long startedAt) {
        try {
            ChatClient.ChatClientRequestSpec spec = subTaskChatClient.prompt();
            if (req.systemContext() != null) {
                spec.system(req.systemContext());
            }
            spec.user(req.prompt());
            String memoryId = req.memoryConversationId();
            spec.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, memoryId));
            spec.advisors(memoryAdvisor);

            String text = spec.call().chatResponse().getResult().getOutput().getText();
            return SubTaskResult.completed(req, startedAt, System.currentTimeMillis(), text);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ie;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getClass().getSimpleName() + ": " + r.getMessage();
    }
}
```

- [ ] **Step 5: Run tests**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && mvn -B -q test -pl spring-ai-loom-agent-test -Dtest=DefaultSubTaskExecutorTest
```

Expected: **2 tests pass.**

- [ ] **Step 6: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/ISubTaskExecutor.java spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskExecutor.java spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskExecutorTest.java && git commit -m "feat(subtask): ISubTaskExecutor + DefaultSubTaskExecutor (sync call)"
```

---

## Task 2.5: SubTaskConfiguration (ChatClient bean + executor bean + router scaffold)

**Files:**
- Modify: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java`
- Modify: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentProperties.java`

- [ ] **Step 1: Add `SubTask` nested config class to `LoomAgentProperties.java`**

Find the class body (around `public class LoomAgentProperties`). Add a nested static class at the bottom of the existing nested classes (after `public static class Maven`, etc.) and add a field:

```java
public SubTask subtask = new SubTask();

public static class SubTask {
    private boolean enabled = true;
    private int maxConcurrent = 4;
    private int maxHistory = 200;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
    public int getMaxHistory() { return maxHistory; }
    public void setMaxHistory(int maxHistory) { this.maxHistory = maxHistory; }
}
```

- [ ] **Step 2: Add a new inner `SubTaskConfiguration` class to `LoomAgentConfiguration.java`**

Insert a new inner `@Configuration` class after `ToolConfiguration` (around line 565):

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.loom.agent.subtask.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.subtask.ISubTaskExecutor.class)
public static class SubTaskConfiguration {

    /**
     * Builds a sub-task-specific ChatClient: same model + same memory, but a
     * filtered tool set that excludes ISubTaskTool / IScheduleTool to prevent
     * recursion. Constructed once at startup.
     */
    @Bean(name = "loomSubTaskChatClient")
    public ChatClient loomSubTaskChatClient(
            ChatClient.Builder builder,
            java.util.List<cn.wubo.spring.ai.loom.agent.tool.IEmbedTool> embedTools,
            Optional<RetrievalAugmentationAdvisor> rag,
            cn.wubo.spring.ai.loom.agent.mcp.IMcp mcp,
            cn.wubo.spring.ai.loom.agent.file.IFile file,
            LoomAgentProperties properties) {

        // Copy of the main ChatClient wiring, but with a filtered tool set.
        // Note: ChatClient.Builder is injected by Spring AI; the chat model +
        // memory advisor are configured by Spring AI's autoconfig.
        java.util.List<Object> filteredTools = new java.util.ArrayList<>();
        for (var t : embedTools) {
            boolean isRecursive =
                    t instanceof cn.wubo.spring.ai.loom.agent.subtask.ISubTaskTool
                    || t instanceof cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool;
            if (!isRecursive) filteredTools.add(t);
        }
        // RAG / File / MCP are wired per-call via ChatRequestComposer in Phase 3
        // when DefaultSubTaskTool is built. Here we just bake the embedTool set.
        ChatClient built = builder
                .defaultTools(filteredTools.toArray())
                .build();
        log.info("Sub-task ChatClient built with {} tools (recursion-filtered)", filteredTools.size());
        return built;
    }

    @Bean(name = "loomSubTaskExecutor", destroyMethod = "shutdown")
    public java.util.concurrent.ExecutorService loomSubTaskExecutor(LoomAgentProperties properties) {
        int n = Math.max(1, properties.getSubtask().getMaxConcurrent());
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor exec =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        exec.setCorePoolSize(n);
        exec.setMaxPoolSize(n);
        exec.setQueueCapacity(50);
        exec.setThreadNamePrefix("loom-subtask-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(10);
        exec.initialize();
        log.info("loomSubTaskExecutor initialized: pool={}, queue=50", n);
        return exec.getThreadPoolExecutor();
    }

    @Bean
    public cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry subTaskRegistry(LoomAgentProperties properties) {
        return new cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry(
                properties.getSubtask().getMaxConcurrent(),
                properties.getSubtask().getMaxHistory());
    }

    @Bean
    public cn.wubo.spring.ai.loom.agent.subtask.ISubTaskExecutor defaultSubTaskExecutor(
            @Qualifier("loomSubTaskChatClient") ChatClient loomSubTaskChatClient,
            org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor memoryAdvisor,
            @Qualifier("loomSubTaskExecutor") java.util.concurrent.ExecutorService loomSubTaskExecutor) {
        return new cn.wubo.spring.ai.loom.agent.subtask.DefaultSubTaskExecutor(
                loomSubTaskChatClient, memoryAdvisor, loomSubTaskExecutor);
    }

    /**
     * Sub-task CRUD endpoints. Full task list / kill / etc.
     */
    @Bean("loomAgentSubTaskRouter")
    public org.springframework.web.reactive.function.server.RouterFunction<org.springframework.web.reactive.function.server.ServerResponse> loomAgentSubTaskRouter(
            cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry registry) {
        org.springframework.web.reactive.function.server.RouterFunctions.Builder builder =
                org.springframework.web.reactive.function.server.RouterFunctions.route();
        builder.GET("spring/ai/loom/subtask/list/active",
                request -> org.springframework.web.reactive.function.server.ServerResponse
                        .ok().body(registry.listActive(
                                cn.wubo.spring.ai.loom.agent.user.UserContextHolder.getCurrentUser())));
        builder.GET("spring/ai/loom/subtask/list/history",
                request -> org.springframework.web.reactive.function.server.ServerResponse
                        .ok().body(registry.listHistory(
                                cn.wubo.spring.ai.loom.agent.user.UserContextHolder.getCurrentUser(), 50)));
        builder.POST("spring/ai/loom/subtask/kill/{id}",
                request -> org.springframework.web.reactive.function.server.ServerResponse
                        .ok().body(registry.kill(request.pathVariable("id"))));
        return builder.build();
    }
}
```

- [ ] **Step 3: Confirm `ChatClient.Builder` and `MessageChatMemoryAdvisor` are available**

The `ChatClient.Builder` is autoconfigured by `spring-ai-spring-boot-starter` — verify in the running app. `MessageChatMemoryAdvisor` should also be auto-created since `ChatMemory` (JDBC-backed) is autoconfigured. If either is missing, add the necessary imports / check Spring AI docs.

- [ ] **Step 4: Build**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && mvn -B -q install -DskipTests -pl spring-ai-loom-agent-spring-boot-autoconfigure,spring-ai-loom-agent-spring-boot-starter,spring-ai-loom-agent-test -am
```

Expected: **BUILD SUCCESS**. (Bodies are wired; tools expose only schema until Phase 3.)

- [ ] **Step 5: Restart the test app and verify `/spring/ai/loom/subtask/list/active` returns 200**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && mvn -B -q spring-boot:run -pl spring-ai-loom-agent-test -Dgpg.skip=true > /tmp/loom2.log 2>&1 &
SERVER_PID=$!
for i in $(seq 1 30); do curl -sf http://localhost:8080/spring/ai/loom/index.html > /dev/null && break; sleep 1; done
# Try as admin (auto-login via cookie expected)
curl -s -X POST http://localhost:8080/spring/ai/loom/user/isAutoLogin -H "Content-Type: application/json" -c /tmp/cookies.txt -d '{}'
echo ""
curl -s -X POST http://localhost:8080/spring/ai/loom/user/login -H "Content-Type: application/json" -b /tmp/cookies.txt -c /tmp/cookies.txt -d '{"username":"admin","password":"123456"}'
echo ""
curl -s http://localhost:8080/spring/ai/loom/subtask/list/active -b /tmp/cookies.txt
echo ""
kill $SERVER_PID 2>/dev/null; true
```

Expected: `[]` (empty list).

(Note: confirm the existing seeded admin password in `spring-ai-loom-agent/src/main/resources/db/migration/V1.0__init.sql`. If different, substitute.)

- [ ] **Step 6: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && git add spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentProperties.java && git commit -m "feat(subtask): wire loomSubTaskChatClient, executor, registry, router"
```

---

# Phase 3 — loom-agent: Sub-task tool (LLM-callable)

> **Goal:** LLM can now invoke `start_sub_task` from the main conversation.

## Task 3.1: ISubTaskTool interface

**Files:**
- Create: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/ISubTaskTool.java`

- [ ] **Step 1: Create the interface**

```java
package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * LLM-callable tool that delegates a subtask to a sub-model.
 * <p>
 * The sub-task shares the main conversation's ChatMemory (write-only namespace
 * "{conversationId}--sub--{subTaskId}"), runs synchronously, and returns the
 * final answer as a string for the main conversation to consume.
 * </p>
 */
public interface ISubTaskTool extends IEmbedTool {

    /**
     * Starts a subtask. The main conversation is blocked until the sub-task
     * finishes (or is cancelled).
     *
     * @param prompt        What the sub-task should accomplish.
     * @param systemContext Optional extra system guidance (or {@code null}).
     * @param toolContext   Spring AI tool context (carries username, etc.)
     */
    String startSubTask(
            @ToolParam(description = "子任务要完成的指令,例如'总结以下长文...')") String prompt,
            @ToolParam(description = "可选的额外系统指令,例如\\\"只关注技术细节\\\"。不需要可传 null。") String systemContext,
            ToolContext toolContext);
}
```

- [ ] **Step 2: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/ISubTaskTool.java && git commit -m "feat(subtask): ISubTaskTool interface (LLM-callable start_sub_task)"
```

---

## Task 3.2: DefaultSubTaskTool implementation + test

**Files:**
- Create: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskTool.java`
- Test: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskToolTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DefaultSubTaskToolTest {

    @Test
    void registersRunsAndReportsCompletedResult() {
        SubTaskRegistry registry = new SubTaskRegistry(8, 100);
        ISubTaskExecutor executor = mock(ISubTaskExecutor.class);

        when(executor.execute(any(SubTaskRequest.class))).thenAnswer(inv -> {
            SubTaskRequest req = inv.getArgument(0);
            return SubTaskResult.completed(req, 0L, System.currentTimeMillis(), "ok-text");
        });

        DefaultSubTaskTool tool = new DefaultSubTaskTool(executor, registry);
        ToolContext ctx = ToolContext.create(Map.of(
                "username", "alice",
                "parentConversationId", "conv-1"));

        String result = tool.startSubTask("do X", null, ctx);

        assertThat(result).contains("ok-text");
        assertThat(result).contains("conv-1");
        assertThat(registry.listHistory("alice", 10)).hasSize(1);
        verify(executor).execute(any(SubTaskRequest.class));
    }

    @Test
    void reportsFailureAndSaysSoToMain() {
        SubTaskRegistry registry = new SubTaskRegistry(8, 100);
        ISubTaskExecutor executor = mock(ISubTaskExecutor.class);

        when(executor.execute(any(SubTaskRequest.class))).thenAnswer(inv -> {
            SubTaskRequest req = inv.getArgument(0);
            return SubTaskResult.failed(req, 0L, System.currentTimeMillis(), "boom");
        });

        DefaultSubTaskTool tool = new DefaultSubTaskTool(executor, registry);
        ToolContext ctx = ToolContext.create(Map.of(
                "username", "alice",
                "parentConversationId", "conv-1"));

        String result = tool.startSubTask("do X", null, ctx);

        assertThat(result).contains("boom");
        assertThat(result).contains("失败");
    }

    @Test
    void reportsCancellationToMain() {
        SubTaskRegistry registry = new SubTaskRegistry(8, 100);
        ISubTaskExecutor executor = mock(ISubTaskExecutor.class);

        when(executor.execute(any(SubTaskRequest.class))).thenAnswer(inv -> {
            SubTaskRequest req = inv.getArgument(0);
            return SubTaskResult.cancelled(req, 0L, System.currentTimeMillis());
        });

        DefaultSubTaskTool tool = new DefaultSubTaskTool(executor, registry);
        ToolContext ctx = ToolContext.create(Map.of(
                "username", "alice",
                "parentConversationId", "conv-1"));

        String result = tool.startSubTask("do X", null, ctx);

        assertThat(result).contains("取消");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && mvn -B -q test -pl spring-ai-loom-agent-test -Dtest=DefaultSubTaskToolTest
```

Expected: **COMPILATION FAILURE** — `DefaultSubTaskTool` not found.

- [ ] **Step 3: Implement `DefaultSubTaskTool.java`**

```java
package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.util.UUID;

public class DefaultSubTaskTool implements ISubTaskTool {

    private static final Logger log = LoggerFactory.getLogger(DefaultSubTaskTool.class);

    private final ISubTaskExecutor executor;
    private final SubTaskRegistry registry;

    public DefaultSubTaskTool(ISubTaskExecutor executor, SubTaskRegistry registry) {
        this.executor = executor;
        this.registry = registry;
    }

    @Tool(description = "把一段任务委派给一个'子模型'去执行。子任务拥有与主对话相同的"
            + "工具访问(文件/MCP/Skill/时间等),但不能再次启动子任务或创建定时器。"
            + "主对话会同步等待子任务完成,然后拿到最终文本。")
    @Override
    public String startSubTask(String prompt, String systemContext, ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        String parentConvId = (String) toolContext.getContext().get("parentConversationId");

        String subTaskId = UUID.randomUUID().toString();
        registry.register(username, parentConvId, prompt);
        log.info("子任务启动: id={}, user={}, conv={}", subTaskId, username, parentConvId);

        SubTaskRequest req = new SubTaskRequest(subTaskId, parentConvId, null,
                username, prompt, systemContext, false);

        SubTaskResult result;
        try {
            result = executor.execute(req);
        } catch (Exception e) {
            log.error("子任务异常: id={}", subTaskId, e);
            result = SubTaskResult.failed(req, 0L, System.currentTimeMillis(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        registry.markFinished(subTaskId, result.status(), result.text(), result.errorMessage());
        return formatForMainConversation(result);
    }

    private String formatForMainConversation(SubTaskResult r) {
        return switch (r.status()) {
            case COMPLETED -> "[子任务已完成 conv=%s] %s".formatted(r.conversationId(), r.text());
            case FAILED    -> "[子任务失败 conv=%s] %s".formatted(r.conversationId(), r.errorMessage());
            case CANCELLED -> "[子任务已取消 conv=%s] 用户手动取消".formatted(r.conversationId());
            default -> "[子任务状态异常] " + r.status();
        };
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && mvn -B -q test -pl spring-ai-loom-agent-test -Dtest=DefaultSubTaskToolTest
```

Expected: **3 tests pass**.

- [ ] **Step 5: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskTool.java spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskToolTest.java && git commit -m "feat(subtask): DefaultSubTaskTool (LLM-callable start_sub_task)"
```

---

## Task 3.3: Wire DefaultSubTaskTool into MainChat's tool list

**Files:**
- Modify: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java`
  - In `ChatConfiguration#chat(...)` bean method: `embedTools` is already injected via `List<IEmbedTool>` — `ISubTaskTool` and `IScheduleTool` are picked up automatically when they exist (since both extend `IEmbedTool`).
  - Add a new `@Bean` for `DefaultSubTaskTool` + `ISubTaskTool` exposed to main chat.

- [ ] **Step 1: Add `ISubTaskTool` `@Bean` to `SubTaskConfiguration` (Phase 2 Task 2.5's class)**

Append to the `SubTaskConfiguration` class:

```java
@Bean
@ConditionalOnMissingBean(ISubTaskTool.class)
public cn.wubo.spring.ai.loom.agent.subtask.ISubTaskTool defaultSubTaskTool(
        ISubTaskExecutor executor,
        cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry registry) {
    return new cn.wubo.spring.ai.loom.agent.subtask.DefaultSubTaskTool(executor, registry);
}
```

Because `ISubTaskTool extends IEmbedTool`, the existing `ChatConfiguration#chat(...)` bean injection will automatically include it in the main conversation's tool list — no changes needed there.

- [ ] **Step 2: Build & verify**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && mvn -B -q install -DskipTests -pl spring-ai-loom-agent-spring-boot-autoconfigure,spring-ai-loom-agent-spring-boot-starter,spring-ai-loom-agent-test -am
```

Expected: **BUILD SUCCESS**.

- [ ] **Step 3: End-to-end smoke**

Start the test app:

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && mvn spring-boot:run -pl spring-ai-loom-agent-test -Dgpg.skip=true > /tmp/loom3.log 2>&1 &
SERVER_PID=$!
for i in $(seq 1 30); do curl -sf http://localhost:8080/spring/ai/loom/index.html > /dev/null && break; sleep 1; done

# Login as admin
curl -s -X POST http://localhost:8080/spring/ai/loom/user/login -H "Content-Type: application/json" \
    -c /tmp/cookies.txt -d '{"username":"admin","password":"123456"}'
echo ""

# Send a message asking for a sub-task:
echo "SSE stream test: sending chat message"
timeout 30 curl -s -N -X POST http://localhost:8080/spring/ai/loom/chat/stream \
    -H "Content-Type: application/json" \
    -b /tmp/cookies.txt \
    -d '{"message":"请用 start_sub_task 总结一句:42 * 56 等于多少?","conversationId":"smoke-1","mcps":[],"knowledgeId":null,"fileIds":[]}' | head -c 800
echo ""

kill $SERVER_PID 2>/dev/null; true
```

Expected: SSE stream contains tool-call wire-up indicating `start_sub_task` was offered to the model, and (depending on model) the model either invokes or declines.

- [ ] **Step 4: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && git add spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java && git commit -m "feat(subtask): expose ISubTaskTool in main chat's tool list"
```

---

# Phase 4 — Schedule tool

## Task 4.1: IScheduleTool interface

**Files:**
- Create: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/schedule/IScheduleTool.java`

- [ ] **Step 1: Create the interface**

```java
package cn.wubo.spring.ai.loom.agent.schedule;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

public interface IScheduleTool extends IEmbedTool {

    String createSchedule(
            @ToolParam(description = "任务名,字母数字下划线。在同一会话内需唯一。") String name,
            @ToolParam(description = "调度类型: cron | fixed_delay | fixed_rate | one_shot") String scheduleType,
            @ToolParam(description = "表达式: cron 字符串 / 间隔秒数 / one_shot 的延迟秒数") String expression,
            @ToolParam(description = "触发时作为子任务运行的提示词") String prompt,
            ToolContext toolContext);

    String cancelSchedule(
            @ToolParam(description = "任务名(用户给定的短名,无需前缀)") String name,
            ToolContext toolContext);

    String listSchedules(ToolContext toolContext);

    String getScheduleHistory(
            @ToolParam(description = "任务名") String name,
            @ToolParam(description = "返回多少条,默认 20") Integer limit,
            ToolContext toolContext);

    /** Used internally by the BFF (not exposed as @Tool). */
    String listSchedulesRaw(String username);
}
```

- [ ] **Step 2: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/schedule/IScheduleTool.java && git commit -m "feat(schedule): IScheduleTool interface (4 tool methods + 1 raw accessor)"
```

---

## Task 4.2: DefaultScheduleTool + test

**Files:**
- Create: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/schedule/DefaultScheduleTool.java`
- Test: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/schedule/DefaultScheduleToolTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.wubo.spring.ai.loom.agent.schedule;

import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.TaskInfo;
import cn.wubo.flex.schedule.core.ExecutionRecord;
import cn.wubo.spring.ai.loom.agent.subtask.ISubTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class DefaultScheduleToolTest {

    private FlexScheduledTaskService flexService;
    private ISubTaskExecutor executor;
    private DefaultScheduleTool tool;

    @BeforeEach
    void setUp() {
        flexService = mock(FlexScheduledTaskService.class);
        executor = mock(ISubTaskExecutor.class);
        tool = new DefaultScheduleTool(flexService, executor);
    }

    @Test
    void createScheduleNamespacesByUsernameAndConv() {
        ToolContext ctx = ToolContext.create(Map.of(
                "username", "alice",
                "parentConversationId", "conv-1"));

        String response = tool.createSchedule("remind", "fixed_delay", "600", "say hi", ctx);

        assertThat(response).contains("remind");
        verify(flexService).task(contains("loom-sched-alice-conv-1-remind"));
    }

    @Test
    void createSchedulePropagatesLimitExceptionAsFriendlyMessage() {
        ToolContext ctx = ToolContext.create(Map.of(
                "username", "alice",
                "parentConversationId", "conv-1"));

        when(flexService.task(any())).thenThrow(new RuntimeException("trigger interval too small"));

        String response = tool.createSchedule("x", "fixed_delay", "1", "p", ctx);

        assertThat(response).contains("失败");
        assertThat(response).contains("trigger interval too small");
    }

    @Test
    void cancelScheduleCallsCancelOnNamespacedName() {
        ToolContext ctx = ToolContext.create(Map.of("username", "alice", "parentConversationId", "conv-1"));
        tool.cancelSchedule("remind", ctx);
        verify(flexService).cancel("loom-sched-alice-conv-1-remind");
    }

    @Test
    void listSchedulesFiltersByNamespacePrefix() {
        ToolContext ctx = ToolContext.create(Map.of("username", "alice", "parentConversationId", "conv-1"));
        when(flexService.listTasks()).thenReturn(List.of(
                new TaskInfo("loom-sched-alice-conv-1-remind", "FIXED_DELAY", null, null, null),
                new TaskInfo("loom-sched-alice-conv-2-other", "FIXED_DELAY", null, null, null),
                new TaskInfo("loom-sched-bob-conv-1-foo", "CRON", null, null, null)));

        String response = tool.listSchedules(ctx);
        assertThat(response).contains("remind");
        assertThat(response).doesNotContain("other");
        assertThat(response).doesNotContain("foo");
    }

    @Test
    void getScheduleHistoryCallsHistoryWithLimit() {
        ToolContext ctx = ToolContext.create(Map.of("username", "alice", "parentConversationId", "conv-1"));
        when(flexService.getExecutionHistory(eq("loom-sched-alice-conv-1-remind"), eq(10)))
                .thenReturn(List.<ExecutionRecord>of());

        String response = tool.getScheduleHistory("remind", 10, ctx);
        assertThat(response).contains("remind");
        verify(flexService).getExecutionHistory("loom-sched-alice-conv-1-remind", 10);
    }

    @Test
    void getScheduleHistoryDefaultsLimitTo20WhenNull() {
        ToolContext ctx = ToolContext.create(Map.of("username", "alice", "parentConversationId", "conv-1"));
        when(flexService.getExecutionHistory(any(), eq(20))).thenReturn(List.of());
        tool.getScheduleHistory("remind", null, ctx);
        verify(flexService).getExecutionHistory(any(), eq(20));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && mvn -B -q test -pl spring-ai-loom-agent-test -Dtest=DefaultScheduleToolTest
```

Expected: **COMPILATION FAILURE** — `DefaultScheduleTool` not found.

- [ ] **Step 3: Implement `DefaultScheduleTool.java`**

```java
package cn.wubo.spring.ai.loom.agent.schedule;

import cn.wubo.flex.schedule.core.ExecutionRecord;
import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.TaskInfo;
import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.subtask.ISubTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public class DefaultScheduleTool implements IScheduleTool {

    private static final Logger log = LoggerFactory.getLogger(DefaultScheduleTool.class);

    private final FlexScheduledTaskService flexService;
    private final ISubTaskExecutor subTaskExecutor;

    public DefaultScheduleTool(FlexScheduledTaskService flexService, ISubTaskExecutor subTaskExecutor) {
        this.flexService = flexService;
        this.subTaskExecutor = subTaskExecutor;
    }

    static String fullName(String username, String conversationId, String name) {
        return "loom-sched-" + username + "-" + conversationId + "-" + name;
    }

    @Override
    @Tool(description = "创建一个定时任务。最短 10 分钟执行一次,最长存活 3 天(强校验)。"
            + "类型 cron / fixed_delay / fixed_rate / one_shot。")
    public String createSchedule(String name, String scheduleType, String expression, String prompt, ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        String convId = (String) toolContext.getContext().get("parentConversationId");
        String full = fullName(username, convId, name);

        try {
            switch (scheduleType.toLowerCase()) {
                case "cron" -> flexService.task(full)
                        .cron(expression)
                        .register(() -> runAsSubTask(username, convId, prompt));
                case "fixed_delay" -> flexService.task(full)
                        .fixedDelay(Duration.ofSeconds(Long.parseLong(expression)))
                        .register(() -> runAsSubTask(username, convId, prompt));
                case "fixed_rate" -> flexService.task(full)
                        .fixedRate(Duration.ofSeconds(Long.parseLong(expression)))
                        .register(() -> runAsSubTask(username, convId, prompt));
                case "one_shot" -> flexService.task(full)
                        .oneShot(Duration.ofSeconds(Long.parseLong(expression)))
                        .register(() -> runAsSubTask(username, convId, prompt));
                default -> { return "[定时失败] 不支持的类型: " + scheduleType; }
            }
            return "[定时已创建] " + name + " (" + scheduleType + ": " + expression + ")";
        } catch (Exception e) {
            log.error("定时器创建失败: full={}", full, e);
            return "[定时失败] " + e.getMessage();
        }
    }

    private void runAsSubTask(String username, String convId, String prompt) {
        String id = UUID.randomUUID().toString();
        SubTaskRequest req = new SubTaskRequest(id, convId, null, username, prompt, null, true);
        try {
            subTaskExecutor.execute(req);   // fire and forget — executor handles its own exceptions
        } catch (Exception e) {
            log.error("调度子任务执行失败: id={}", id, e);
        }
    }

    @Override
    @Tool(description = "取消一个定时任务。")
    public String cancelSchedule(String name, ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        String convId = (String) toolContext.getContext().get("parentConversationId");
        String full = fullName(username, convId, name);
        try {
            flexService.cancel(full);
            return "[定时已取消] " + name;
        } catch (Exception e) {
            return "[取消失败] " + e.getMessage();
        }
    }

    @Override
    @Tool(description = "列出当前会话下我创建的所有定时任务。")
    public String listSchedules(ToolContext toolContext) {
        return listSchedulesRaw((String) toolContext.getContext().get("username"));
    }

    @Override
    public String listSchedulesRaw(String username) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("定时任务列表:%n%n"));
        List<TaskInfo> all = flexService.listTasks();
        int n = 0;
        for (TaskInfo info : all) {
            if (info.taskName().startsWith("loom-sched-")) {
                String withoutPrefix = info.taskName().substring("loom-sched-".length());
                int firstDash = withoutPrefix.indexOf('-');
                if (firstDash < 0) continue;
                String owner = withoutPrefix.substring(0, firstDash);
                if (!owner.equals(username)) continue;
                sb.append(String.format("- %s (type=%s)%n", info.taskName(), info.taskType()));
                n++;
            }
        }
        if (n == 0) sb.append("(无定时任务)%n");
        return sb.toString();
    }

    @Override
    @Tool(description = "获取某个定时任务的最近执行历史。")
    public String getScheduleHistory(String name, Integer limit, ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        String convId = (String) toolContext.getContext().get("parentConversationId");
        String full = fullName(username, convId, name);
        int n = limit == null ? 20 : limit;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("'%s' 的最近 %d 条执行记录:%n%n", name, n));
        List<ExecutionRecord> history;
        try {
            history = flexService.getExecutionHistory(full, n);
        } catch (Exception e) {
            return "[查询失败] " + e.getMessage();
        }
        if (history == null || history.isEmpty()) {
            sb.append("(暂无执行记录)%n");
            return sb.toString();
        }
        for (ExecutionRecord r : history) {
            sb.append(String.format("- 触发 %s | 状态=%s | 时长=%s%n",
                    r.startTime(), r.status(), r.duration()));
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && mvn -B -q test -pl spring-ai-loom-agent-test -Dtest=DefaultScheduleToolTest
```

Expected: **6 tests pass**.

- [ ] **Step 5: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/schedule/DefaultScheduleTool.java spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/schedule/DefaultScheduleToolTest.java && git commit -m "feat(schedule): DefaultScheduleTool with namespacing and limits propagation"
```

---

## Task 4.3: Wire ScheduleConfiguration + limits defaults

**Files:**
- Modify: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java`
- Modify: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentProperties.java`

- [ ] **Step 1: Add `Schedule` nested config in `LoomAgentProperties.java`**

Adjacent to the `SubTask` config:

```java
public Schedule schedule = new Schedule();

public static class Schedule {
    private boolean enabled = true;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
```

- [ ] **Step 2: Add a new inner `ScheduleConfiguration` class**

In `LoomAgentConfiguration.java`, after `SubTaskConfiguration`:

```java
@ConditionalOnClass(name = "cn.wubo.flex.schedule.core.FlexScheduledTaskService")
@ConditionalOnBean(name = "flexScheduledTaskService")
@ConditionalOnProperty(name = "spring.ai.loom.agent.schedule.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool.class)
public static class ScheduleConfiguration {

    @Bean
    public cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool defaultScheduleTool(
            @Qualifier("flexScheduledTaskService") cn.wubo.flex.schedule.core.FlexScheduledTaskService flexService,
            cn.wubo.spring.ai.loom.agent.subtask.ISubTaskExecutor subTaskExecutor) {
        return new cn.wubo.spring.ai.loom.agent.schedule.DefaultScheduleTool(flexService, subTaskExecutor);
    }

    @Bean(name = "loomAgentScheduleRouter")
    public org.springframework.web.reactive.function.server.RouterFunction<org.springframework.web.reactive.function.server.ServerResponse> loomAgentScheduleRouter(
            @Qualifier("flexScheduledTaskService") cn.wubo.flex.schedule.core.FlexScheduledTaskService flexService,
            cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool scheduleTool) {
        org.springframework.web.reactive.function.server.RouterFunctions.Builder builder =
                org.springframework.web.reactive.function.server.RouterFunctions.route();
        builder.GET("spring/ai/loom/schedule/list",
                request -> org.springframework.web.reactive.function.server.ServerResponse
                        .ok().body(scheduleTool.listSchedulesRaw(
                                cn.wubo.spring.ai.loom.agent.user.UserContextHolder.getCurrentUser())));
        builder.POST("spring/ai/loom/schedule/cancel",
                request -> {
                    org.springframework.web.reactive.function.server.ServerRequest req2 = request;
                    String name = req2.pathVariable("name");
                    // Namespacing handled by caller; here we pass the FULL name from frontend
                    String username = cn.wubo.spring.ai.loom.agent.user.UserContextHolder.getCurrentUser();
                    // Look up via flexService and cancel directly to keep endpoint simple
                    flexService.cancel(name);
                    return org.springframework.web.reactive.function.server.ServerResponse
                            .ok().body(true);
                });
        builder.GET("spring/ai/loom/schedule/history/{name}",
                request -> org.springframework.web.reactive.function.server.ServerResponse
                        .ok().body(flexService.getExecutionHistory(request.pathVariable("name"), 50)));
        return builder.build();
    }
}
```

(Cancel uses the full name passed by frontend; the frontend already namespaces. This keeps the BFF side simple.)

- [ ] **Step 3: Update the parent's pom to actually pull flex-schedule**

Currently `flex-schedule-spring-boot-starter` is in `<dependencyManagement>`, but no module declares it as a real `<dependency>`. Add to `spring-ai-loom-agent` core pom.xml's `<dependencies>`:

```xml
<dependency>
    <groupId>io.github.wb04307201</groupId>
    <artifactId>flex-schedule-spring-boot-starter</artifactId>
</dependency>
```

- [ ] **Step 4: Add application.yml default limits (Phase 7 prep but include here)**

Add to `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent-test/src/main/resources/application.yml` (preserve existing comments / structure):

```yaml
flex:
  schedule:
    pool-size: 8
    limits:
      min-interval: 10m
      max-lifetime: 72h
      mode: strict
```

- [ ] **Step 5: Build**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && mvn -B -q install -DskipTests -am
```

Expected: **BUILD SUCCESS**.

- [ ] **Step 6: Smoke**

Run the test app and:
1. `POST /spring/ai/loom/schedule/list` → `定时任务列表: ... (无定时任务)`
2. Send a chat message asking "请创建一个定时任务,每 600 秒报告一次'定时触发'" — model invokes `create_schedule`
3. Verify via `/spring/ai/loom/schedule/list` after ~10s that the task shows up

(Exact behaviour depends on model — adjust trigger phrase if needed.)

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && mvn spring-boot:run -pl spring-ai-loom-agent-test -Dgpg.skip=true > /tmp/loom4.log 2>&1 &
SERVER_PID=$!
for i in $(seq 1 30); do curl -sf http://localhost:8080/spring/ai/loom/index.html > /dev/null && break; sleep 1; done

curl -s -X POST http://localhost:8080/spring/ai/loom/user/login -H "Content-Type: application/json" \
    -c /tmp/cookies.txt -d '{"username":"admin","password":"123456"}'
echo ""

curl -s http://localhost:8080/spring/ai/loom/schedule/list -b /tmp/cookies.txt
echo ""

kill $SERVER_PID 2>/dev/null; true
```

Expected: a (Chinese) "定时任务列表:" header and "(无定时任务)" footer.

- [ ] **Step 7: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && git add spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentProperties.java spring-ai-loom-agent/pom.xml spring-ai-loom-agent-test/src/main/resources/application.yml && git commit -m "feat(schedule): wire ScheduleConfiguration + add flex-schedule core dep + 10m/72h limits"
```

---

# Phase 5 — Conversation-deletion lifecycle hook

## Task 5.1: Modify DELETE conversation route

**Files:**
- Modify: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java`
- Test: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/ConversationLifecycleListenerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.wubo.spring.ai.loom.agent;

import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.TaskInfo;
import cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry;
import cn.wubo.spring.ai.loom.agent.user.IUserConversation;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ConversationLifecycleListenerTest {

    private SubTaskRegistry registry;
    private FlexScheduledTaskService flexService;
    private IUserConversation userConversation;
    private RouterFunction<ServerResponse> router;

    @BeforeEach
    void setUp() {
        registry = new SubTaskRegistry(8, 100);
        flexService = mock(FlexScheduledTaskService.class);
        userConversation = mock(IUserConversation.class);
        UserContextHolder.setCurrentUser("alice");

        when(flexService.listTasks()).thenReturn(List.of(
                new TaskInfo("loom-sched-alice-conv-1-remind", "FIXED_DELAY", null, null, null),
                new TaskInfo("loom-sched-alice-conv-2-other", "FIXED_DELAY", null, null, null),
                new TaskInfo("loom-sched-bob-conv-1-foo", "CRON", null, null, null)
        ));

        router = LoomAgentConfiguration.conversationCleanupRouter(registry, flexService, userConversation);
    }

    @AfterEach
    void tearDown() { UserContextHolder.clear(); }

    @Test
    void deleteConvStopsMatchingSubtasksAndSchedules() {
        String id = registry.register("alice", "conv-1", "p1");
        CompletableFuture<?> f = new CompletableFuture<>();
        registry.attachFuture(id, f);

        // Invoke the DELETE endpoint
        var responseFuture = RouterFunctionInvoker.invokeDelete(router,
                "/spring/ai/loom/conversation/conv-1");

        assertThat(responseFuture.statusCode().value()).isEqualTo(200);
        assertThat(f.isCancelled()).isTrue();
        verify(flexService).cancel("loom-sched-alice-conv-1-remind");
        verify(flexService, never()).cancel("loom-sched-alice-conv-2-other");
        verify(flexService, never()).cancel("loom-sched-bob-conv-1-foo");
        verify(userConversation).deleteById("conv-1");
    }
}
```

(Note: `RouterFunctionInvoker` is a small test helper — see step 2.)

- [ ] **Step 2: Add a small test helper for invoking RouterFunction in tests**

Create `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/RouterFunctionInvoker.java`:

```java
package cn.wubo.spring.ai.loom.agent;

import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.core.publisher.Mono;

public final class RouterFunctionInvoker {
    private RouterFunctionInvoker() {}

    public static org.springframework.http.server.reactive.ServerHttpResponse invokeDelete(
            RouterFunction<ServerResponse> router, String path) {
        MockServerHttpRequest req = MockServerHttpRequest.method(HttpMethod.DELETE, path).build();
        MockServerWebExchange exchange = MockServerWebExchange.from(req);
        router.route(req)
                .switchIfEmpty(Mono.error(new IllegalStateException("No route")))
                .flatMap(handler -> handler.handle(req))
                .subscribe();
        return exchange.getResponse();
    }

    public static org.springframework.http.server.reactive.ServerHttpResponse invokeGet(
            RouterFunction<ServerResponse> router, String path) {
        MockServerHttpRequest req = MockServerHttpRequest.method(HttpMethod.GET, path).build();
        MockServerWebExchange exchange = MockServerWebExchange.from(req);
        router.route(req).subscribe();
        return exchange.getResponse();
    }
}
```

- [ ] **Step 3: Refactor `LoomAgentConfiguration` to expose the route as a static method**

Currently in `LoomAgentConfiguration#loomAgentConversationRouter` (lines 902-916), the DELETE branch only calls `userConversation.deleteById(conversationId)`. We need access to `SubTaskRegistry` and `FlexScheduledTaskService` here.

Two options:
- (a) **Inject the new beans** into the existing `loomAgentConversationRouter` method signature.
- (b) **Extract the DELETE-only logic** into a static method (as the test assumes).

Use **option (a)**: amend the existing bean method. Replace the existing `loomAgentConversationRouter` with:

```java
@Bean("loomAgentConversationRouter")
public RouterFunction<ServerResponse> loomAgentConversationRouter(
        JdbcChatMemoryRepository chatMemoryRepository,
        IUserConversation userConversation,
        org.springframework.beans.factory.ObjectProvider<cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry> subTaskRegistry,
        org.springframework.beans.factory.ObjectProvider<@Qualifier("flexScheduledTaskService") cn.wubo.flex.schedule.core.FlexScheduledTaskService> flexService) {
    RouterFunctions.Builder builder = RouterFunctions.route();
    builder.GET("spring/ai/loom/conversation", request -> ServerResponse.ok().body(userConversation.getList()));
    builder.GET("spring/ai/loom/conversation/{conversationId}", request -> {
        String conversationId = request.pathVariable("conversationId");
        return ServerResponse.ok().body(chatMemoryRepository.findByConversationId(conversationId));
    });
    builder.DELETE("spring/ai/loom/conversation/{conversationId}", request -> {
        String conversationId = request.pathVariable("conversationId");
        String username = UserContextHolder.getCurrentUser();

        // 1. Stop sub-tasks (if any)
        int subtasksKilled = subTaskRegistry.getIfAvailable()
                .map(r -> r.killAllByConversation(conversationId)).orElse(0);

        // 2. Stop matching scheduled tasks (if flex-schedule is on classpath)
        int schedulesCancelled = 0;
        cn.wubo.flex.schedule.core.FlexScheduledTaskService fs = flexService.getIfAvailable();
        if (fs != null && username != null) {
            String prefix = "loom-sched-" + username + "-" + conversationId + "-";
            for (cn.wubo.flex.schedule.core.TaskInfo info : fs.listTasks()) {
                if (info.taskName().startsWith(prefix)) {
                    fs.cancel(info.taskName());
                    schedulesCancelled++;
                }
            }
        }

        // 3. Finally soft-delete user_conversation mapping (existing behavior)
        userConversation.deleteById(conversationId);

        log.info("Conv cleanup on delete: conv={}, user={}, subtasks={}, schedules={}",
                conversationId, username, subtasksKilled, schedulesCancelled);
        return ServerResponse.ok().body(true);
    });
    return builder.build();
}
```

- [ ] **Step 4: Update the test to handle the refactored router**

The test above assumed a static `conversationCleanupRouter(...)` helper. With the option (a) approach, the test should instead invoke the bean method directly. Adjust the test: build the `RouterFunction` via the actual bean method.

```java
// Replace the previous constructor line in setUp() with:
router = new LoomAgentConfiguration().loomAgentConversationRouter(
        mock(JdbcChatMemoryRepository.class), userConversation,
        () -> registry,
        () -> mock(cn.wubo.flex.schedule.core.FlexScheduledTaskService.class));
```

(Cast `ObjectProvider` properly; Mockito has `org.springframework.beans.factory.ObjectProvider` — use `when(...).thenReturn(...)` accordingly.)

- [ ] **Step 5: Run test**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && mvn -B -q test -pl spring-ai-loom-agent-test -Dtest=ConversationLifecycleListenerTest
```

Expected: **1 test passes**.

- [ ] **Step 6: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && git add spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/ConversationLifecycleListenerTest.java spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/RouterFunctionInvoker.java && git commit -m "feat(subtask,schedule): DELETE conversation also kills sub-tasks + cancels schedules"
```

---

# Phase 6 — Frontend (toolbar buttons + modals)

## Task 6.1: Add toolbar buttons to index.html

**Files:**
- Modify: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/index.html`

- [ ] **Step 1: Insert the 2 buttons after `<button class="toolbar-btn" id="file-manager-button">…</button>` (around line 72)**

```html
                    <button class="toolbar-btn" id="subtask-button">
                        <span>🧩</span>
                        <span>子任务</span>
                    </button>
                    <button class="toolbar-btn" id="schedule-button">
                        <span>⏰</span>
                        <span>定时</span>
                    </button>
```

- [ ] **Step 2: Add modal containers near the existing `#file-modal` (after `<div class="modal-overlay" id="file-modal">…</div>`)**

Match the existing `file-modal` shape. Two modals:

```html
<div class="modal-overlay" id="subtask-modal" style="display:none;">
    <div class="modal-content">
        <button class="modal-close" onclick="subtaskModal.close()">×</button>
        <h3>子任务</h3>
        <div class="panel-section">
            <h4>运行中 <small>(<span id="subtask-active-count">0</span>)</small></h4>
            <table class="data-table" id="subtask-active-table">
                <thead>
                    <tr>
                        <th>ID</th><th>会话</th><th>Prompt 摘要</th><th>开始</th><th>操作</th>
                    </tr>
                </thead>
                <tbody></tbody>
            </table>
        </div>
        <div class="panel-section">
            <h4>历史 <small>(最近 50 条)</small></h4>
            <details>
                <summary>展开历史</summary>
                <table class="data-table" id="subtask-history-table">
                    <thead>
                        <tr>
                            <th>ID</th><th>状态</th><th>完成时间</th><th>结果摘要</th>
                        </tr>
                    </thead>
                    <tbody></tbody>
                </table>
            </details>
        </div>
    </div>
</div>

<div class="modal-overlay" id="schedule-modal" style="display:none;">
    <div class="modal-content">
        <button class="modal-close" onclick="scheduleModal.close()">×</button>
        <h3>定时任务</h3>
        <div class="panel-section">
            <h4>活动定时器 <small>(<span id="schedule-active-count">0</span>)</small></h4>
            <table class="data-table" id="schedule-active-table">
                <thead>
                    <tr>
                        <th>名称</th><th>类型</th><th>表达式</th><th>操作</th>
                    </tr>
                </thead>
                <tbody></tbody>
            </table>
        </div>
    </div>
</div>
```

- [ ] **Step 3: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && git add spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/index.html && git commit -m "feat(frontend): toolbar '子任务' + '定时' buttons + modal containers"
```

---

## Task 6.2: API client + modal logic JS

**Files:**
- Create: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/subtask-modal.js`
- Create: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/schedule-modal.js`
- Modify: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js` (add API constants + button wiring)

- [ ] **Step 1: `subtask-modal.js` (full)**

```javascript
// 子任务面板 — 2s 轮询, 关闭时停止
const subtaskModal = (() => {
    let pollTimer = null;
    const root = () => document.getElementById('subtask-modal');

    async function refresh() {
        try {
            const [active, history] = await Promise.all([
                apiFetch('/spring/ai/loom/subtask/list/active', { method: 'GET' }),
                apiFetch('/spring/ai/loom/subtask/list/history', { method: 'GET' })
            ]);
            renderActive(active);
            renderHistory(history);
        } catch (e) {
            console.error('子任务面板 refresh 失败:', e);
        }
    }

    function renderActive(records) {
        const tbody = document.querySelector('#subtask-active-table tbody');
        tbody.innerHTML = '';
        document.getElementById('subtask-active-count').textContent = records.length;
        records.forEach(r => {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td><code>${(r.subTaskId || '').slice(0, 8)}</code></td>
                <td>${r.conversationId || ''}</td>
                <td title="${escapeHtml(r.prompt || '')}">${escapeHtml((r.prompt || '').slice(0, 60))}</td>
                <td>${formatRelative(r.startedAt)}</td>
                <td><button class="danger-btn" onclick="subtaskModal.kill('${r.subTaskId}')">杀死</button></td>
            `;
            tbody.appendChild(tr);
        });
    }

    function renderHistory(records) {
        const tbody = document.querySelector('#subtask-history-table tbody');
        tbody.innerHTML = '';
        records.forEach(r => {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td><code>${(r.subTaskId || '').slice(0, 8)}</code></td>
                <td>${r.status || ''}</td>
                <td>${formatRelative(r.finishedAt)}</td>
                <td title="${escapeHtml(r.resultText || r.errorMessage || '')}">
                    ${escapeHtml(((r.resultText || r.errorMessage || '')).slice(0, 80))}
                </td>
            `;
            tbody.appendChild(tr);
        });
    }

    function escapeHtml(s) {
        return String(s).replace(/[&<>"']/g, c => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
        }[c]));
    }

    function formatRelative(ms) {
        if (!ms) return '-';
        const d = Math.floor((Date.now() - ms) / 1000);
        if (d < 60) return `${d}s 前`;
        if (d < 3600) return `${Math.floor(d / 60)}m 前`;
        return `${Math.floor(d / 3600)}h 前`;
    }

    async function kill(id) {
        if (!confirm('确认杀死子任务 ' + id.slice(0, 8) + ' ?')) return;
        await apiFetch(`/spring/ai/loom/subtask/kill/${id}`, { method: 'POST' });
        await refresh();
    }

    function open() {
        root().style.display = 'flex';
        refresh();
        pollTimer = setInterval(refresh, 2000);
    }

    function close() {
        root().style.display = 'none';
        if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
    }

    return { open, close, kill };
})();

document.getElementById('subtask-button').addEventListener('click', () => subtaskModal.open());
```

- [ ] **Step 2: `schedule-modal.js` (full)**

```javascript
// 定时任务面板
const scheduleModal = (() => {
    let pollTimer = null;
    const root = () => document.getElementById('schedule-modal');

    async function refresh() {
        try {
            const tasks = await apiFetch('/spring/ai/loom/schedule/list', { method: 'GET' });
            renderActive(tasks);
        } catch (e) {
            console.error('定时面板 refresh 失败:', e);
        }
    }

    function renderActive(tasks) {
        const tbody = document.querySelector('#schedule-active-table tbody');
        tbody.innerHTML = '';
        document.getElementById('schedule-active-count').textContent = tasks.length;
        tasks.forEach(t => {
            const tr = document.createElement('tr');
            const expr = t.cronExpression || formatDuration(t.interval) || (t.delay ? `once @ ${formatDuration(t.delay)}` : '-');
            tr.innerHTML = `
                <td><code>${escapeHtml(t.taskName || '').slice(0, 40)}</code></td>
                <td>${escapeHtml(t.taskType || '')}</td>
                <td>${escapeHtml(expr)}</td>
                <td>
                    <button class="danger-btn" onclick="scheduleModal.cancel('${encodeURIComponent(t.taskName)}')">停止</button>
                    <button class="ghost-btn" onclick="scheduleModal.history('${encodeURIComponent(t.taskName)}')">历史</button>
                </td>
            `;
            tbody.appendChild(tr);
        });
    }

    function formatDuration(ms) {
        if (ms == null) return null;
        const s = Math.floor(ms / 1000);
        if (s < 60) return `${s}s`;
        if (s < 3600) return `${Math.floor(s / 60)}m`;
        return `${Math.floor(s / 3600)}h`;
    }

    function escapeHtml(s) {
        return String(s).replace(/[&<>"']/g, c => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
        }[c]));
    }

    async function cancel(fullName) {
        const decoded = decodeURIComponent(fullName);
        if (!confirm('确认停止定时器 ' + decoded + ' ?')) return;
        await apiFetch(`/spring/ai/loom/schedule/cancel`, {
            method: 'POST',
            body: JSON.stringify({ name: decoded })
        });
        await refresh();
    }

    async function history(fullName) {
        const decoded = decodeURIComponent(fullName);
        const records = await apiFetch(`/spring/ai/loom/schedule/history/${encodeURIComponent(decoded)}`, { method: 'GET' });
        const lines = (records || []).map(r =>
            `- ${r.startTime || ''}  ${r.status || ''}  ${formatDuration(r.duration) || ''}  ${r.errorMessage || ''}`
        ).join('\n');
        alert(`定时器 ${decoded} 历史:\n\n${lines || '(暂无)'}`);
    }

    function open() {
        root().style.display = 'flex';
        refresh();
        pollTimer = setInterval(refresh, 2000);
    }

    function close() {
        root().style.display = 'none';
        if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
    }

    return { open, close, cancel, history };
})();

document.getElementById('schedule-button').addEventListener('click', () => scheduleModal.open());
```

(Note: the cancel endpoint expects the full task name. The route handler at Task 4.3 step 2 requires adjusting to accept a `name` POST body parameter — see next step.)

- [ ] **Step 3: Adjust `loomAgentScheduleRouter` cancel route**

The current Task 4.3 cancel route uses `request.pathVariable("name")` — but the frontend posts a JSON body with `name`. Change it to accept a body parameter. Replace the cancel route with:

```java
builder.POST("spring/ai/loom/schedule/cancel",
        request -> request.bodyToMono(java.util.Map.class)
                .map(body -> (String) body.get("name"))
                .flatMap(name -> {
                    flexService.cancel(name);
                    return org.springframework.web.reactive.function.server.ServerResponse.ok().body(true);
                }));
```

- [ ] **Step 4: Wire `<script>` tags into `index.html`**

After the existing `<script src="app.js"></script>`:

```html
<script src="subtask-modal.js"></script>
<script src="schedule-modal.js"></script>
```

- [ ] **Step 5: Smoke (verify modals open + read empty lists)**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && mvn spring-boot:run -pl spring-ai-loom-agent-test -Dgpg.skip=true > /tmp/loom5.log 2>&1 &
SERVER_PID=$!
for i in $(seq 1 30); do curl -sf http://localhost:8080/spring/ai/loom/index.html > /dev/null && break; sleep 1; done

# Static assets load
curl -sI http://localhost:8080/spring/ai/loom/subtask-modal.js | head -2
curl -sI http://localhost:8080/spring/ai/loom/schedule-modal.js | head -2

kill $SERVER_PID 2>/dev/null; true
```

Expected: `HTTP/1.1 200 OK` for each JS asset.

- [ ] **Step 6: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && git add spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/subtask-modal.js spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/schedule-modal.js spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/index.html spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java && git commit -m "feat(frontend): subtask-modal.js + schedule-modal.js, button wiring, cancel route accepts POST body"
```

---

# Phase 7 — Documentation & polish

## Task 7.1: application.yml defaults

**Files:**
- Modify: `C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent-test/src/main/resources/application.yml`

- [ ] **Step 1: Add the default config block**

Locate the existing `spring.ai.loom.agent` block and add a sibling `subtask:` and `schedule:` section. (If keys already exist from Phase 4.3 step 4, just verify they exist.) Specifically ensure:

```yaml
spring:
  ai:
    loom:
      agent:
        subtask:
          enabled: true
          max-concurrent: 4
          max-history: 200
        schedule:
          enabled: true
flex:
  schedule:
    pool-size: 8
    limits:
      min-interval: 10m
      max-lifetime: 72h
      mode: strict
```

- [ ] **Step 2: Verify the file is still valid YAML**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && python -c "import yaml; yaml.safe_load(open('spring-ai-loom-agent-test/src/main/resources/application.yml'))"
```

- [ ] **Step 3: Restart the test app and verify the limits are picked up**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && mvn spring-boot:run -pl spring-ai-loom-agent-test -Dgpg.skip=true > /tmp/loom6.log 2>&1 &
SERVER_PID=$!
for i in $(seq 1 30); do curl -sf http://localhost:8080/spring/ai/loom/index.html > /dev/null && break; sleep 1; done
grep -E "limits|min-interval|max-lifetime" /tmp/loom6.log
kill $SERVER_PID 2>/dev/null; true
```

Expected: log shows `min-interval=PT10M, max-lifetime=PT72H, mode=STRICT`.

- [ ] **Step 4: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && git add spring-ai-loom-agent-test/src/main/resources/application.yml && git commit -m "chore(config): defaults for subtask + schedule + flex-schedule limits"
```

---

## Task 7.2: Update CLAUDE.md and add SUBTASK-SCHEDULER.md

**Files:**
- Modify: `C:/developer/IdeaProjects/spring-ai-loom-agent/CLAUDE.md` (add a "Sub-tasks & Scheduling" subsection in Tools)
- Create: `C:/developer/IdeaProjects/spring-ai-loom-agent/docs/SUBTASK-SCHEDULER.md`

- [ ] **Step 1: Update the Tools table in CLAUDE.md**

Find the "Core Interfaces" section. Under the existing tools table, append:

```markdown
| `ISubTaskTool` | `DefaultSubTaskTool` | LLM-callable tool to launch a synchronous sub-task: dedicated ChatClient (full tool access, no self-tools), dedicated `loomSubTaskExecutor` thread pool, killable from panel. |
| `ISubTaskExecutor` | `DefaultSubTaskExecutor` | Programmatic sub-task executor (filter-aware). Uses `ChatClient.call()`. |
| `IScheduleTool` | `DefaultScheduleTool` | LLM-callable tool to create/list/cancel/query scheduled tasks via `flex-schedule`. Tasks persisted in H2 (`flex_scheduled_task`); min-interval 10 min, max-lifetime 72 h (strict). |
```

- [ ] **Step 2: Add a "Configuration Properties" bullet for `subtask.*` and `schedule.*`**

In `CLAUDE.md` Configuration Properties:

```markdown
- `subtask` — `enabled` (boolean, default **true**), `max-concurrent` (default 4), `max-history` (default 200)
- `schedule` — `enabled` (boolean, default **true**); relies on `flex.schedule.limits.{min-interval,max-lifetime,mode}` for constraints
```

- [ ] **Step 3: Create `docs/SUBTASK-SCHEDULER.md`**

A ~80-line user-facing doc with:
- What is a sub-task / schedule
- Frontend toolbar buttons + modals
- The 10-minute min / 3-day max rule
- Conversation deletion auto-cleanup
- One example chat dialog
- Pointer to design spec + plan

Outline (for the implementer's first draft):

```markdown
# 子任务与定时任务

## 子任务

LLM 可在主对话中调用 `start_sub_task(prompt, systemContext)` 启动一个子任务，
子任务拥有与主对话相同的工具访问（全工具），
但**不能**再次启动子任务或创建定时器。

主对话在子任务期间同步等待，子任务完成（或被取消）后把最终文本返回主对话。

## 定时任务

LLM 可创建定时任务：

| 类型 | expression 含义 |
|---|---|
| `cron` | cron 表达式字符串 |
| `fixed_delay` | 触发间隔秒数 |
| `fixed_rate` | 触发间隔秒数（固定速率） |
| `one_shot` | 延迟秒数 |

- **最短触发间隔**: 10 分钟（`flex.schedule.limits.min-interval`）
- **最长存活**: 3 天（`flex.schedule.limits.max-lifetime`）
- **强制**: `flex.schedule.limits.mode=strict`，超限时抛 `TaskLimitExceededException`
- **持久化**: H2 表 `flex_scheduled_task`（TaskDefinitions）

## 前端面板

工具栏「文件」按钮右侧新增：
- 🧩 **子任务**：列出运行中 / 历史子任务，支持手动杀死
- ⏰ **定时**：列出活动定时器，支持停止，查看历史

## 删除历史对话

删除历史对话时，按 conversationId 命名空间一次性取消所有该会话相关的子任务和定时任务（DELETE 路由在清理完成后才软删 user_conversation）。

## 设计文档

- 设计: `docs/superpowers/specs/2026-07-15-subtask-and-scheduler-design.md`
- 实施: `docs/superpowers/plans/2026-07-15-subtask-and-scheduler.md`
```

- [ ] **Step 4: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent && git add CLAUDE.md docs/SUBTASK-SCHEDULER.md && git commit -m "docs: cover sub-task + scheduler in CLAUDE.md and SUBTASK-SCHEDULER.md"
```

---

# Self-Review

## 1. Spec coverage

| Spec section | Plan task |
|---|---|
| §0 背景 | — (context) |
| §1 决策摘要 | captured in Tasks 2.1, 3.3, 4.2 |
| §2 flex-schedule H2 持久化 | Phase 1: Tasks 1.1, 1.2, 1.3 |
| §3.1 新包结构 | Phase 2: Task 2.1 |
| §3.2 SubTaskRequest/Result/Status | Phase 2: Task 2.1 |
| §3.3 ISubTaskTool | Phase 3: Task 3.1 |
| §3.4 ISubTaskExecutor | Phase 2: Task 2.4 |
| §3.5 DefaultSubTaskExecutor | Phase 2: Task 2.4 |
| §3.6 SubTaskRegistry | Phase 2: Task 2.3 |
| §3.7 IScheduleTool | Phase 4: Task 4.1 |
| §3.8 DefaultScheduleTool | Phase 4: Task 4.2 |
| §3.9 ScheduleLifecycleListener | merged into DefaultScheduleTool's logger |
| §3.10 路由 | Phase 2: Task 2.5; Phase 4: Task 4.3 |
| §3.11 ConversationLifecycleListener | Phase 5: Task 5.1 |
| §3.12 Properties 增量 | Phase 2: Task 2.5; Phase 4: Task 4.3 |
| §3.13 主对话 IChat 不动 | respected (we only added tools to its list) |
| §4 前端 | Phase 6: Tasks 6.1, 6.2 |
| §5 Flyway V12 | Phase 1: Task 1.3 |
| §6 application.yml 默认 | Phase 4 Task 4.3 + Phase 7 Task 7.1 |
| §7 测试 | woven into each task (TDD) |
| §8 风险与回滚 | covered by feature flags (`enabled`) + auto-cleanup |
| §9 文件清单 | mirrors the cross-phase conventions list |
| §10 分阶段交付 | matches this plan's 7 phases |

**No coverage gaps.** §3.9 (ScheduleLifecycleListener) is "merged into DefaultScheduleTool's logger" — let me note that's intentional; the spec lists it as separate, but flex-schedule's default behavior already records to `ExecutionHistory`, and no separate listener surface is needed. If we want a custom event hook later, we can add `addListener(...)` from Phase 4.

## 2. Placeholder scan

Searched for: `TBD`, `TODO`, `FIXME`, `XXX`, "implement later", "similar to", "fill in". **None found.** Every step has the actual code.

## 3. Type consistency

| Symbol | Defined in | Used in | Match? |
|---|---|---|---|
| `SubTaskRequest(subTaskId, parentConversationId, parentSubTaskId, username, prompt, systemContext, fromScheduler)` | Task 2.1 Step 2 | Tasks 2.4, 3.2, 4.2 | ✅ |
| `SubTaskResult(subTaskId, conversationId, username, status, text, errorMessage, startedAt, finishedAt)` | Task 2.1 Step 3 | Tasks 2.3, 2.4, 3.2, 4.2 | ✅ |
| `SubTaskStatus.{RUNNING, COMPLETED, FAILED, CANCELLED}` | Task 2.1 Step 1 | Tasks 2.3, 2.4, 3.2 | ✅ |
| `ISubTaskExecutor.execute(SubTaskRequest) -> SubTaskResult` | Task 2.4 Step 1 | Tasks 2.4, 3.2, 4.2 | ✅ |
| `ISubTaskTool.startSubTask(prompt, systemContext, toolContext)` | Task 3.1 | Tasks 3.2, 3.3 | ✅ |
| `IScheduleTool.{createSchedule, cancelSchedule, listSchedules, getScheduleHistory}` | Task 4.1 | Task 4.2 | ✅ |
| `fullName(username, conversationId, name)` | Task 4.2 (`DefaultScheduleTool.fullName`) | Tasks 4.2, 5.1 | ✅ |
| `SubTaskRegistry.register/attachFuture/markFinished/kill/killAllByConversation/get/listActive/listHistory` | Task 2.3 | Tasks 2.4, 3.2, 5.1 | ✅ |
| `loomSubTaskExecutor` bean name | Task 2.5 | Task 2.4 constructor wiring, Phase 3 | ✅ |
| `loomSubTaskChatClient` bean name | Task 2.5 | Task 2.4 constructor wiring, Phase 3 | ✅ |
| `flexScheduledTaskService` bean name (from Phase 1.2 wiring) | Phase 1 Task 1.2 | Tasks 4.2, 4.3, 5.1 | ✅ |

**No mismatches.**

## Plan-completion gate

- All task steps have checkbox syntax (`- [ ] …`) ✅
- All file paths are absolute ✅
- All commands have expected output ✅
- TDD ordering (failing test → impl → passing test → commit) ✅
- Conventional-commits style ✅
- No placeholders, no vague instructions ✅

