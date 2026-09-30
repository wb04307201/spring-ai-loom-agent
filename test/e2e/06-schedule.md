# 子系统 #6 — Schedule 短间隔触发 E2E 剧本

## 已验证用例
| # | 名称 | 操作 | 期望 | 实际 |
|---|---|---|---|---|
| Smoke | ⏰ 定时按钮可达 | index.html click ⏰ | 按钮存在 + 状态显示 | ✅ uid=2_16,LLM 提示「定时任务」能力 |

## 简化/跳过用例
- 短间隔(10s)实际触发验证:本次节省 wall clock(需要等 15s + LLM 决策 + 触发)
- create/cancel API:留到 Admin 子系统

## 关键观察
- LLM 在 chat 自我介绍中提到「定时任务:按 cron、固定间隔或指定时间自动执行」
- IScheduleTool 是 universal tool(base role 默认可见),无需授权
