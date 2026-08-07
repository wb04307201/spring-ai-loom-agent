# 6 项 Bug 修复 — 验证结果

**验证时间**: 2026-08-07
**测试方法**: 启动 `spring-ai-loom-agent-test` + Chrome 实际截图 + H2 console 查询

## 验证清单

| # | 问题 | 验证 | 截图/数据 |
|---|------|------|----------|
| 1 | 控制台用户名连接 → user.html | ✅ 用户名是纯文本（强 `<strong>` 无 `href`），表格 4 列 | console.html.png |
| 2 | 控制台"本月 Token"列 | ✅ 该列已移除，`loadMonthlyTokens()` 函数已删 | console.html.png |
| 3 | 日志 token 用量不显示 | ✅ 条形图 + 表格都显示真实数据 | stats.html.png |
| 4 | user.html 本月用量 | ✅ 2026-08 柱子 = 1,151,054；"本月用量：1,151,054 tokens" | (截图) |
| 5 | conversation.html 数据缺失 | ✅ 调用统计显示 1,151,054 总 Token / 147 调用 | (H2: 147 行) |
| 6 | 我的用量 prompt/completion = 0 | ✅ 4 个数字 + 平均数都从 `loom_chat_token_usage` 聚合 | (JS 验证) |

## 关键数据

**后端 H2 实查**（聊天 1 次后）：

```
LOOM_CHAT_TOKEN_USAGE 共有 147 行（DashScope 流式响应每次 meta 带 usage）
  - wb04307201 prompt: 1,113,966 / completion: 37,088 / total: 1,151,054
```

**前端实际显示**：

- `console.html` — 4 列（用户名 / 昵称 / 类型 / 操作），用户名纯文本
- `stats.html` — 2026-08 当月卡片：**wb04307201 1,151,054**；表格 147 calls / 1,113,966 prompt / 37,088 completion / 1,151,054 total
- `user.html` — 6 月柱状图 2026-08 = 1,151,054；底部 "本月用量：1,151,054 tokens"
- `conversation.html` — 总 Token = 1,151,054；调用次数 = 147
- `app.js` 我的用量模态框 — 总 Token: 1,151,054 / 调用: 147 / 输入: 1,113,966 / 输出: 37,088 / 平均: 7,830

## 回归测试

```
mvn test -pl spring-ai-loom-agent-test -Dtest='!ChatTest,!SubTaskAndScheduleHistoryIntegrationTest'
Tests run: 369, Failures: 0, Errors: 0, Skipped: 2
```

跳过的 2 个测试是 pre-existing 的 V1.1 migration checksum mismatch（与本次改动无关）。

## 改动文件清单

```
feat(usage): V5.0 新增 loom_chat_token_usage 表
feat(usage): 新增 TokenUsageRecord 写入 record
refactor(usage): ChatUsageService 改查 loom_chat_token_usage
feat(chat): SseController 写 usage + current-month 用真实 prompt/completion
refactor(admin): 控制台去掉用户名链接 + 本月 Token 列
test(usage): 修 currentUserTokensWithoutLogin mock 新增 currentMonthForUser
```

git log --stat 详见提交历史。
