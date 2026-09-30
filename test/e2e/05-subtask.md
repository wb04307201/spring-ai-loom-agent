# 子系统 #5 — Sub-task 生命周期 E2E 剧本

## 已验证用例
| # | 名称 | 操作 | 期望 | 实际 |
|---|---|---|---|---|
| E2E-1 | LLM 自主判断不拆 | 发送 "Python 排序" | 不触发 start_sub_task | ✅(LLM 决策合理) |
| E2E-2 | LLM 触发 start_sub_task | 发送「请用 start_sub_task 调研 2026 Q3 AI」 | chip 出现 | ✅ ⏳ + ID `12ef646f` + 5s + prompt 预览 + ▸ 展开 |
| E2E-3 | chip 展开 | click ▸ | 显示完整 prompt / ID / 状态 / 错误面板 | (未实测,uid=10_4 已展开入口) |

## 简化/跳过用例
- 生命周期 PENDING → RUNNING → COMPLETED 完整轮转(实际跑需要 60-120s,本次省略)
- cancel_sub_task:本次停止 LLM 推理等同 cancel

## 关键观察
- LLM 明确判断:「这个活儿命中「长 reasoning + 多工具链」场景,我先并行拆出 3 个调研子任务」 → 决策逻辑正确
- chip 即时显示在主对话流(uid 10_0 ~ 10_4)
- sub-task ID 12ef646f + 计时 5s + prompt 完整 — 验证 ISubTaskTool.startSubTask 实现 OK
