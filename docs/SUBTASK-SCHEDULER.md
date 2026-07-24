# Sub-task & Schedule

> 中文版：[SUBTASK-SCHEDULER.zh-CN.md](SUBTASK-SCHEDULER.zh-CN.md) (same directory)

Spring AI LoomAgent supports two async/delegation capabilities that the LLM can proactively invoke: **sub-tasks** and **scheduled tasks**.

## Sub-task

In the main conversation, the LLM may call the tool `start_sub_task(prompt, systemContext)` to delegate a piece of work to a "sub-model" for execution:

- The sub-task has the same tool access as the main conversation (files / MCP / Skill / time, etc.), but it **cannot** spawn another sub-task or create a scheduled task (filtered at the tool-collection level to prevent self-recursion).
- The main conversation **waits synchronously** while the sub-task runs; once the sub-task completes (or is cancelled), its final text is returned to the main conversation.
- The sub-task runs on the dedicated thread pool `loomSubTaskExecutor` and can be interrupted.
- The sub-task's ChatMemory is written under the namespace `{conversationId}--sub--{subTaskId}`.

Config (`spring.ai.loom.agent.subtask.*`):

| key | default | description |
|---|---|---|
| `enabled` | `true` | Whether to register sub-task-related beans |
| `max-concurrent` | `4` | Maximum in-flight sub-tasks; launch requests beyond this are rejected |
| `max-history` | `200` | History records kept per user (FIFO eviction of oldest) |

## Schedule

The LLM can create scheduled tasks; when triggered, they run as a **sub-task** with the given prompt:

| type | expression meaning |
|---|---|
| `cron` | cron expression string |
| `fixed_delay` | trigger interval in seconds |
| `fixed_rate` | trigger interval in seconds (fixed rate) |
| `one_shot` | delay in seconds (fires once) |

Constraints (enforced by flex-schedule's `flex.schedule.limits.*`):

- **Minimum trigger interval**: `min-interval` (test app default 10 minutes)
- **Maximum lifetime**: `max-lifetime` (test app default 3 days / 72h)
- **Mode**: `mode=strict` throws an exception when exceeded (creation fails with a friendly message)
- **Persistence**: loom-agent manages its own H2 table `loom_scheduled_task` (added in `V2.0`; originally `V13`); see "Persistence (Path B — loom-owned)" below. Tasks survive restarts, with `createdAt` preserved so `max-lifetime` accumulates across restarts.

Task name namespace: `loom-sched-{username}-{conversationId}-{name}` — tasks with the same name in different users/conversations never collide, and the list query filters by this prefix.

> **⚠ Username constraint**: the `{username}-{conversationId}` segment is split on the first `-`, so **`username` MUST NOT contain `-`**. `DefaultUser.createUser` enforces this as of commit `dc20b8f` (July 2026): usernames containing `-` are rejected with `LoomAgentRuntimeException(400, ...)`. The frontend's `schedulePanel._shortName` parser relies on the same invariant (strip prefix + first `-` + 36-char UUID). If usernames with `-` are ever allowed, `_shortName` must be refactored to find the last 36-char UUID-shaped token — never use positional `split('-').slice(4)`.

Config (`spring.ai.loom.agent.schedule.*`): `enabled` (default `true`).

## Frontend panels

Two entries were added to the right of the toolbar "Files" button:

- 🧩 **Sub-task**: list running/historical sub-tasks; supports manually "killing" running sub-tasks (2-second polling refresh).
- ⏰ **Schedule**: list the current user's active schedules; supports "Stop" and viewing "History" execution records (2-second polling refresh).

## Auto-cleanup when deleting a conversation

When you delete a conversation via `DELETE /spring/ai/loom/conversation/{id}`, **before** soft-deleting the `user_conversation` mapping, the system:

1. Kills all in-flight sub-tasks under that conversationId.
2. Cancels all scheduled tasks under the `loom-sched-{user}-{conv}-` prefix.

Both capabilities can be independently disabled via `enabled=false`; the corresponding cleanup step is skipped when disabled.

## Persistence (Path B — loom-owned)

H2 persistence for scheduled tasks is managed by loom-agent itself (replacing the earlier approach that piggybacked on flex-schedule):

- **Table**: `loom_scheduled_task` (added in `V2.0`; replaced the old `flex_scheduled_task` which is no longer created)
- **Columns**: `task_name` PK + `schedule_type` (`cron` / `fixed_delay` / `fixed_rate` / `one_shot`) + three expression columns (one per type) + `prompt` CLOB + `username` + `conversation_id` + `paused` + `created_at` / `updated_at`
- **Repository**: `ILoomScheduleTriggerRepository` (in `cn.wubo.spring.ai.loom.agent.schedule`); default impl `JdbcLoomScheduleTriggerRepository` is wired by `LoomAgentConfiguration.ScheduleConfiguration` via `@Bean` + `@ConditionalOnMissingBean`
- **Writes**: `repo.save(...)` after every successful `createSchedule`; `repo.delete(...)` after every successful `cancelSchedule`; `repo.deleteAllForConversation(user, conv)` on conversation deletion
- **Restore**: `ScheduleRestoreListener` listens for `ApplicationReadyEvent`, iterates `repo.findAll()`, deletes expired rows directly via `repo.delete(...)` (based on `flex.schedule.limits.max-lifetime`), and re-registers the rest via `flexService.task(name).{cron|fixedDelay|fixedRate|oneShot}(...).createdAt(storedCreatedAt).register(lambda)`, re-pausing paused tasks via `flexService.pause(name)`
- **No more flex-schedule `restoreTasks()`**: because 1) it requires `beanName`/`methodName` whereas loom-agent triggers via lambda; 2) its `scheduleByType` has no ONE_SHOT branch

**Why Path B instead of Path A**: flex-schedule currently exposes only `TaskBuilder.createdAt(Instant)` and an in-memory `TaskRepository`; the persistence layer belongs to whoever uses it (loom-agent uses H2; cluster deployments can swap to Redis/JDBC). flex-schedule does not pick the engine for its consumers.