# Spring AI LoomAgent v1.1.48 Release Notes

**发布日期**: 2026-09-22  
**版本**: 1.1.48 (1.1.47 → 1.1.48)  
**总变更**: 26 commits, 5 features, 4 fixes, 5 docs, 2 tests, 6 chores + cleanups  
**覆盖时段**: 2026-09-21 (昨天) ~ 2026-09-22 (今天)

> 本项目不维护 CHANGELOG.md,本文件作为 v1.1.48 版本的 release summary。如需逐 commit 历史,查 `git log`。

---

## 🚀 一句话总结

v1.1.48 解决了一个 Spring AI 1.1.8 流式协议的 `JsonEOFException` 致命 bug(2026-09-21 生产事故),并通过让 LLM 主动拆长 reasoning 任务为子任务,从源头避免触发该 bug;同时给 chat UI 加了 3 项 UX 修复(子任务 chip 实时显示 + scroll 智能守卫 + askUser 卡片与 AI 回复共用气泡)。

---

## 📦 主题分组

### 主题 1: 长 reasoning 任务截断 bug — 治未病 + 治已病

**背景**: 2026-09-21 生产事故。Spring AI 1.1.8 `ToolUseAggregationEvent.squashIntoContentBlock` 在 tool_use 流未完整到达时强制解析不完整 `input_json`,触发 `JsonEOFException`。本地复现:Qwen 长 reasoning (~62K token / 234s 静默 / column 34068)。原 fallback 把异常包成 `RuntimeException`,用户只看到"聊天服务异常",误导根因。

**治已病(commit `488db810`)**: `friendlyMessage` 新增 JsonEOFException 分支 + 3 条可操作建议(重发 / 调 max-tokens / 升级 Spring AI 2.0+),`FriendlyMessageJsonEofTest` 3 用例。

**治未病(commits `238e73e2` `0d0fae37` `2ebf530b` `d26f133c` `06950052`)**: 
- `buildDynamicSystemPrompt` 新增【任务分段执行】段(4 类必拆场景 + 触发协议 + 1 完整 few-shot 轨迹)
- `start_sub_task` 的 `@Tool` description 加用途段(正向引导,避免抑制型措辞)
- 触发协议:LLM 见到 reasoning 超长会自动拆为子任务
- 回归: `DefaultChatSubTaskGuidanceContractTest` 9 断言锁定

**效果**: LLM 主动用 `start_sub_task` 拆任务,从源头避免触发 Spring AI 1.1.8 流协议上限。

---

### 主题 2: 子任务超时防御 + few-shot 调优 — 治标

**背景**: 主题 1 让 LLM 拆任务,但子任务内部仍可能因 Qwen 长输出卡住。

**commits `56097fbf` `ee597ca6` `06e3ff81` `e2802181` `7f01e9db`**:
- `few-shot` 例子改写:从 ~700 行 HTML 单子任务 → 每个子任务 ~150-200 行单模块 HTML
- 新增 `spring.ai.loom.agent.subtask.timeoutSeconds` 默认 600(10 分钟)
- `DefaultSubTaskExecutor.execute()` 用 `future.get(timeout)` + 捕获 `TimeoutException`
- 超时返回诊断文本:"子任务超时 N 秒,已自动取消",不再阻塞主对话
- 回归: `DefaultSubTaskToolTimeoutTest` 3 用例

**效果**: 即使 LLM 拆分失败导致单个子任务卡死,主对话也不会无限阻塞。

---

### 主题 3: 三项 Chat UI UX 修复

**commits `cf442395` `7bb27d8b` `11f60162` `337822f0` `0965ac48` `bbbce93a`**:
- **#1 子任务紧凑 chip**: `subTaskChips` 模块 + 实时显示 RUNNING/COMPLETED/FAILED 终态 + 30s 淡出
  - SSE 推送: `DefaultSubTaskExecutor.publishEvent` → `entry.emitter().send(ChatResponseRecord(... subTaskEvent))`
  - 前端: `data.subTaskEvent` 触发 `renderStart/renderEnd`
- **#2 智能滚动**: `scrollToBottom()` 加 `isAtBottom` 守卫 + "↓ 回到底部"浮动按钮(用户主动向上滚时不打断阅读)
- **#3 askUser 同气泡**: AI 首次回复气泡(renderBotMessage)和 askUser 卡片视觉合并(不再 3 个独立 chat-item)

**回归**: `ChatUiUxFixesContractTest` 3 断言锁定(jsdom-style 读源码字符串)。

---

### 主题 4: Bug 修复(测试期间发现的回归)

**`f838a790` (#3 同气泡契约修复)**: `askUserCards.render` 退化策略(已有 askuser 气泡 → 上一个 AI 气泡 → 新建独立),回归锁 ChatUiUxFixesContractTest#askUserFallsBackToLastBotBubble 3 断言。

**`7f251c21` (#1 subTaskChips 不显示)**: `LoomAgentConfiguration.defaultSubTaskExecutor` 的 `sseEmitterRegistry` 参数误加 `@Lazy` 导致注入 null → `publishEvent` 静默 no-op → chip 永远不渲染。修复:去掉 `@Lazy`(同方法内 defaultAskUserTool 的同名参数没 `@Lazy` 工作正常)。

**`69be9dfb` (subTaskChip 渲染位置 bug)**: #3 修复改了 `_currentAskUserBubble` 语义(.bubble → .chat-item),`subTaskChips.renderStart` 没同步,`bubble.appendChild(chip)` 实际把 chip 加到 chat-item 顶层,被 flex 拉成行内(71×1692px)。修复:`closest('.chat-item-left')` 升级到 chat-item,再用 `:scope > .bubble` 取直接子 bubble。

**`801ab019` (sub-task chip inline 展开)**: 原 click 调 `window.subTaskPanel.open()` 是空头支票(panel 从未定义,全代码仅引用 1 次)。改为 **inline 展开**:click chip → `wrapper.classList.toggle("expanded")`,展开 detail 面板显示完整 prompt / ID / 状态 / 错误。FAILED 时自动展开 + 显示错误堆栈。

---

### 主题 5: 清理 + 文档同步

**`5593eaeb`**: 清理调试阶段的 `[DIAG]` 诊断 log(`DefaultSubTaskExecutor.publishEvent` + `AskUserRegistry`)— 任务完成,无存在必要。

**`ba2daf05`** + **`e8dae0e5`**: spec 文档落地后归档。`docs/superpowers/{specs,plans}/2026-09-21-*.md`(3 specs + 2 plans,共 5 文件,约 2.7 KB)已删除,与 v1.2.0 cleanup (M3+ tech debt docs 删除)同模式;通过 git history 可恢复。CLAUDE.md spec 引用改为「git history 可恢复」措辞。

**`21eba4ba`**: 版本号更新至 1.1.48。

---

## 📊 数据

**总 commit 数**: 26

**后端 Java 文件改动**: 6 个文件(`LoomAgentConfiguration` / `DefaultSubTaskExecutor` / `AskUserRegistry` / `DefaultChat` / `DefaultAskUserTool` / `ChatResponseRecord` + `SubTaskEvent` 新 record)

**前端 JS/CSS 改动**: 2 个文件(`app.js` + `style.css`)

**新增测试**: 3 个(`FriendlyMessageJsonEofTest` / `DefaultChatSubTaskGuidanceContractTest` / `DefaultSubTaskToolTimeoutTest`)

**回归测试**: 6 / 6 全绿(`ChatUiUxFixesContractTest` + `DefaultSubTaskToolTimeoutTest` 等)

**新 spec/plans(已删)**: 3 + 2 = 5 文件

**代码净增**: 约 +700 行(含 3 个新 record + 2 个新类内函数 + CSS)

---

## ⚠️ 已知问题(留作后续)

**`[DIAG-AU] answer: qid=... lookupResult=NULL`**(测试脚本时序陷阱):Python SSE 客户端在 AI 流阻塞 `future.get(300s)` 期间,SseEmitter 缓冲 askUser 帧约 5 分钟才 flush。脚本收到帧时已超时,PendingQuestion 被 finally 清掉。生产无影响(浏览器 1 秒内 flush + 用户 < 5 分钟内答)。

**`subTaskPanel` 撤销承诺**: 0965ac48 设计文档里的弹窗从未实现,801ab019 已替换为 inline 展开方案。

---

## 🔗 相关链接

**上版 release**: v1.1.47 (2026-09-21) — `git log a89beaee..23b1ef3d`

**历史 spec/plans(已删,git history 可恢复)**: `docs/superpowers/{specs,plans}/2026-09-21-*.md`(`git log --all -- '*2026-09-21*'`)

**上一里程碑**: v1.2.0 (M0 + M1 + M2,Skill + Knowledge market) — `git log 26834b0..dceaddc`
