# Spring AI LoomAgent — flex-schedule 1.2.2 升级 Chrome E2E 全面测试 设计

- 日期: 2026-07-23
- 作者: Claude (brainstorming + writing)
- 状态: 设计稿,待用户审阅

## 背景

`flex-schedule` 依赖从 `1.0-SNAPSHOT` 升到 `1.2.2`(commit ee2627b 之前的 5-6 轮破坏性改动):
- `TaskBuilder.createdAt(Instant)` 新 API
- consumer-owned persistence(移除 `JdbcTaskRepository`)
- `InMemoryExecutionHistory` 注入 `FlexScheduledTaskRegistrar`
- `LimitsChecker` 行为修订
- `setCreatedAt` / 文档 + 测试补全
- metrics + Redis distributed lock 调整

项目侧已为这次升级写了约 1856 LOC 单测/集成测试 (`spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/schedule/` 下 6 个新文件),但都是基于 Mockito 或单容器集成。**实际 jar 在真实 Chrome 浏览器 + LLM 真实调用 + Spring Boot 完整上下文下能否跑通,以及跨用户隔离/跨重启恢复等行为是否如预期,没有验证。**

## 目标

用 Chrome(Playwright MCP 驱动)对以下三件事做端到端真实验证:

1. **LLM tool 发现 + 调用链路**:qwen3.7-plus 真的能把 `create_scheduled_task` / `cancel_scheduled_task` / `list_scheduled_tasks` / `get_schedule_history` 4 个 tool 调起来,且参数正确
2. **SPA 定时任务面板的 UI 绑定**:`schedulePanel` modal 真的能列任务、取消任务、查历史,UI 与后端状态一致
3. **跨用户安全 + 跨重启恢复** + **limits 行为**:cross-user cancel 被拒、task 跨 Spring Boot 重启被恢复、`max-lifetime` 过期被丢弃

## 非目标

- **不**覆盖 flex-schedule 内部算法的正确性(那归 `flex-schedule` 自己的测试)
- **不**重复写 `parseSeconds` / `recordExecution` / `createdAt` 链路的单测(已被 `DefaultScheduleToolLifecycleTest` 等覆盖)
- **不**压测 / 性能 / 并发
- **不**改 `flex-schedule` 自身代码

## 测试矩阵(16 核心 + 3 bonus)

### 核心矩阵 16 = 4 类型 × 2 角色 × {成功, 失败}

| # | 角色 | 类型 | 路径 | LLM prompt(中文) | 期望 sentinel |
|---|---|---|---|---|---|
| 1  | admin  | cron        | success | 帮我创建一个每 5 分钟报时一次的定时任务 | `[定时已创建]` |
| 2  | admin  | fixed_delay | success | 每 30 秒查一次北京天气 | `[定时已创建]` |
| 3  | admin  | fixed_rate  | success | 每 20 秒问候我一次 | `[定时已创建]` |
| 4  | admin  | one_shot    | success | 30 秒后提醒我去开会 | `[定时已创建]` + 30s 后历史有 1 条 |
| 5  | admin  | cron        | failure | 每 1 秒报时一次(故意低于 min-interval=10m) | `[定时失败]` |
| 6  | admin  | fixed_delay | failure | 每 5 秒查一次天气 | `[定时失败]` |
| 7  | admin  | fixed_rate  | failure | 每 5 秒问候一次 | `[定时失败]` |
| 8  | admin  | one_shot    | failure | prompt 故意空(用奇怪措辞让 LLM 不传或传 null) | `[定时失败] prompt 不能为空` |
| 9  | tester | cron        | success | 同 #1 | 同 #1 |
| 10 | tester | fixed_delay | success | 同 #2 | 同 #2 |
| 11 | tester | fixed_rate  | success | 同 #3 | 同 #3 |
| 12 | tester | one_shot    | success | 同 #4 | 同 #4 |
| 13 | tester | cron        | failure | 同 #5 | 同 #5 |
| 14 | tester | fixed_delay | failure | 同 #6 | 同 #6 |
| 15 | tester | fixed_rate  | failure | 同 #7 | 同 #7 |
| 16 | tester | one_shot    | failure | prompt 空 | `[定时失败] prompt 不能为空` |

### Bonus 3 个

| # | 名称 | 步骤概要 | 期望 |
|---|---|---|---|
| B1 | 跨用户 cancel | admin 创建 `cross-task`,tester 通过 LLM 让"取消 cross-task" | LLM 回 `[取消失败] 未找到任务` + DB 行仍在 + 弹窗提示一致 |
| B2 | 跨重启恢复 | admin 创建 cron 任务 → `taskkill /F /PID 43980` → 重启 Spring Boot → 等 35s | 启动日志 `Restored scheduled task [...]` + `flexService.listTasks()` 含该任务 + DB 行仍在 |
| B3 | max-lifetime 过期 | H2 SQL `UPDATE loom_scheduled_task SET created_at = NOW() - INTERVAL '73' HOUR WHERE task_name=?` → 重启 | 启动日志 `Dropping expired scheduled task [...]` + DB 行被删 + flexService 不含 |

## 架构与工具

### 驱动组合

| 用途 | 工具 | 备注 |
|---|---|---|
| Chrome 交互(打开 SPA / 登录 / 发 chat / 点面板 / 截图) | **Playwright MCP** (`mcp__playwright__browser_*`) | 复用现有 MCP,免装 Playwright Python 库 |
| SSE 流结束判定 | `mcp__playwright__browser_wait_for` 等 sentinel 文本 | 单条 90s 超时 |
| REST 断言(`/spring/ai/loom/schedule/by-conversation/{conv}` 等) | `mcp__playwright__browser_evaluate` 在页面上下文里 `fetch()` | 与 LLM 独立,直接读后端状态;Chrome context 已带 cookie,不需要单独维护 cookies.txt |
| H2 SQL(清表 / 改 createdAt) | `curl` 走 H2 console form `/h2-console/login.do` + `/h2-console/query.do` | H2 console 已启用 (`/h2-console`,jdbc:h2:file:`~/.loom/datasource/db`) |
| 进程控制 | `taskkill /F /PID 43980` + 后台 `mvn spring-boot:run` | Windows 环境 |

### Chrome Context 复用

- 启动时建两个独立 context:`ctx-admin` / `ctx-tester`,各自注入 cookie,避免每个场景重登录
- 每场景开始 `browser_navigate` 到 SPA 主页 + `browser_snapshot` 确认未掉登录
- 每场景结束截图存盘

### 测试 harness 主循环

每场景独立运行以下步骤:

1. **DB 重置**:`DELETE FROM loom_scheduled_task; DELETE FROM loom_schedule_execution;`
2. **Chrome 重置**:`navigate` SPA 主页,确认登录态
3. **触发 LLM**:聚焦 chat input,输入 prompt,`wait_for` 等 sentinel,超时 90s
4. **截图 + 抽取回复**:截图 `chat.png`,从 SSE 流解析出 LLM 最后一条 assistant 文本
5. **REST 断言**:在已登录的 Chrome context 里 `browser_evaluate(async () => fetch('/spring/ai/loom/schedule/by-conversation/' + convId).then(r => r.json()))`;H2 直查 `loom_scheduled_task`
6. **面板验证**:点 SPA 的"定时任务"按钮,截图 `panel.png`,从 DOM 抓任务列表
7. **判定**:LLM 回复 / DB 行数 / 面板行数 三者与 `expected` 表对照,标 `PASS` 或 `FAILED-XXX`
8. **记录**:`scenarios/<id>/result.json` + 主 `results.jsonl`

## 失败处理

| 现象 | 处理 |
|---|---|
| LLM 回复不含 sentinel(超时 / 答非所问 / 截断) | 备用 prompt 重试 1 次;仍失败 → `LLM-FLAKE` |
| LLM 说 `[定时已创建]` 但 DB 无行 | `STATE-MISMATCH`(高优,通常是 flex-schedule 抛了但 LLM 翻译错了) |
| LLM 说 `[定时失败]` 但 DB 有行 | `STATE-MISMATCH`(回滚路径出错) |
| 面板截图里看不到任务 | `UI-MISMATCH`(SPA 渲染 vs 后端状态不一致) |
| 连续 3 场景 `LLM-FLAKE` | **暂停整个矩阵**,回报用户,等指示 |

LLM 备用 prompt 模板:同一意图的另一种措辞(简洁版 / 详细版 / 命令式)。

## 报告

产物落在 `e2e-results/<时间戳>/`:

```
e2e-results/2026-07-23-2130/
├── summary.md             # 表格:场景/通过/失败原因/证据路径
├── results.jsonl          # 每行一 JSON
├── scenarios/
│   └── <NN-role-type-path>/
│       ├── chat.png
│       ├── panel.png
│       ├── llm-stream.log
│       ├── db-state.json
│       └── result.json
├── bonus/
│   └── B1|B2|B3-.../
└── failures/              # 失败场景的额外证据
```

`summary.md` 表格列:
- 编号
- 角色 + 类型 + 路径
- PASS / FAILED-XXX
- LLM 回复片段
- DB 行数
- 面板行数
- 证据路径

## 时间预算

| 阶段 | 预计 |
|---|---|
| 准备(测登录 / 建 tester / 清表) | 15 min |
| 16 场景主矩阵 | 90-120 min |
| 3 个 bonus | 30 min |
| 报告生成 + 收尾 | 15 min |
| **合计** | **2.5-3 小时** |

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| DASHSCOPE_API_KEY 失效 / qwen3.7-plus 限流 | 失败兜底 + 暂停矩阵 |
| Spring Boot 重启期间 (B2/B3) cookie 过期 | 不依赖 cookie,B2/B3 用 REST 直接断言 |
| Chrome MCP 偶发 snapshot 超时 | `browser_wait_for` + 重试 |
| `loom-sched-{user}-{conv}-{name}` 命名空间跨 user 不会冲突 | 已在 B1 显式验证 |

## 准备 checklist

- [ ] Spring Boot 已启动并响应 `/actuator/health` 200
- [ ] 默认 admin `wb04307201` 密码已知(从 `V1.0__init.sql` 找或试 `123456`)
- [ ] DASHSCOPE_API_KEY 35 字符已设
- [ ] `tester` 用户已通过 admin 创建,type=`USER`
- [ ] `loom_scheduled_task` / `loom_schedule_execution` 已清空
- [ ] Playwright MCP 可达,Chrome 已开

## 后续(完成 E2E 后才考虑)

- 把核心矩阵固化为 Playwright Python 脚本,纳入 CI
- 把 B1/B2/B3 固化为 JUnit `@SpringBootTest`,跑得快
- 更新 `CLAUDE.md` "Module Structure" 段的 schedule 工具描述
