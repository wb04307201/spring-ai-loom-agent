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

---

## Step 5 · knowledge 工具 —— D2 实证（控制器接管，先做因为不需等 LLM）

**修正**：spec 原判 D2 为 P0（预期上传 500）。**读源码后修正为 P1** ——
`H2JVectorStore` 只调 `embeddingModel.embed(...)`（L208/L309），**从不调 `call()`**，
而 fake 的 `embed()` 有实现。全库 grep 无 `.call(embed...)` 调用点。

**实测**：

```
PUT  /knowledge  {"name":"e2e-knowledge",...}  → 200 {"id":"dbee7b15-755c-440e-8acd-a7816c1204ee"}
POST /knowledge/dbee7b15.../upload              → 200 {"status":"success","fileId":"dde514b0-..."}
```

**上传成功，不是 500** ✓ 修正正确。

**但向量维度证实了问题**：

```sql
SELECT document_id, dim, LENGTH(embedding), SUBSTRING(content,1,40) FROM loom_vector_store;
→ c41501f4-...  |  dim=4  |  15 bytes  |  "LoomAgent 是一个 Spring Boot AI Agent 库…"
```

| 检查项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| 上传返回 | 200 或 503 | **200** | ✓ 链路通 |
| 向量落库 | 有行 | **1 行** | ✓ |
| 文档内容 | 正确 | ✓ 中文完整 | ✓ |
| **向量维度** | 真实模型维度（1024+） | **dim=4** | **P1** |

**P1 定级理由**：`TestAnthropicOptionsConfig` 的 fake `EmbeddingModel` 返回
`new Random(text.hashCode())` 生成的 **4 维随机向量**。上传与检索链路**都能跑通**，
但向量空间是随机的 —— 检索排序**语义上无意义**（问"A"返回的是随机相似文档，
不是真正相关的内容）。知识库作为 RAG 核心能力，在本 test app 配置下**不可用于验证**。

**影响面**：这是 test app 的配置问题（`TestAnthropicOptionsConfig` 在 `src/main/java`），
不是库代码缺陷。但它让 Task 5（维度 E）的全部检索断言失去意义 ——
**Task 5 的判定标准必须据此调整**：不能断言"检索到相关内容"，只能断言"链路不崩"。

---

## Step 3 · file 工具 —— 通过 ✓

**问法**：`请读取 e2e/sample.txt 这个文件，把里面的内容原样告诉我。`

**前置**：`spring-ai-loom-agent-test/target/test-users/wb04307201/file/e2e/sample.txt`
内容 = `E2E 测试内容 2026-10-06`

**实测**（从 `#mainContent` 的 HTML 提取）：

思考链原文：
> 用户想读取 e2e/sample.txt 文件并原样输出内容。我直接调用 readTextFile 工具即可。
> 读取成功，内容是 `"E2E 测试内容 2026-10-06"`，原样返回给用户即可。

AI 回复：
> 文件内容如下（原样）：
> ```
> "E2E 测试内容 2026-10-06"
> ```

**判定：调用成功** ✓ —— LLM 明确调用 `readTextFile`，读回内容与文件系统实际内容
**逐字一致**。沙箱路径校验通过（用户 `PathSecurityUtils.java` 的 WIP 未造成阻碍）。
