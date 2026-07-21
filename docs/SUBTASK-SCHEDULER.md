# 子任务与定时任务

Spring AI LoomAgent 支持两种由 LLM 主动发起的异步/委派能力：**子任务**与**定时任务**。

## 子任务（Sub-task）

主对话中，LLM 可调用工具 `start_sub_task(prompt, systemContext)` 把一段任务委派给一个"子模型"执行：

- 子任务拥有与主对话相同的工具访问（文件 / MCP / Skill / 时间等），**但不能**再次启动子任务或创建定时器（从工具集合层面过滤，杜绝自递归）。
- 主对话在子任务运行期间**同步等待**，子任务完成（或被取消）后把最终文本返回主对话继续。
- 子任务在专用线程池 `loomSubTaskExecutor` 上运行，可被中断。
- 子任务的 ChatMemory 写入命名空间 `{conversationId}--sub--{subTaskId}`。

配置（`spring.ai.loom.agent.subtask.*`）：

| key | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 是否注册子任务相关 bean |
| `max-concurrent` | `4` | 同时在飞子任务上限，超过则启动请求被拒 |
| `max-history` | `200` | 每用户历史保留条数，FIFO 丢弃最旧 |

## 定时任务（Schedule）

LLM 可创建定时任务，触发时以**子任务**方式运行给定提示词：

| 类型 | expression 含义 |
|---|---|
| `cron` | cron 表达式字符串 |
| `fixed_delay` | 触发间隔秒数 |
| `fixed_rate` | 触发间隔秒数（固定速率） |
| `one_shot` | 延迟秒数（只触发一次） |

约束（由 flex-schedule 的 `flex.schedule.limits.*` 强校验）：

- **最短触发间隔**：`min-interval`（测试应用默认 10 分钟）
- **最长存活**：`max-lifetime`（测试应用默认 3 天 / 72h）
- **模式**：`mode=strict` 时超限抛异常（创建失败并返回友好文案）
- **持久化**：loom-agent 自管 H2 表 `loom_scheduled_task`（Flyway `V13`）；见下方「持久化 (Path B — loom-owned)」章节。重启后仍在，且 `createdAt` 保留以便 `max-lifetime` 跨重启累计计时。

任务名命名空间：`loom-sched-{username}-{conversationId}-{name}`，因此不同用户 / 会话下同名任务互不冲突，列表也按此前缀过滤。

> **⚠ Username 约束**：namespace 的 `{username}-{conversationId}` 段以第一个 `-` 作为分隔符，因此 **`username` 不得包含 `-`**。`DefaultUser.createUser` 已在 2026-07 测试 commit `dc20b8f` 后强制校验：含 `-` 的 username 会抛 `LoomAgentRuntimeException(400, ...)`。前端 `schedulePanel._shortName` 解析同样依赖此不变式（截掉 prefix + 第一个 `-` + 36 字符 UUID），未来如需放开 username-dash，必须同步把 `_shortName` 改为基于"找最后一个 36 字符 UUID 形串"的解析方式，不能再用 `split('-').slice(4)` 这种按位置切的脆弱做法。

配置（`spring.ai.loom.agent.schedule.*`）：`enabled`（默认 `true`）。

## 前端面板

工具栏「文件」按钮右侧新增两个入口：

- 🧩 **子任务**：列出运行中 / 历史子任务，支持手动「杀死」运行中的子任务（2 秒轮询刷新）。
- ⏰ **定时**：列出当前用户的活动定时器，支持「停止」与查看「历史」执行记录（2 秒轮询刷新）。

## 删除历史对话时的自动清理

删除某个历史对话时，`DELETE /spring/ai/loom/conversation/{id}` 会在软删 `user_conversation` 映射**之前**：

1. 杀掉该 conversationId 名下所有在飞子任务；
2. 取消该 `loom-sched-{user}-{conv}-` 前缀下的所有定时任务。

两个能力均可通过 `enabled=false` 单独关闭，关闭时对应清理自动跳过。

## 设计与实施文档

- 设计：[docs/superpowers/specs/2026-07-15-subtask-and-scheduler-design.md](docs/superpowers/specs/2026-07-15-subtask-and-scheduler-design.md)
- 实施：[docs/superpowers/plans/2026-07-15-subtask-and-scheduler.md](docs/superpowers/plans/2026-07-15-subtask-and-scheduler.md)

## 持久化 (Path B — loom-owned)

定时任务的 H2 持久化由 loom-agent 自管（替代早期借住在 flex-schedule 的方案）：

- **表名**：`loom_scheduled_task`（Flyway V13 取代了旧的 `flex_scheduled_task`）
- **列**：`task_name` PK + `schedule_type` (`cron` / `fixed_delay` / `fixed_rate` / `one_shot`) + 三种 expression 列（按类型取一）+ `prompt` CLOB + `username` + `conversation_id` + `paused` + `created_at` / `updated_at`
- **Repository**：`ILoomScheduleTriggerRepository`（`cn.wubo.spring.ai.loom.agent.schedule`）；默认实现 `JdbcLoomScheduleTriggerRepository` 由 `LoomAgentConfiguration.ScheduleConfiguration` 用 `@Bean` + `@ConditionalOnMissingBean` 注册
- **写入**：每次 `createSchedule` 成功后 `repo.save(...)`；每次 `cancelSchedule` 成功后 `repo.delete(...)`；删除会话时 `repo.deleteAllForConversation(user, conv)`
- **恢复**：`ScheduleRestoreListener` 监听 `ApplicationReadyEvent`，遍历 `repo.findAll()`，对过期行直接 `repo.delete(...)`（基于 `flex.schedule.limits.max-lifetime`），其余通过 `flexService.task(name).{cron|fixedDelay|fixedRate|oneShot}(...).createdAt(storedCreatedAt).register(lambda)` 重新装载，对 paused 行再调 `flexService.pause(name)`
- **不再用 flex-schedule 的 `restoreTasks()`**：因为 1) 它要求 `beanName`/`methodName`，而 loom-agent 用 lambda 触发；2) 它的 `scheduleByType` 没有 ONE_SHOT 分支

**为什么是 Path B 而不是路径 A**：flex-schedule 现在只提供 `TaskBuilder.createdAt(Instant)` 这个 hook 和默认内存 `TaskRepository`；持久化层是谁就用谁的（loom-agent 用 H2，集群部署可换 Redis/JDBC）。flex-schedule 不替消费方选引擎。
