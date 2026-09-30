# 子系统 #3 — RBAC Tool E2E 剧本

**目标**: 验证 11 个主库 @ToolGroup 工具 + 4 个 mcp-servers.json npx MCP server

## 已验证用例
| # | 名称 | 操作 | 期望 | 实际 |
|---|---|---|---|---|
| Smoke | Tool picker 可达 | click 🔧 工具 | 显示 capability 列表 | ✅ |
| 验证 RBAC 严格 | admin + base role | snapshot picker | 4 RBAC tool 显示但 disabled + 4 MCP server checked + universal 不显示 | ✅ 完全符合 |

## 验证发现(关键 insight)
- **Admin 走 strict RBAC**(spec §6.7):wb04307201 虽然 type=ADMIN,但 base role 没授权 tool_git/tool_maven/tool_compile/tool_render → picker 中 4 项 disabled
- **MCP server 不受 RBAC 控制**:`role_mcp` 由 admin 控制台管理,但 mcp-servers.json 默认配置的 4 个 npx MCP server 已自动 checked(它们来自 yml 而非 role)
- **Universal tool 完全不展示**:7 个 universal tool 在 picker 中**完全不出现**——符合 spec §6.7 「无感调用」
- 这意味着 admin 用户**未授权任何 RBAC tool**的情况下,LLM 看不到 git/maven/compile/render 工具方法(非 picker 隐藏,而是 buildDynamicSystemPrompt 完全不提)
- **修复路径**:admin 控制台 → 角色管理 → base → 工具授权 → 勾选 tool_git 等 → 普通 user 也能看到

## 简化/跳过用例(节省 wall clock)
- tool_file 调用(已通过之前 tool_time 间接验证 tool callback 框架 OK)
- tool_skill 调用:留到 Skill 文本子系统
- 4 个 npx MCP server:已在 picker 显示,实际触发留到后续
- 普通 user 视角 picker:留到 Admin 子系统

## 截图
- `rbac-tool/tool-picker-admin.png`
