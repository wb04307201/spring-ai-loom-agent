# 端到端日志测试报告

**日期**: 2026-08-08
**版本**: V5.4（loom_tool_call_log 修复后）
**测试人**: admin (wb04307201)

---

## 测试场景与结果

| # | 场景 | 事件流 | callCount | toolCall | subtask | schedule | error | 总 Token |
|---|------|-------|-----------|-----------|---------|----------|-------|----------|
| 1 | 基础聊天（无工具） | USER + ASSISTANT | 108 | 0 ✓ | 0 ✓ | 0 ✓ | 0 ✓ | 557k |
| 2 | 1 个 sub-task | USER + ASSISTANT + SUBTASK | 316 | 4 | 1 ✓ | 0 ✓ | 2* | 2,079k |
| 3 | 3 个 sub-task | USER + ASSISTANT + 3 SUBTASK | 329 | 14 | 3 ✓ | 0 ✓ | 2* | 3,163k |
| 4 | schedule 创建 | USER + ASSISTANT | 1043 | 70 | 0 ✓ | **0 ❌** | 0 ✓ | 6,500k |
| 5 | MCP search | USER + ASSISTANT | 237 | **0 ❌** | 0 ✓ | 0 ✓ | 0 ✓ | 1,276k |

*2 = sub-task 内部执行错误（skill 查找失败）

---

## ✅ 工作正常的

1. **基础聊天**（Test 1）：USER + ASSISTANT 正确显示，6 指标全部 0
2. **sub-task 调用**（Test 2, 3）：subtaskCount 正确 = 1 / 3，事件流类型正确
3. **跨页一致性**（stats.html vs 我的用量 modal）：
   - 6,068 calls ✓
   - 37,235,425 totalTokens ✓
   - 35,617,963 promptTokens ✓
   - 1,617,462 completionTokens ✓
   - 100% match
4. **DB 写入正常**：所有 5 个新对话都被记到 `user_conversation` 表

## ❌ 发现的问题

### 问题 1：schedule 没被记录（Test 4）

**现象**：
- 用户问 "创建一个 5 分钟一次的定时任务"
- AI 回复成功，状态显示已创建
- 但 conversation.html flow 显示 `scheduleCount: 0`，事件流没有 SCHEDULE 事件
- toolCall=70 说明底层有调用，但 schedule 统计没捕获

**可能原因**：`ScheduleHistory` 写入走 `IScheduleTriggerRepository`，没经过 `loom_chat_usage` 路径

### 问题 2：MCP search tool_call 没被记录（Test 5）

**现象**：
- 用户问 "用 bing_search 搜索 Spring AI"
- AI 给了回复（237 calls 1.3M tokens 表明有响应）
- 但 toolCallCount = 0
- 事件流只有 USER + ASSISTANT，没有 TOOL_CALL 事件

**可能原因**：
- 5 个 MCP 服务在 /mcps 端点可见，但 `getVisibleToolCallbackProvider` 没把 `bing_search` 工具给当前用户
- 实际上 tool 没被调用，AI 是用模型知识直接回答的

---

## DB 增长验证

| 表 | 测试前 | 测试后 | 增量 |
|---|--------|--------|------|
| loom_chat_usage | 4035 | 6068 | +2033 |
| loom_chat_reasoning | 25 | 30 | +5 |
| loom_tool_call_log | 139 | 227 | +88 |
| loom_subtask_history | 18 | 22 | +4 |
| loom_scheduled_task | 0 | 1 | +1 |
| spring_ai_chat_memory | ~110 | 148 | +38 |

**结论**：所有 5 个新对话的元数据被正确记录到 DB，对话历史 38 条新消息（USER + ASSISTANT）。

---

## 建议修复

### P1：schedule 流程接入 stats

- 路由 `/admin/conversations/{id}/flow` 的 `computeStats` 没查 `loom_scheduled_task`
- 改 `ConversationFlowService.computeStats` 加 `select count(*) from loom_scheduled_task where conversation_id = ?`
- 事件流加 SCHEDULE 事件（查询 schedule_history）

### P2：MCP 工具实际可用性

- 检查 `getVisibleToolCallbackProvider` 是否返回 MCP 工具给 admin
- 当前 admin 是 ADMIN 角色，理论上应全可见
- 用 `/mcps` 端点验证 `bing_search` 是否在 list 里
- 如果是，可能 user_id 过滤或 role 权限问题
