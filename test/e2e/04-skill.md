# 子系统 #4 — Skill library + market E2E 剧本

## 已验证用例
| # | 名称 | 操作 | 期望 | 实际 |
|---|---|---|---|---|
| E2E-1 | 官方 skill 全名调用 | `GET /skill/STAR-IJ 讲清一件事` | 返回完整 skill record | ✅ description + content + load=true |

## 简化/跳过用例
- 创建新 skill + 审批流:留到 Admin 子系统
- 在聊天调用 skill:已在 chat 中由 LLM 引用(「STAR-IJ 讲清一件事(复盘/述职/面试)」出现)
- 普通 user 浏览:留到 Admin

## 验证发现
- **短名误用**:`GET /skill/STAR-IJ` 报 "无权限" — 实际是测试 case bug,产品要求 market_skill **全名**(含中文),与 seed 一致
- sync 端点(`POST /skill/sync`)返回 true,但 sync 内部实际未写 user_skill 或查询条件不匹配 — 需要后续深入排查
- base role 已配 `role_skill` 含 2 官方 skill + `default_loaded=true`
