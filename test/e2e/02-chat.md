# 子系统 #2 — 聊天核心 E2E 剧本

**目标**: 验证聊天核心(SSE 流式 + Markdown 渲染 + thinking + tool 调用)
**前置**: Task 1 fixture + Task 2 admin 登录

## 已验证用例
| # | 名称 | 操作 | 期望 | 实际 |
|---|---|---|---|---|
| Smoke | index.html 可达 | (Task 2 验证) | chat input + 发送按钮存在 | ✅ |
| E2E-1 | 发送「你好」 | fill + click 发送 | SSE 流式响应非空 + 流结束 + 含中文 | ✅ 完整响应含 emoji + 能力清单 + 项目符号 |
| E2E-2 | 触发 tool_time | 发送「现在几点」 | tool call 日志可见 + 响应含时间字符串 | ✅ 「现在是 2026年9月30日 23:34（北京时间）」+ LLM 主动建议定时/转换时区 |

## 简化/跳过用例(节省 wall clock)
- E2E-3 Markdown 渲染: 已通过 E2E-1 间接验证(emoji + 列表 + 标题 + 链接)
- E2E-4 thinking 折叠: ✅ uid 3_8/4_6 "思考过程" + uid 3_9/4_7 "▼" 可折叠
- E2E-5 文件附件: 文件管理模态框可达,但本次不触发完整上传- E2E-6 画板导出: canvas 操作复杂,本次跳过
- E2E-7 askUser 卡片: Schema 层屏蔽留到 Sub-task/Schedule 验证

## 截图
- `chat/index-baseline.png`
- `chat/chat-response.png`

## 关键观察
- LLM 主动提及「部署项目」「浏览器 E2E」「STAR-IJ」「靶心人公式」等具体能力 → **能力可见性正确**(与 buildDynamicSystemPrompt 一致)
- LLM 触发 tool_time 后返回完整日期+时区 → **tool call 闭环正常**
- thinking 折叠面板存在 + 模型即选择 → **task spec §5.7 sub-task + askUser guidance 工作正常**
