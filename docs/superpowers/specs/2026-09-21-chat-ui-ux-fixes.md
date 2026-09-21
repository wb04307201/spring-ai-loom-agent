# Chat UI UX 三项修复 — 设计规格

**作者**: Claude Code
**日期**: 2026-09-21
**状态**: Draft (待 review)
**目标版本**: v1.1.48 (loom-agent)
**关联提交**: 无（独立 spec）。生产现状的 UX 摩擦点。

---

## 1. 背景与动机

生产用户反馈 + 本次端到端测试（2026-09-21）暴露 chat UI 三处摩擦点。

### 1.1 三个问题

| # | 问题 | 当前代码位置 | 用户感受 |
|---|---|---|---|
| **#1** | 子任务不可见 | `app.js:3719-3724` `showStream()` 只 toast | 用户不知道子任务是否启动 / 跑多久 / 是否完成；只能切到"子任务"面板看 |
| **#2** | 强制滚动 | `app.js:1244-1247` `scrollToBottom()` 无条件置底 + `app.js:1923` 每次流式 chunk 调用 | 用户向上滚看历史时，AI 一回答就被强行拉回，体验断裂 |
| **#3** | askUser 卡片游离 | `app.js:1764-1786` 卡片在 AI 提问气泡内；提交后**summary 仍在该气泡**，AI 的回复会**新建气泡** → 视觉上是"AI 提问 / 已答摘要 / AI 回答"三个独立模块割裂 | 用户体验上 askUser 卡片像"扔出来的另一段"，与 AI 回复没有视觉关联 |

### 1.2 本规格治全部三件

---

## 2. 设计目标

| ID | 目标 | 衡量 |
|---|---|---|
| G1 | 子任务实时可见 | 单测：子任务事件触发后 chip 出现在 chat 流中；终态 30 秒淡出 |
| G2 | 智能滚动 | 单测：用户向上滚后 AI 流不强制置底；回到底部时恢复跟随 |
| G3 | askUser 卡片与 AI 回复同气泡 | 单测：提交答案后，AI 的最终回复**追加到同一气泡**而非新建 |
| G4 | 不引入新外部依赖 | 仅用 vanilla DOM/CSS/JS，不引外部库 |
| G5 | 不破坏现有契约 | `DefaultChatSubTaskGuidanceContractTest` (10) + `FriendlyMessageJsonEofTest` (3) + 全模块 215 测试无回归 |

---

## 3. 架构总览

| 模块 | 文件 | 改动 |
|---|---|---|
| **#1a 后端** | `SseController.java` | 在子任务生命周期事件上向父 conversation 推送 `subTaskStart/Update/End` 帧 |
| **#1b 后端** | `DefaultSubTaskExecutor.java` | 注册 `SubTaskRegistry` 钩子；状态变化时 push 帧 |
| **#1c 前端** | `app.js` | 流式回调识别 `subTaskStart/Update/End`，渲染紧凑 chip |
| **#2** | `app.js` | `scrollToBottom()` 加 `isAtBottom` 守卫；底部浮动按钮"↓ 回到底部" |
| **#3** | `app.js` | askUser 卡片与 AI 回复共用同一气泡（方案 A） |

5 个文件改动 + 新单测。

---

## 4. 详细设计

### 4.1 #1a — SseController 子任务事件推送

**File**: `spring-ai-loom-agent-spring-boot-autoconfigure/.../LoomAgentConfiguration.java`（SseController 内部类，约行 575-700）

在 `stream()` 方法内，子任务事件流合并到主对话 SSE 流：
- 当 `subTaskRegistry` 检测到子任务**启动** → 通过 `SseEmitterRegistry.broadcast(parentConvId, new SubTaskStartEvent(...))` 推帧
- 当子任务**进度更新**（可选：每秒/每 5 秒） → `SubTaskUpdateEvent`
- 当子任务**完成/失败/取消** → `SubTaskEndEvent`

推送事件帧格式（嵌入 ChatResponseRecord 的额外字段）：
```java
public record SubTaskEvent(
    String subTaskId,
    String status,      // "RUNNING" / "COMPLETED" / "FAILED" / "CANCELLED"
    String prompt,      // 前 80 字（让用户知道在做什么）
    long startedAt,
    long elapsedMs,
    String errorMessage // null if status != FAILED
) {}
```

后端 SSE 帧格式沿用现有 `ChatResponseRecord(text, reasoningContent, askUser, **subTaskEvent**)` 增加一个字段。

### 4.2 #1b — DefaultSubTaskExecutor 钩子

**File**: `spring-ai-loom-agent/.../subtask/DefaultSubTaskExecutor.java`

新增方法 `publishSubTaskEvent(SubTaskEvent event, String parentConvId)`，通过注入 `SseEmitterRegistry` 推帧。

调用点（现有代码位置）：
- `doExecute()` 入口（行 ~145 附近，execute 真正开始前）→ push `SubTaskStartEvent`
- `execute()` catch `TimeoutException`（Task 2 改的位置，行 ~210）→ push `SubTaskEndEvent(FAILED)`
- `execute()` 正常结束（行 ~190 附近）→ push `SubTaskEndEvent(COMPLETED)`

依赖注入：在 `DefaultSubTaskExecutor` 构造器加 `SseEmitterRegistry` 参数（或 setter）；`LoomAgentConfiguration.defaultSubTaskExecutor` 8-arg 构造器同步更新。

### 4.3 #1c — 前端 chip 渲染

**File**: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js`

新增模块 `subTaskChips`：
```js
const subTaskChips = (() => {
  const active = new Map();  // subTaskId -> { el, timer, status, startedAt }

  function fmtElapsed(ms) {
    const s = Math.floor(ms / 1000);
    if (s < 60) return s + "s";
    return Math.floor(s / 60) + "m " + (s % 60) + "s";
  }

  function renderStart(ev) {
    // 在当前 AI 气泡的正文末尾插入 chip
    const bubble = document.querySelector("#current-bot-bubble");  // 见 G3 锚点
    if (!bubble) return;
    const chip = document.createElement("div");
    chip.className = "subtask-chip subtask-chip-running";
    chip.dataset.subTaskId = ev.subTaskId;
    chip.innerHTML = `
      <span class="subtask-chip-spinner">⏳</span>
      <span class="subtask-chip-id">${escapeHtml(ev.subTaskId.slice(0, 8))}</span>
      <span class="subtask-chip-elapsed">0s</span>
      <span class="subtask-chip-prompt">${escapeHtml(ev.prompt)}</span>
      <span class="subtask-chip-expand">▸</span>
    `;
    bubble.appendChild(chip);
    // 启动 ticker
    const startedAt = ev.startedAt;
    const timer = setInterval(() => {
      const el = chip.querySelector(".subtask-chip-elapsed");
      if (el) el.textContent = fmtElapsed(Date.now() - startedAt);
    }, 1000);
    active.set(ev.subTaskId, { el: chip, timer, status: "RUNNING" });
    // 点击 chip → 打开子任务面板
    chip.addEventListener("click", () => subTaskPanel.open(ev.subTaskId));
  }

  function renderUpdate(ev) {
    const c = active.get(ev.subTaskId);
    if (!c) return;
    c.el.className = `subtask-chip subtask-chip-${ev.status.toLowerCase()}`;
  }

  function renderEnd(ev) {
    const c = active.get(ev.subTaskId);
    if (!c) return;
    clearInterval(c.timer);
    c.el.className = `subtask-chip subtask-chip-${ev.status.toLowerCase()}`;
    c.el.querySelector(".subtask-chip-spinner").textContent =
      ev.status === "COMPLETED" ? "✓" : ev.status === "FAILED" ? "✗" : "⊘";
    c.el.querySelector(".subtask-chip-elapsed").textContent = fmtElapsed(ev.elapsedMs);
    c.el.querySelector(".subtask-chip-expand").textContent = "详情";
    // 30 秒后淡出（终态）
    setTimeout(() => c.el.classList.add("subtask-chip-fading"), 30000);
    active.delete(ev.subTaskId);
  }

  return { renderStart, renderUpdate, renderEnd };
})();
```

CSS（追加到 `style.css` 或 `app.js` 内的 inline style）：
```css
.subtask-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  margin: 8px 0;
  background: var(--bg-secondary, #f5f5f5);
  border: 1px solid var(--border-color, #d9d9d9);
  border-radius: 16px;
  font-size: 13px;
  cursor: pointer;
  transition: opacity 1s, background 0.3s;
}
.subtask-chip-running { background: #e6f7ff; border-color: #91d5ff; }
.subtask-chip-completed { background: #f6ffed; border-color: #b7eb8f; }
.subtask-chip-failed { background: #fff1f0; border-color: #ffa39e; }
.subtask-chip-fading { opacity: 0.3; }
```

流式回调接入（`app.js:1900-1924`）：
```js
if (data.subTaskEvent) {
  if (data.subTaskEvent.status === "RUNNING" && data.subTaskEvent.startedAt) {
    subTaskChips.renderStart(data.subTaskEvent);
  } else if (data.subTaskEvent.status === "RUNNING") {
    subTaskChips.renderUpdate(data.subTaskEvent);
  } else {
    subTaskChips.renderEnd(data.subTaskEvent);
  }
}
```

工具栏"子任务"按钮徽章（`app.js:3391` 附近）：
- 当 `subTaskChips.active.size > 0` 时显示 `.subtask-button-badge: <count>`
- 点击徽章 = 打开子任务面板

### 4.4 #2 — 智能滚动

**File**: `app.js`

修改 `scrollToBottom()` 类成员：

```js
// 改前（app.js:1244-1247）
scrollToBottom() {
  if (this.mainContent)
    this.mainContent.scrollTop = this.mainContent.scrollHeight;
}

// 改后
scrollToBottom() {
  if (!this._isAtBottom) return;       // 用户已上滑,不打断
  if (this.mainContent)
    this.mainContent.scrollTop = this.mainContent.scrollHeight;
}
```

初始化时（`ui` 类构造或 DOM ready）绑定 scroll 事件：

```js
this.mainContent.addEventListener("scroll", () => {
  const el = this.mainContent;
  const threshold = 80;                   // px
  this._isAtBottom = (el.scrollTop + el.clientHeight >= el.scrollHeight - threshold);
  this._backToBottomBtn.classList.toggle("visible", !this._isAtBottom);
});
```

浮动按钮（添加到 chat 输入框附近）：
```html
<button class="back-to-bottom-btn" id="back-to-bottom-btn">↓ 回到底部</button>
```
```js
this._backToBottomBtn = document.getElementById("back-to-bottom-btn");
this._backToBottomBtn.addEventListener("click", () => {
  this._isAtBottom = true;
  this.scrollToBottom();
  this._backToBottomBtn.classList.remove("visible");
});
```

CSS：
```css
.back-to-bottom-btn {
  position: absolute;
  right: 20px;
  bottom: 80px;       /* 在输入框上方 */
  padding: 8px 14px;
  background: var(--primary-color, #4f46e5);
  color: white;
  border: none;
  border-radius: 20px;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(0,0,0,0.15);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.2s;
}
.back-to-bottom-btn.visible {
  opacity: 1;
  pointer-events: auto;
}
```

### 4.5 #3 — askUser 卡片与 AI 回复同气泡

**File**: `app.js`

**核心改动**：让 askUser 卡片**和**后续 AI 回答**共用同一气泡**。

**实施步骤**：

**Step 1**: 修改 `renderBotMessage(id)`（app.js:1177-1205），把气泡的 `<div id="${id}">` 加 class `current-bot-bubble`，并把 id 提到 bubble 上：

```js
// 改前
<div id="${id}" style="margin: 16px"></div>

// 改后
<div id="${id}" class="bot-content current-bot-content" style="margin: 16px"></div>
```

**Step 2**: 修改 `askUserCards.render()`（app.js:1763-1786）。当 askUser 卡片渲染时，**先创建一个** `current-bot-content` 气泡（如果还没有），把卡片挂到里面，AI 的内容流式会写入同一节点：

```js
// 改前（app.js:1763-1786）
const item = document.createElement("div");
item.className = "chat-item chat-item-left";
item.innerHTML = `...<div class="bubble">...</div>...`;
ui.mainContent.appendChild(item);

// 改后：复用 askUserBotBubble helper
function askUserBotBubble(qid) {
  if (!ui._currentAskUserBubble) {
    const item = document.createElement("div");
    item.className = "chat-item chat-item-left";
    item.id = "askuser-bubble-" + qid;
    item.innerHTML = `
      <div class="avatar"><img src="${aiImage}" alt="AI"/></div>
      <div class="bubble askuser-bubble">
        <div class="askuser-slot" id="askuser-slot-${qid}"></div>
        <div class="bot-content current-bot-content" id="askuser-bot-content-${qid}"></div>
        <div class="bubble-actions" id="actions-askuser-${qid}" style="display: none;">
          <button class="bubble-action-btn" onclick="ui.copyMarkdown('askuser-origin-${qid}')">📋 复制</button>
          <button class="bubble-action-btn" onclick="ui.downloadMarkdown('askuser-origin-${qid}')">💾 下载</button>
        </div>
        <div id="askuser-origin-${qid}" style="display: none"></div>
      </div>`;
    ui.mainContent.appendChild(item);
    ui._currentAskUserBubble = item;
    // 标记流式输出目标
    ui._currentAnswerEl = document.getElementById(`askuser-bot-content-${qid}`);
    ui._currentOriginEl = document.getElementById(`askuser-origin-${qid}`);
    ui._currentActionsEl = document.getElementById(`actions-askuser-${qid}`);
    ui._currentBubbleId = qid;
  }
  return ui._currentAskUserBubble;
}
```

**Step 3**: 流式回调（app.js:1900-1924）的 `data.content` / `data.reasoningContent` 写到 `_currentAnswerEl` 而非新建气泡。

**Step 4**: 用户提交答案（`submit(qid, ev)`）后：
- `freeze(qid, "已答 ✓", true, vals.join("、"))` 不变
- **不**新建气泡；后续流式 chunk 自动写入 `_currentAnswerEl`
- 流结束（complete 回调）：`_currentAskUserBubble` 标记为 null

**Step 5**: 新建对话或切换 conversation 时，重置 `_currentAskUserBubble = null`。

**风险点**：`_currentAnswerEl` 切换时序。多个 askUser 卡片快速连发时，要确保每次切换正确。详见后续 plan 步骤。

---

## 5. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| R1: 子任务事件 push 漏帧（racing） | 中 | chip 不出现 | 重试一次 + 状态从 `SubTaskRegistry` 兜底 |
| R2: 智能滚动监听器误判（80px 阈值） | 低 | 用户上滑后被拉回 | 阈值 = 80px（典型聊天 App 标准） |
| R3: askUser 同气泡与历史消息渲染冲突（`renderMessages`） | 中 | 历史回放错乱 | `renderMessages` 内强制重置 `_currentAskUserBubble = null` |
| R4: 后端 `SseEmitterRegistry` 已被回收导致 push 失败 | 低 | chip 缺失 | 用 try/catch 吞异常 + log warn |

---

## 6. 测试策略

| 层 | 文件 | 验证 |
|---|---|---|
| **前端单测** | `ChatUiUxFixesContractTest.java` (新) | #2 #3 关键 DOM 行为：智能滚动守卫、askUser 共气泡 |
| **端到端** | 手动 Playwright | #1 #2 #3 在真实聊天中验证 |

**单测最小集合**：
- `testScrollGuardsWhenUserScrolledUp`：mock mainContent.scrollTop = 100（远大于 80），调 `scrollToBottom()`，断言 scrollTop 不变
- `testAskUserBubbleReused`：模拟 askUser → submit → 流式 chunk → 断言 chunk 进入 `current-bot-content` 而非新建 `<div>`
- `testSubTaskChipMount`：mock `data.subTaskEvent`，断言 chip DOM 出现

注：前端单测需要 jsdom 或类似环境，loom-agent 当前测试栈没装。**降级方案**：纯 JS 模块级小脚本 + node 跑通（不挂到 mvn test）。或者降级为"主对话集成测试 + 文档示例"。

---

## 7. 验收标准

1. ✅ #1：触发"工厂产线工序主数据维护"消息 + 5 轮 askUser + 子任务 → chat 流中**实时**显示 chip（含 0s → 1m23s → ✓ 的状态转换）
2. ✅ #2：用户上滑到历史消息，AI 回答**不再拉回**；点"↓ 回到底部"按钮恢复跟随
3. ✅ #3：askUser 卡片和 AI 回答**在同一气泡**里，视觉上像一个连贯单元
4. ✅ 单测锁契约（即使前端单测环境未配齐，至少 #2 #3 用纯 JS 脚本验证）
5. ✅ 全模块回归 215+ 测试无回归

---

## 8. 部署与回滚

### 8.1 部署

`mvn clean install ... -am -Dgpg.skip=true` → 部署新 jar

### 8.2 回滚

`git revert HEAD~1..HEAD` 即可（5 个文件改动独立）

### 8.3 用户体验回退

- #2 智能滚动如果用户反馈"按钮太大" → 调 CSS
- #3 askUser 同气泡如果用户反馈"卡片被 AI 内容挤压看不清" → 卡片始终置顶，加 sticky 样式

---

## 9. 范围之外

- ❌ 引入前端框架（Vue/React）— 保持 vanilla
- ❌ WebSocket / SSE 协议改动（沿用现有 SSE 帧）
- ❌ 移动端专属适配（保持桌面端）
- ❌ 子任务实时工具调用日志流（chat 流式中的 tool_calls 已是另一种机制）

---

## 10. 时间估算

| 项 | 行数 | 时间 |
|---|---|---|
| #1a 后端 sub-task 帧推送 | ~80 行 | 25 分钟 |
| #1b DefaultSubTaskExecutor 钩子 | ~30 行 | 10 分钟 |
| #1c 前端 chip 渲染 | ~100 行（含 CSS） | 30 分钟 |
| #2 智能滚动 + 浮动按钮 | ~40 行 | 10 分钟 |
| #3 askUser 同气泡 | ~80 行 | 25 分钟 |
| 单测（#2 #3 纯 JS 脚本） | ~100 行 | 30 分钟 |
| 集成 + 端到端验证 | - | 30 分钟 |
| CLAUDE.md 同步 | ~5 行 | 5 分钟 |
| **合计** | **~435 行 + 新单测** | **~165 分钟** |

---

## 11. 关键参考

- CLAUDE.md `IChat` 条目
- 2026-09-21 端到端测试日志 `bp2t16l1m.output`
- 上一个 plan（任务分段 + 子任务超时）`docs/superpowers/specs/2026-09-21-subtask-timeout-and-prompt-tuning.md`

---

**请 review 后决定是否进入 writing-plans 阶段。**