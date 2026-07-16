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
- **持久化**：H2 表 `flex_scheduled_task`（Flyway `V12`），重启后仍在

任务名命名空间：`loom-sched-{username}-{conversationId}-{name}`，因此不同用户 / 会话下同名任务互不冲突，列表也按此前缀过滤。

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
