# Task 4 · 维度 D 工具层巡检报告

**执行时间**：2026-10-06 13:42 起
**服务**：`http://localhost:8080`（后台任务 `b4tmcsfd5`，日志 `/tmp/loom-e2e-server.log`）
**账号**：`wb04307201` / `123456`（ADMIN + `base` 角色）
**复用会话**：`9988fe61-dc5f-4b95-a11b-a2b59f793d71`
**LLM**：MiniMax-M3（thinking 开启），单条消息 30-180 秒

---

## Step 1 · RBAC 前置 —— 当前授权快照

登录端点是 `POST /spring/ai/loom/user/login`（**注意**：brief 未给出此路径，
`/spring/ai/loom/login` 返回 401 —— 该路径不存在）。admin cookie：
`loom-agent-session=ad810448-f296-4858-9e1c-eaba5efb1439`。

`GET /spring/ai/loom/api/capabilities`（当前用户可见面）：

| type | id | effectiveEnabled | 工具数 |
|---|---|---|---|
| LOCAL | tool_compile | **false** | 1 |
| LOCAL | tool_git | **false** | 28 |
| LOCAL | tool_http | **false** | 5 |
| LOCAL | tool_maven | **false** | 6 |
| LOCAL | tool_render | **false** | 1 |
| MCP | spring-ai-mcp-client - @tokenizin-agency/mcp-npx-fetch | true | 4 |
| MCP | spring-ai-mcp-client - bing-search | true | 2 |
| MCP | spring-ai-mcp-client - mcp-server-chart | true | 27 |
| MCP | spring-ai-mcp-client - sequential-thinking | true | 1 |

**结论**：

1. **7 个 universal 工具（`tool_time` / `tool_file` / `tool_skill` /
   `tool_knowledge` / `tool_subtask` / `tool_schedule` / `tool_askUser`）
   完全不出现在 `/api/capabilities` 响应里** —— 这与设计一致（CLAUDE.md：
   「聊天面板工具弹窗**完全不展示** universal 工具的 checkbox」），
   它们由 `@ToolGroup(defaultGranted=true)` 走 `visibleToolGroupsFor()` 的
   `∪ universal` 分支直接放行，不经 `role_tool` 表。所以 universal 工具
   **无需任何前置授权**，可以直接测。
2. **5 个 RBAC 工具全部 `effectiveEnabled=false`** —— `base` 角色当前
   未授 `tool_git` / `tool_maven` / `tool_render` / `tool_http` / `tool_compile`，
   与 CLAUDE.md 记载的默认状态一致。测这 5 个前需先用 H2 直插 `role_tool`
   授权（Step 6b 起）。
3. 4 个 MCP server 全部在线（与 Task 0 预检一致）。

---
