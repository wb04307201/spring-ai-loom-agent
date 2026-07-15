# 子任务 + 定时任务 — 设计文档

- **日期**: 2026-07-15
- **目标仓库**: `spring-ai-loom-agent`（核心变更）+ `flex-schedule`（增量扩展）
- **关联模块**: `spring-ai-loom-agent`（core）/ `spring-ai-loom-agent-spring-boot-autoconfigure`（路由 + 配置）/ `flex-schedule` + `flex-schedule-spring-boot-autoconfigure`（持久化）/ `spring-ai-loom-agent-test`（验证）

---

## 0. 背景与目标

在 loom-agent 当前的工具集（Time / File / Skill / Git / Maven / Compile & Deploy / 各种 MCP 工具）基础上，新增两个由 LLM 直接调用的能力：

1. **子任务（sub-task）**：主对话 AI 认为需要把一段任务委派给 "子模型" 时，调用 `start_sub_task(prompt, system_context)`。子模型拥有与主对话完全一致的**全工具访问**，但**不能自我递归**（不能调起子任务、不能开定时）。主对话在子任务完成前同步阻塞，子任务结果作为字符串返回给主对话。
2. **定时任务（schedule）**：主对话 AI 可以调用 `create_schedule(name, type, expr, prompt, ...)` 创建一个定时任务，调度库满足用户两条硬约束：最短 10 分钟一次、最长存活 3 天（强校验 + abort 注册）。定时器触发后，会把 `prompt` 作为子任务在 `loomSubTaskExecutor` 线程池里跑一次（行为与用户手动调 `start_sub_task` 等价）。

并增加两个**前端面板**（沿用现有 modal 模式）：「子任务」面板显示运行中/历史的子任务并支持 kill；「定时」面板显示活动定时器和执行历史，支持 stop。

最后是**生命周期联动**：删除历史对话时，按 `conversationId` 一次性停止相关的所有子任务和定时任务。

---

## 1. 关键决策（已与用户对齐）

| 决策点 | 选择 | 备选 |
|---|---|---|
| 子任务能力范围 | **与主对话一致**（全工具访问） | 纯文本 / 受限白名单 |
| 主对话 vs 子任务关系 | **同步阻塞**（主对话等待子任务返回） | 异步 + 回传主对话 / 异步不回传 |
| 定时任务持久化 | **H2 表**（复用 loom-agent 现有 H2 datasource） | 内存 / Redis |
| 前端面板形式 | **沿用现有 modal 弹窗** | 底部活动栏 / 侧边抽屉 |
| 子任务 LLM call vs stream | **`ChatClient.call()`**（同步、自动跑完整 tool-call 循环） | 流式（用户看不到流式过程，徒增复杂度） |
| 是否复用 `IChat`/`DefaultChat` | **不复用**，新建独立 `ISubTaskExecutor` 抽象（关注点分离） | 加字段进 `ChatRequestRecord` / 给 `IChat` 加 `call` 方法 |
| `ChatRequestRecord` wire-format | **不动**（仍只服务于 SSE 主对话） | 与子任务共享 |

---

## 2. flex-schedule 改动（持久化层）

### 2.1 新增 `JdbcTaskRepository`（H2 实现）

`flex-schedule` 已暴露 `TaskRepository` 接口（在 `core/TaskRepository.java`），有 `InMemoryTaskRepository` 作为默认实现。新增 H2 实现。

**文件**: `flex-schedule/src/main/java/cn/wubo/flex/schedule/core/JdbcTaskRepository.java`

```java
public class JdbcTaskRepository implements TaskRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper; // 序列化 TaskDefinition JSON 列

    public JdbcTaskRepository(JdbcTemplate jdbcTemplate) { ... }

    @Override public void save(TaskDefinition def) { ... }       // INSERT 或 UPDATE
    @Override public void delete(String taskName) { ... }
    @Override public TaskDefinition findByName(String taskName) { ... }
    @Override public List<TaskDefinition> findAll() { ... }       // 用于启动恢复
}
```

**Schema**（由 loom-agent 的 Flyway V12 提供，或由 flex-schedule 内部初始化 — 见 2.2）：

```sql
CREATE TABLE IF NOT EXISTS flex_scheduled_task (
    task_name VARCHAR(255) PRIMARY KEY,
    type VARCHAR(20) NOT NULL,           -- CRON / FIXED_DELAY / FIXED_RATE / ONE_SHOT
    cron VARCHAR(100),
    timezone VARCHAR(50),
    fixed_interval_seconds BIGINT,
    initial_delay_seconds BIGINT,
    timeout_seconds BIGINT,
    retry_policy_json CLOB,
    target_bean VARCHAR(255),            -- 我们用 Runnable lambda,所以这一列只是占位(可空)
    target_method VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    next_fire_at TIMESTAMP,
    extra_json CLOB                      -- 把 BeanMethodRunnable 的参数序列化到这里
);
```

### 2.2 flex-schedule 自动配置

**修改文件**: `flex-schedule-spring-boot-autoconfigure/src/main/java/.../FlexScheduleAutoConfiguration.java`

> **库 API 不增方法**：`cancelByNamespace` 等聚合逻辑放在 loom-agent 侧（详见 §3.11），不在 `FlexScheduledTaskService` 接口上加方法 — 这样避免升级 flex-schedule major 版本号

新增 `@Configuration` 内嵌类：

```java
@Configuration
@ConditionalOnClass(H2.class)             // 不强制引入 H2,只在 classpath 有时启用
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(name = "flex.schedule.repository", havingValue = "jdbc", matchIfMissing = true)
public static class JdbcTaskRepositoryConfiguration {

    @Bean
    public TaskRepository jdbcTaskRepository(DataSource dataSource,
                                             ObjectMapper objectMapper) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        ensureSchema(jdbcTemplate);      // CREATE TABLE IF NOT EXISTS
        return new JdbcTaskRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper flexScheduleObjectMapper() {
        return new ObjectMapper();
    }
}
```

`FlexScheduledTaskRegistrar` 启动时优先注入此 Bean（目前已有 `TaskRepository` 注入点 — 见 2.3 验证）。

### 2.3 验证注入点

- ✅ `CLAUDE.md` 第 60 行附近已声明 `InMemoryTaskRepository` 与 `TaskRepository` 接口
- ⚠️ 需要核验 `FlexScheduledTaskRegistrar` 当前是否真的从 bean factory 拉 `TaskRepository`，否则注入会失效。落地时第一步读 `FlexScheduledTaskRegistrar` 源码确认；如未注入，则在构造器上加 `Optional<TaskRepository>` 参数并加 setter

### 2.4 启动恢复

`FlexScheduledTaskRegistrar.restoreTasks()` 在 `afterSingletonsInstantiated` 阶段调用，遍历 `taskRepository.findAll()` 重新注册。这里确保：
- `createdAt` 取持久化值（用于 `max-lifetime` 计算）
- 超过 `max-lifetime` 的任务直接丢弃不入队
- 给此类任务加日志：「skipping expired task xxx (createdAt=…, age=…）」

---

## 3. loom-agent 新增类与方法（核心）

### 3.1 新包结构

```
cn.wubo.spring.ai.loom.agent.subtask
  ├── ISubTaskTool.java
  ├── DefaultSubTaskTool.java
  ├── ISubTaskExecutor.java
  ├── DefaultSubTaskExecutor.java
  ├── SubTaskRegistry.java
  ├── SubTaskRequest.java
  ├── SubTaskResult.java
  ├── SubTaskStatus.java
  └── ChatRequestComposer.java       (内部共享类)

cn.wubo.spring.ai.loom.agent.schedule
  ├── IScheduleTool.java
  ├── DefaultScheduleTool.java
  └── ScheduleLifecycleListener.java
```

### 3.2 `SubTaskRequest` / `SubTaskResult` / `SubTaskStatus`

```java
public record SubTaskRequest(
    String subTaskId,            // UUID, 由 Registry 生成
    String parentConversationId, // 主对话 conversationId, 用于 ChatMemory 隔离
    String parentSubTaskId,      // 可空,用于未来嵌套;v1 固定 null
    String username,
    String prompt,               // 用户原始 prompt,或 scheduler 传入
    String systemContext,        // 可选:"你是子任务,请只关注 X" 这类系统提示
    boolean fromScheduler        // true = 调度触发,false = LLM 工具调用触发
) {}

public record SubTaskResult(
    String subTaskId,
    String conversationId,
    String username,
    SubTaskStatus status,        // COMPLETED / FAILED / CANCELLED
    String text,                 // .call().content() 的最终回答
    String errorMessage,         // FAILED 时填充
    long startedAt,
    long finishedAt
) {}

public enum SubTaskStatus { RUNNING, COMPLETED, FAILED, CANCELLED }
```

### 3.3 `ISubTaskTool`（IEmbedTool 子接口）

```java
public interface ISubTaskTool extends IEmbedTool {

    /**
     * 启动一个子任务,在专用线程池同步执行。返回结果为最终文本或错误描述。
     * 主对话在调用本方法期间会阻塞。
     */
    @Tool(description = "把一段任务委派给'子模型'执行。子任务可以使用与主对话相同的工具,"
          + "但不能再次启动子任务或创建定时。")
    String startSubTask(
        @ToolParam(description = "子任务要完成的指令,例如'总结以下长文...') String prompt,
        @ToolParam(description = "可选的额外系统指令,例如\"只关注技术细节\"。不需要可传 null。")
        String systemContext,
        ToolContext toolContext
    );
}
```

实现关键点（`DefaultSubTaskTool`）：

```java
public class DefaultSubTaskTool implements ISubTaskTool {
    private final ISubTaskExecutor executor;
    private final SubTaskRegistry registry;

    @Override
    public String startSubTask(String prompt, String systemContext, ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        String parentConvId = (String) toolContext.getContext().get("parentConversationId");
        String subTaskId = UUID.randomUUID().toString();

        SubTaskRequest req = new SubTaskRequest(subTaskId, parentConvId, null, username, prompt, systemContext, false);
        registry.register(subTaskId, parentConvId, username, prompt, "RUNNING");

        SubTaskResult result;
        try {
            result = executor.execute(req);   // 阻塞
        } catch (Exception e) {
            result = new SubTaskResult(subTaskId, parentConvId, username, FAILED, "", e.getMessage(), now(), now());
        }

        registry.markFinished(subTaskId, result.status(), result.text(), result.errorMessage());
        return formatForMainConversation(result);  // 把 SubTaskResult 渲染成主对话可消费的字符串
    }
}
```

### 3.4 `ISubTaskExecutor`

```java
public interface ISubTaskExecutor {
    SubTaskResult execute(SubTaskRequest req) throws SubTaskException;
}
```

### 3.5 `DefaultSubTaskExecutor` 实现要点

```java
public class DefaultSubTaskExecutor implements ISubTaskExecutor {
    private final ChatClient subTaskChatClient;       // 启动时构造,tools 过滤版
    private final ExecutorService executorService;    // loomSubTaskExecutor,可配置并发
    private final MessageChatMemoryAdvisor memoryAdvisor; // 复用主对话的 ChatMemory bean
    private final List<String> allowedMemoryIds;      // 子任务在 ChatMemory 里使用的 id 命名空间

    public SubTaskResult execute(SubTaskRequest req) {
        // 1. 在专用线程池中跑
        Future<SubTaskResult> future = executorService.submit(() -> doExecute(req));
        try {
            return future.get();    // 阻塞,允许取消
        } catch (InterruptedException ie) {
            future.cancel(true);
            return new SubTaskResult(req.subTaskId(), req.parentConversationId(),
                req.username(), CANCELLED, "", "用户取消", now(), now());
        } catch (ExecutionException ee) {
            ...
        }
    }

    private SubTaskResult doExecute(SubTaskRequest req) {
        // 2. 用过滤版 ChatClient.call()
        ChatClient.ChatClientRequestSpec spec = subTaskChatClient.prompt();
        if (req.systemContext() != null) spec.system(req.systemContext());
        spec.user(req.prompt());
        spec.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationIdFor(req)));
        spec.advisors(memoryAdvisor);   // 写入主对话同一个 ChatMemory
        String content = spec.call().content();  // ← 同步 call,非流式
        return new SubTaskResult(req.subTaskId(), req.parentConversationId(),
            req.username(), COMPLETED, content, "", startedAt, now());
    }
}
```

**关键设计**：
- `subTaskChatClient` 在 loom-agent 启动时构造一次，工具集合 = `主对话 tools − {ISubTaskTool.class, IScheduleTool.class}`，**从源头杜绝 LLM 自递归**
- 子任务 ChatMemory 命名空间：`{parentConversationId}--sub--{subTaskId}` — 与主对话**隔离**但**同一张表**
- `executorService` 是新的 `loomSubTaskExecutor` Bean，配置 `spring.ai.loom.agent.subtask.max-concurrent`（默认 4）；用 `ThreadPoolTaskExecutor` 实现便于优雅关闭
- 取消：`registry.kill(id)` → `executorService.submit` 拿到的 `Future.cancel(true)` → `doExecute()` 抛 `InterruptedException`（Spring AI 同步调用路径反应快）→ 返回 `CANCELLED`

### 3.6 `SubTaskRegistry`

```java
@Component
public class SubTaskRegistry {
    private final ConcurrentHashMap<String, SubTaskRecord> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SubTaskRecord> history = new ConcurrentHashMap<>();
    private final AtomicInteger runningCount = new AtomicInteger(0);
    private final int maxConcurrent;
    private final int maxHistory;

    public String register(...) { ... }            // 返回 subTaskId
    public void markFinished(...) { ... }
    public SubTaskRecord get(String id) { ... }
    public List<SubTaskRecord> activeByConversation(String convId) { ... }
    public List<SubTaskRecord> listActive(String username) { ... }
    public List<SubTaskRecord> listHistory(String username, int limit) { ... }
    public Future<SubTaskResult> futureFor(String id) { ... }    // 给 kill 用
    public int killAllByConversation(String convId) { ... }      // 删除历史时调用
    public int kill(String id) { ... }                            // 面板操作
}
```

**存储**：纯内存。`SubTaskRecord` 含 `Future<SubTaskResult>` 引用，方便 kill。

### 3.7 `IScheduleTool`（IEmbedTool 子接口）

```java
public interface IScheduleTool extends IEmbedTool {
    @Tool(description = "创建一个定时任务。最短 10 分钟执行一次,最长存活 3 天(强校验)。")
    String createSchedule(
        @ToolParam(description = "任务名,字母数字下划线,在同一会话内需唯一") String name,
        @ToolParam(description = "调度类型:cron / fixed_delay / fixed_rate / one_shot")
        String scheduleType,
        @ToolParam(description = "表达式:cron 字符串 / 间隔秒数(one_shot 用延迟秒数)")
        String expression,
        @ToolParam(description = "触发时执行的提示词(作为子任务运行)") String prompt,
        ToolContext toolContext
    );

    @Tool(description = "取消一个定时任务。")
    String cancelSchedule(@ToolParam(description = "任务名") String name, ToolContext toolContext);

    @Tool(description = "列出当前会话下我创建的所有定时任务。")
    String listSchedules(ToolContext toolContext);

    @Tool(description = "获取某个定时任务的最近执行历史。")
    String getScheduleHistory(@ToolParam(description = "任务名") String name,
                              @ToolParam(description = "返回多少条") Integer limit,
                              ToolContext toolContext);
}
```

### 3.8 `DefaultScheduleTool` 实现要点

- 任务全名：**`loom-sched-{username}-{conversationId}-{name}`**（自动加 namespace 防冲突）
- `createSchedule`：
  - 拼全名，调 `flexScheduledTaskService.task(fullName).cron/fixedDelay/fixedRate/oneShot(...).register(runnable)`，其中 `runnable = () -> subTaskExecutor.execute(SubTaskRequest(... fromScheduler=true))`
  - LimitsChecker 会自动校验 10 分钟 / 3 天；超限时捕获 `TaskLimitExceededException` 并返回用户可读错误
  - `setExecutionHistory` 引用 flex-schedule 默认 ring buffer（100 条 / 任务）
- `cancelSchedule`：调 `flexScheduledTaskService.cancel(fullName)`
- `listSchedules`：调 `flexScheduledTaskService.listTasks()`，过滤 `fullName.startsWith("loom-sched-{username}-{convId}-")`
- `getScheduleHistory`：调 `flexScheduledTaskService.getExecutionHistory(fullName, limit)`

### 3.9 `ScheduleLifecycleListener`

实现 `flex-schedule` 的 `TaskExecutionListener` 接口，记录每次触发到独立 `ch.qos.logback` logger，便于面板历史可观察（可选；v1 先用默认 `getExecutionHistory` 即可）。

### 3.10 路由（`loomAgentSubTaskRouter` / `loomAgentScheduleRouter`）

新增两个 `@Bean` 返回 `RouterFunction<ServerResponse>`：

```java
@Bean("loomAgentSubTaskRouter")
public RouterFunction<ServerResponse> loomAgentSubTaskRouter(SubTaskRegistry registry) {
    return RouterFunctions.route()
        .GET("spring/ai/loom/subtask/list/active", req -> ok().body(registry.listActive(currentUser(req))))
        .GET("spring/ai/loom/subtask/list/history", req -> ok().body(registry.listHistory(currentUser(req), 50)))
        .POST("spring/ai/loom/subtask/kill/{id}", req -> { registry.kill(id); return ok().body(true); })
        .build();
}

@Bean("loomAgentScheduleRouter")
public RouterFunction<ServerResponse> loomAgentScheduleRouter(FlexScheduledTaskService svc,
                                                              UserContextHolder holder) {
    return RouterFunctions.route()
        .GET("spring/ai/loom/schedule/list",      req -> ok().body(svc.listTasks()))
        .POST("spring/ai/loom/schedule/cancel/{name}", req -> { svc.cancel(name); return ok().body(true); })
        .GET("spring/ai/loom/schedule/history/{name}", req -> ok().body(svc.getExecutionHistory(name, 50)))
        .build();
}
```

### 3.11 `ConversationLifecycleListener`（生命周期钩子）

修改现有 DELETE 路由所在的 `loomAgentConversationRouter`：

```java
builder.DELETE("spring/ai/loom/conversation/{conversationId}", request -> {
    String conversationId = request.pathVariable("conversationId");
    String username = UserContextHolder.getCurrentUser();

    // 1. 先停子任务
    int subtasksKilled = subTaskRegistry.killAllByConversation(conversationId);

    // 2. 再停定时器（loom-agent 侧聚合,无需给 flex-schedule 加新方法）
    int schedulesCancelled = 0;
    String prefix = "loom-sched-" + username + "-" + conversationId + "-";
    for (TaskInfo info : flexScheduledTaskService.listTasks()) {
        if (info.taskName().startsWith(prefix)) {
            flexScheduledTaskService.cancel(info.taskName());
            schedulesCancelled++;
        }
    }

    // 3. 最后才是用户层面的删除
    userConversation.deleteById(conversationId);

    log.info("Cleanup on conv delete: conv={}, subtasks_killed={}, schedules_cancelled={}",
             conversationId, subtasksKilled, schedulesCancelled);
    return ServerResponse.ok().body(true);
});
```

**注意**：loom-agent **不**给 `FlexScheduledTaskService` 加新方法（避免升级库版本）。在 loom-agent 侧用 `flexScheduledTaskService.listTasks()` + 命名空间前缀过滤 + 逐个 `cancel(name)` 即可（详见下方 `ConversationLifecycleListener`）。

**重要 — 删除语义**：loom 当前 `userConversation.deleteById(...)` 是**软删除**（设置 `deleted_at` 时间戳），不会真正清掉 ChatMemory 行。本设计沿用软删除：DELETE 路由先停子任务、再停定时、最后软删 `user_conversation`；用户选择「清空历史」时由前端触发另一路径（不在本设计范围）。本设计负责「停正在跑的子任务/定时」这一行为已经完备。

### 3.12 `LoomAgentProperties` 新增字段

```java
private SubTask subTask = new SubTask();
private Schedule schedule = new Schedule();

public static class SubTask {
    private boolean enabled = true;
    private int maxConcurrent = 4;
    private int maxHistory = 200;
}

public static class Schedule {
    private boolean enabled = true;
}
```

**默认限制**（在 `LoomAgentConfiguration` 启动时强制注入到 `flex.schedule.*`）：

```java
@Bean
@ConditionalOnMissingBean
public TaskLimits loomAgentDefaultTaskLimits() {
    return new TaskLimits(Duration.ofMinutes(10), Duration.ofHours(72), TaskLimits.Mode.STRICT);
}
```

> 注：flex-schedule 当前 limits 是 `@ConfigurationProperties` 绑定。落地时实际绑定方式以源码为准；若 TaskLimits 是构造注入，则通过 `ApplicationRunner` 调 `FlexScheduledTaskRegistrar` 的 setter 注入初始值（**重要**：用户能在 yml 里覆盖优先级要高）。

### 3.13 主对话 `IChat` 不动

`DefaultChat` 只动一处：构造 `IEmbedTool` 列表时，把 `ISubTaskTool` 和 `IScheduleTool` 也注入给主对话（**主对话能用这两个工具**）。其余 `IChat` 代码保持不变。

---

## 4. 前端新增

### 4.1 工具栏 (`index.html`)

在「文件」按钮后插入 2 个 `<button>`：

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

### 4.2 Modal 容器（沿用 `file-modal` 模式）

新增 `#subtask-modal` 和 `#schedule-modal`，结构同现有 `#file-modal`：固定层遮罩 + 内容面板 + 关闭按钮。CSS 直接复用 `style.css` 的 `.modal-overlay` / `.modal-content`。

### 4.3 子任务面板内容

- 上半：表格列出**当前运行中**子任务（id 截断 8 字符 / username / prompt 摘要 80 字符 / startedAt 相对时间 / 「⛔ 杀死」按钮）
- 下半：折叠面板「历史（最近 50 条）」

### 4.4 定时面板内容

- 上半：表格列出**活动定时任务**（fullName / schedule 类型 / 表达式 / 上次/下次触发 / 「⛔ 停止」按钮）
- 折叠面板：每个任务展开后显示 `getExecutionHistory(50)` 的表格（开始时间 / 状态 SUCCESS-FAILED-CANCELLED / 时长 / 错误消息）
- 不在 v1 中做"创建定时"UI（用户通过对话即可）

### 4.5 API 客户端 (`app.js`)

```js
const API = {
    ...,
    listActiveSubTasks: () => `/spring/ai/loom/subtask/list/active`,
    listHistorySubTasks: () => `/spring/ai/loom/subtask/list/history`,
    killSubTask: (id) => `/spring/ai/loom/subtask/kill/${id}`,
    listSchedules: () => `/spring/ai/loom/schedule/list`,
    cancelSchedule: (name) => `/spring/ai/loom/schedule/cancel/${encodeURIComponent(name)}`,
    scheduleHistory: (name) => `/spring/ai/loom/schedule/history/${encodeURIComponent(name)}`,
};
```

打开面板时启动 `setInterval(pull, 2000)`；关闭时 `clearInterval`。

---

## 5. 数据迁移（Flyway）

新增 `spring-ai-loom-agent/src/main/resources/db/migration/V12__flex_scheduled_task.sql`：

```sql
CREATE TABLE IF NOT EXISTS flex_scheduled_task (
    task_name VARCHAR(255) PRIMARY KEY,
    type VARCHAR(20) NOT NULL,
    cron VARCHAR(100),
    timezone VARCHAR(50),
    fixed_interval_seconds BIGINT,
    initial_delay_seconds BIGINT,
    timeout_seconds BIGINT,
    retry_policy_json CLOB,
    target_bean VARCHAR(255),
    target_method VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    next_fire_at TIMESTAMP,
    extra_json CLOB
);

CREATE INDEX idx_flex_scheduled_task_created_at ON flex_scheduled_task(created_at);
```

> H2 默认 schema 升级路径已经在 loom V1.0/V1.1 上做过（双版本号在同 Flyway 实例并存），加 V12.0 即可。

---

## 6. 配置文件默认值（application.yml 增量）

```yaml
spring:
  ai:
    loom:
      agent:
        subtask:
          enabled: true          # 默认开
          max-concurrent: 4
        schedule:
          enabled: true
flex:
  schedule:
    pool-size: 8
    limits:
      min-interval: 10m         # 用户硬要求
      max-lifetime: 72h         # 用户硬要求
      mode: strict              # 强校验,超限抛错
```

---

## 7. 测试与验证

### 7.1 flex-schedule 自测

- `JdbcTaskRepositoryTest`：H2 in-memory，验证 save/findByName/findAll/delete
- `FlexScheduleAutoConfigurationJdbcTest`：mock 一个 `DataSource`，验证 Bean 装配
- `RestoreFromH2Test`：写一条任务到 H2，模拟重启，验证 `restoreTasks()` 重新注册且 `createdAt` 保留

### 7.2 loom-agent 自测

- `DefaultSubTaskExecutorTest`：mock ChatClient，验证过滤工具集合不含 ISubTaskTool/IScheduleTool
- `SubTaskRegistryTest`：register / markFinished / killAllByConversation / 容量上限
- `DefaultSubTaskToolTest`：用 mock executor 验证返回字符串格式
- `DefaultScheduleToolTest`：mock `FlexScheduledTaskService`，验证 namespacing / limits 错误传播
- `ConversationLifecycleListenerTest`：模拟 DELETE 路由，验证子任务先停、定时再停、最后 user_conversation 删除

### 7.3 端到端（spring-ai-loom-agent-test）

- 走 DashScope 真实模型（已有 API key），完整路径：在主对话发"启动一个子任务总结 README"，验证子任务跑完、ChatMemory 中能看到子任务的 intermediate；再发"每 10 分钟给我生成一条问候"，验证定时注册和第一次触发（缩短到 60s 做演示）；调用 DELETE 路由，验证子任务和定时都被清理

---

## 8. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 子任务 token 失控（每分钟 1 次 × 3 天 × 8K token = 34M token） | 子任务的 `systemContext` 模板要求 LLM "concise response"；暴露 `maxConcurrent` 防并发超限；最长 3 天已硬限制 |
| LLM 自递归（子任务被允许调起自己） | `subTaskChatClient` 启动时过滤掉 ISubTaskTool/IScheduleTool，运行时无通路 |
| 进程崩溃时还在跑的子任务状态丢失 | in-memory `SubTaskRegistry`，崩了就崩了；日志留痕即可；定时器走 H2 持久化不丢 |
| H2 schema lock 冲突（多线程同时 INSERT） | `JdbcTaskRepository.save` 用 `MERGE` 或在表上加 `ApplicationName` 列区分 |
| 用户在 yml 里覆盖默认 limits 导致 1 分钟触发 | 设计允许（用户拥有最终控制权）；文档里提示 |
| `FlexScheduledTaskRegistrar` 现有构造器不接受外部 `TaskRepository` | 落地第一步验证；不兼容则小幅重构 flex-schedule 构造器签名 |
| `ISubTaskTool` / `IScheduleTool` 是 IEmbedTool 子接口 — 主对话工具集自动包含，但 LLM 也可能滥用 | 在 `@Tool(description=...)` 里写明"sub-task use only for X / schedule use only for Y" 提供 prompt-level guardrail |

回滚策略：所有改动集中在一个新包 `cn.wubo.spring.ai.loom.agent.subtask` 和 `…schedule` 下，外加一个独立的 `loomAgentSubTaskRouter`/`loomAgentScheduleRouter` Bean、2 个 modal、2 个 flex-schedule 类。回滚 = 删除这些文件 + 撤销 H2 V12 migration + 撤销 `spring-ai-loom-agent-test` 的依赖。

---

## 9. 文件清单（增量）

### flex-schedule 仓库
- 新增 `flex-schedule/src/main/java/cn/wubo/flex/schedule/core/JdbcTaskRepository.java`
- 修改 `flex-schedule-spring-boot-autoconfigure/.../FlexScheduleAutoConfiguration.java`（+ 1 个内嵌 `@Configuration`）
- 新增 `flex-schedule-test/.../JdbcTaskRepositoryTest.java`

### loom-agent 仓库
- 新增 9 个文件（同 §3.1 包结构）
- 修改 `LoomAgentConfiguration.java`（新增 2 个 `@Bean RouterFunction`、1 个 `loomSubTaskExecutor` Bean、1 个 `loomAgentDefaultTaskLimits` Bean）
- 修改 `LoomAgentProperties.java`（+ 2 个内嵌类）
- 修改 `loominAgentConversationRouter` 的 DELETE 分支（+3 行清理逻辑）
- 新增 `db/migration/V12__flex_scheduled_task.sql`
- 修改 `index.html`（+ 2 个按钮）
- 修改 `style.css`（按需，复用现有 modal 样式基本可以）
- 新增 `subtask-modal.js` + `schedule-modal.js`（或合并入 `app.js`）
- 修改 `app.js`（+ 6 个 API 常量、+ 2 个 `openModal(id, ...)`）
- 修改 `application.yml`（+ 默认配置块）

### 配置文件 (`application.yml` 增量)
- 见 §6

---

## 10. 分阶段交付

1. **Phase 1（flex-schedule 持久化）**：JdbcTaskRepository + 自动配置，flex-schedule 单测全过
2. **Phase 2（loom-agent 子任务骨架）**：SubTaskRequest/Result/Status + ISubTaskExecutor + DefaultSubTaskExecutor + SubTaskRegistry + 路由，单测过
3. **Phase 3（loom-agent 子任务工具）**：ISubTaskTool + DefaultSubTaskTool + 过滤版 ChatClient 构造器，单元 + 集成测试过
4. **Phase 4（loom-agent 定时骨架）**：IScheduleTool + DefaultScheduleTool + namespacing + limits 注入，单元测试过
5. **Phase 5（删除历史联动）**：ConversationLifecycleListener 改造，单元 + 集成测试过
6. **Phase 6（前端）**：按钮 + modal + 轮询，端到端测试过
7. **Phase 7（application.yml 默认值 + 文档）**：更新 CLAUDE.md、README.md、新增 docs/SUBTASK-SCHEDULER.md
