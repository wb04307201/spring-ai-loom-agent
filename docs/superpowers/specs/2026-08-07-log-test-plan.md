# 日志记录功能 — 全覆盖测试计划

**日期**: 2026-08-07
**目的**: 验证 V4.0 合并后所有日志表（`loom_chat_usage` / `loom_chat_reasoning` / `loom_tool_call_log` / `loom_subtask_history` / `loom_schedule_execution`）跨 6 个前端入口的数据一致性
**基线**: 服务已 cold-start（V1.0 → V4.0），DB 干净（所有日志表 0 行）

---

## 0. 测试矩阵总览

| # | 维度 | 测试场景 | 验证点 |
|---|------|---------|--------|
| 1 | 数据采集 | 单条普通对话 | loom_chat_usage 1 行 / reasoning 1 行 |
| 2 | 数据采集 | 多轮对话（同会话） | 多行 usage / 1 行 reasoning |
| 3 | 数据采集 | enable_thinking 真触发 | reasoning 长度 > 0 且含思维链 |
| 4 | 数据采集 | 工具调用（bing / weather / sub-task / schedule） | loom_tool_call_log / loom_subtask_history / loom_schedule_execution |
| 5 | 跨页一致性 | stats.html ↔ DB | 表格数字 = DB SUM |
| 6 | 跨页一致性 | user.html 6 月柱状图 ↔ DB | 6 个柱子 = DB GROUP BY |
| 7 | 跨页一致性 | conversation.html 顶部卡片 ↔ DB | 6 数字 = DB SUM |
| 8 | 跨页一致性 | app.js "我的用量" 模态框 ↔ DB | 4 数字 = DB SUM |
| 9 | 边界 | 空对话（0 message） | 不写 usage / 不写 reasoning |
| 10 | 边界 | 流中断（用户点停止） | 部分 usage / 无 reasoning |
| 11 | 边界 | 跨月查询 | YEAR/MONTH 过滤正确 |
| 12 | 边界 | 单用户多会话 | 按 conversation_id 聚合唯一 |
| 13 | 错误 | 用户名为空 | 跳过 record |
| 14 | 错误 | 0 token usage | 跳过 record（不写空行） |

---

## 1. 数据采集测试

### 1.1 单条普通对话（基础）

**前置**: clean DB，新会话

**操作**:
1. 登录 wb04307201 / 123456
2. 进 `/index.html`
3. 输入"你好，简单介绍一下你自己"
4. 等回复完

**DB 验证** (H2 console):
```sql
-- loom_chat_usage 应该 1 行
SELECT * FROM LOOM_CHAT_USAGE WHERE username = 'wb04307201';
-- 期望: 1 行，prompt_tokens > 0，completion_tokens > 0，total_tokens > 0

-- loom_chat_reasoning 应该 1 行
SELECT conversation_id, LENGTH(reasoning_text) FROM LOOM_CHAT_REASONING;
-- 期望: 1 行，reasoning 不为空（enable_thinking=true）

-- spring_ai_chat_memory 应该 2 行（1 USER + 1 ASSISTANT）
SELECT type, LENGTH(content) FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?;
```

**前端验证**:
- `/admin/stats.html` 当月用量应该有 1 行（username + token）
- `/admin/user.html?username=wb04307201` 6 月柱状图 2026-08 有值
- `/admin/conversation.html?id=...` 顶部卡片有数字
- 聊天界面 "我的用量" 模态框 4 数字都 > 0
- Conversation ASSISTANT 卡片有 "思考" 折叠区

### 1.2 多轮对话（同会话）

**操作**: 同会话再发 2 句话（3 轮总计）

**DB 验证**:
```sql
-- loom_chat_usage 应该是 3 行的总和（每 chunk 1 行）
SELECT conversation_id, COUNT(*) cnt, SUM(total_tokens) sm
FROM LOOM_CHAT_USAGE GROUP BY conversation_id;
-- 期望: 1 个 conversation_id，cnt 远 > 3（流式 chunk），sm > 0

-- reasoning 仍是 1 行（合并自多次请求）
SELECT COUNT(*) FROM LOOM_CHAT_REASONING WHERE conversation_id = ?;
-- 期望: 1 行
```

**关键校验**: SUM(prompt_tokens) **会重复计算**（流式每 chunk 写一行），但前端展示的"总 token"是从 DB SUM 出来的，所以是真实的 cumulative 数字。

### 1.3 enable_thinking 真触发

**前置**: DashScope `enable_thinking: true` 已在 application.yml 配置

**验证**:
```sql
SELECT reasoning_text FROM LOOM_CHAT_REASONING WHERE conversation_id = ?
-- 长度 > 50 字符，且包含"Let me"、"思考"、"判断"等关键词
```

**前端**: conversation.html 思考折叠区可展开，内容不是空字符串。

### 1.4 工具调用类型覆盖

| 工具 | 触发命令 | 验证表 | 期望 |
|------|---------|--------|------|
| MCP (bing_search) | "搜索 Spring AI 是什么" | `loom_tool_call_log` | 1 行，结果含搜索结果 |
| MCP (weather) | "北京今天天气" | `loom_tool_call_log` | 1 行，结果含天气 |
| 子任务 | "用 start_sub_task 并行处理 3 个问题" | `loom_subtask_history` | N 行（子任务数） |
| 定时 | "每 15 分钟提醒我..." | `loom_scheduled_task` + `loom_schedule_execution` | 1 任务 |

**特例**: sub-task 会在主对话里嵌套，验证 conversation.html 时间线能展示 SUBTASK 事件。

---

## 2. 跨页一致性测试（核心）

### 2.1 stats.html ↔ DB

**操作**:
1. 累计 5 轮对话
2. 访问 `/admin/stats.html?year=2026&month=8`

**比对**:
```
stats.html 表格：
  wb04307201  147 calls  2,765,794 prompt  60,933 completion  2,826,727 total

DB 直接查：
SELECT username, COUNT(*) calls, SUM(prompt_tokens) p, SUM(completion_tokens) c, SUM(total_tokens) t
FROM LOOM_CHAT_USAGE WHERE YEAR(created_at)=2026 AND MONTH(created_at)=8
GROUP BY username;
```

**期望**: 所有数字 1:1 匹配。

### 2.2 user.html 6 月柱状图 ↔ DB

**操作**:
1. 在 2026-03 月份造 1 条 usage（修改 created_at 模拟）
2. 访问 `/admin/user.html?username=wb04307201`

**比对**:
```
2026-03 = X
2026-04 = 0
2026-05 = 0
2026-06 = 0
2026-07 = 0
2026-08 = Y
```

**校验**:
- 6 个柱子都展示（即使中间月 0）
- 数字 = DB SUM
- "本月用量：Y tokens" 也对

### 2.3 conversation.html 顶部卡片 ↔ DB

**操作**: 访问某具体对话

**校验**:
```
总 Token = SUM(loom_chat_usage.total_tokens WHERE conversation_id=?)
调用次数 = COUNT(*)
工具调用 = COUNT(loom_tool_call_log WHERE conversation_id=?)
子任务 = COUNT(loom_subtask_history WHERE ...)
定时任务 = COUNT(loom_schedule_execution WHERE ...)
错误 = COUNT(loom_tool_call_log WHERE result_is_error=true)
```

### 2.4 app.js "我的用量" 模态框 ↔ DB

**操作**: 首页 → 用户菜单 → 我的用量

**校验**: 4 数字（总 Token / 调用 / 输入 / 输出）= DB SUM（当前月 + 当前用户）

---

## 3. 边界测试

### 3.1 空对话

**操作**: 新建对话不发送任何消息

**DB 期望**:
- loom_chat_usage 0 行
- loom_chat_reasoning 0 行
- spring_ai_chat_memory 0 行

### 3.2 流中断

**操作**: 发消息中途点"停止"按钮

**DB 期望**:
- 部分 usage 行（已收到的 chunk）
- 无 reasoning（流未正常完成，complete handler 不触发）

### 3.3 跨月查询

**操作**: 修改某行 usage 的 created_at 到 2026-08-01 23:59:59

**校验**: stats.html 8 月数据正确包含这条，9 月数据不正确包含。

### 3.4 单用户多会话

**操作**: 创建 5 个不同对话，每个发 1 条消息

**DB 期望**: 5 个不同 conversation_id 都有 usage / reasoning

### 3.5 用户名为空

**操作**: Mock / 篡改 username 为 ""

**DB 期望**: 不写 usage（saveReasoning 检查 isBlank）

### 3.6 0 token usage

**操作**: 极短消息（可能总共 < 5 tokens）

**DB 期望**: chatUsageService.record() 早返，不写空行；reasoning 仍写（如果有 thinking）

---

## 4. 工具调用流程测试

### 4.1 bing_search

**操作**: 输入"搜索 Spring AI 1.0 是什么"

**DB 校验**:
```sql
SELECT tool_name, result_is_error, duration_ms FROM LOOM_TOOL_CALL_LOG
WHERE conversation_id = ? AND tool_name = 'bing_search';
-- 期望: 1 行，result_is_error=false 或超时（取决于网络）
```

**前端**: conversation.html 出现 `🔧 bing_search` 事件 + 折叠区显示参数和返回值。

### 4.2 sub-task

**操作**: "用 start_sub_task 并行启动 3 个子任务"

**DB 校验**:
```sql
SELECT COUNT(*) FROM LOOM_SUBTASK_HISTORY WHERE conversation_id = ?;
-- 期望: 3 行
```

**前端**: conversation.html 出现 3 个 `🧩` SUBTASK 事件。

### 4.3 schedule

**操作**: "每 30 秒提醒我喝水"

**DB 校验**:
```sql
SELECT task_name, cron FROM LOOM_SCHEDULED_TASK WHERE conversation_id = ?;
SELECT COUNT(*) FROM LOOM_SCHEDULE_EXECUTION WHERE task_name = ?;
```

**前端**: 触发后 conversation.html 出现 `⏰` 事件。

---

## 5. 错误恢复

### 5.1 DB 锁竞争

**操作**: 同时打开 2 个 Chrome 标签发对话

**DB 期望**: H2 AUTO_SERVER 模式下并发写入 OK，count 正确。

### 5.2 H2 console 锁

**操作**: 发完对话后立即开 H2 console 查询

**DB 期望**: 立即可读，H2 AUTO_SERVER 支持。

### 5.3 服务重启不丢数据

**操作**: 记 1 条 usage → 杀服务 → 重启 → 再查

**DB 期望**: H2 文件持久化，数据保留。

---

## 6. 测试执行步骤

### Phase 1: 基础采集（1-2）
1. 重启服务确认 DB 干净
2. 5 个普通对话（无工具）覆盖单条/多轮
3. 验证所有 4 个日志表行数

### Phase 2: 工具调用（1.4）
1. 1 个 bing_search 对话
2. 1 个 weather 对话
3. 1 个 sub-task 对话
4. 1 个 schedule 对话

### Phase 3: 跨页一致性（2.x）
1. 全部 9 个对话完成
2. 扫 4 个前端页面对比 DB
3. 数字必须 1:1 匹配

### Phase 4: 边界（3.x）
1. 空对话
2. 流中断
3. 跨月查询
4. 单用户多会话

### Phase 5: 自动对比

为了减少人工对照，建议写一个简单的 shell 脚本：
```bash
# 1. 浏览器侧拿数据（fetch）
TOKEN=xxx
curl -s /admin/stats.html -b cookie.txt > stats.html
# 2. 解析表格数字
# 3. SQL 查 DB
java -jar h2-driver.jar -sql "SELECT ..."
# 4. diff
```

或者用 Playwright + H2 console API 串起来。

### 失败判定标准

- ❌ 任何一个 DB SUM 与前端展示不符 → FAIL
- ❌ 任何工具调用无对应行 → FAIL
- ❌ 任何空对话产生 usage 行 → FAIL
- ❌ reasoning 缺失 → FAIL（enable_thinking=true 时）

---

## 7. 报告输出

测试完成后输出：
```
=== 测试结果 ===
[✓] 1.1 单条对话: usage=1, reasoning=1
[✓] 1.2 多轮对话: usage=N (N>3), reasoning=1
[✓] 1.3 thinking 触发: len=298
[✓] 1.4 bing_search: 1 行
[✓] 1.4 sub-task: 3 行
...
[✓] 2.1 stats.html ↔ DB: 1:1 匹配
[✓] 2.2 user.html ↔ DB: 1:1 匹配
...
```

任何 [✗] 都要记录：预期 vs 实际、复现步骤、SQL/query 输出。
