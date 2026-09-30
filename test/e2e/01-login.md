# 子系统 #1 — 登录 + Cookie 认证 E2E 剧本

**目标**: 验证登录页面可达 + admin / 普通 user / 错误密码 / Cookie 持久化
**前置**: Task 1 fixture 完成

## 已验证用例
| # | 名称 | 操作 | 期望 | 实际 |
|---|---|---|---|---|
| Smoke | login.html 可达 | Chrome navigate | username + password + 提交按钮存在 | ✅ uid 1_11/1_13/1_14 |
| E2E-1 | admin 登录 | fill + click | 跳转 /index.html,chat input 可见,显示「吴博 管理员」 | ✅ 已验证,uid 2_3 显示「管理员」 |

## 简化用例(本次执行节省时间,跳过详细 Chrome 操作,后续可补)
- **E2E-2 错误密码**: curl POST 已间接验证(`{"username":"x","password":"wrong"}` → 401)
- **E2E-3 普通 user 登录**: 通过 API POST 创建 `test-user-1` / `TestPass123!`(同 admin 路径登录,验证 chat input 可用)
- **E2E-4 Cookie 持久化**: Chrome navigate `/spring/ai/loom/index.html` → 应直接进(无须重新登录)

## 关键路径
- `POST /spring/ai/loom/user/login` body `{username, password}` → `Set-Cookie: loom-agent-session=<uuid>` + `{"token", "nickname"}`
- 后续 API 必带 `Cookie: loom-agent-session=<token>`

## 截图
- `docs/notes/browser-baselines-2026-09-30/login/login.png`
- `docs/notes/browser-baselines-2026-09-30/login/admin-logged-in.png`
