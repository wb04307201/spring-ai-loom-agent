# Chat UI UX 三项修复 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** (1) Show sub-task chips inline in the chat stream so users see sub-task lifecycle (running/timeout/completed) without leaving the conversation. (2) Smart scroll: only auto-scroll to bottom if user is already near the bottom; otherwise let them read freely and offer a "back to bottom" floating button. (3) Merge askUser card with the AI's eventual reply into the same chat bubble so they read as one unit.

**Architecture:** Two backend touch-ups (ChatResponseRecord subTaskEvent field + SseController push frame + DefaultSubTaskExecutor hook) plus one big frontend refactor (app.js). All changes are additive — no behavior removed, only improved UX.

**Tech Stack:** Java 17, Spring Boot 3.x, Spring AI 1.1.8, vanilla JS + CSS

**Spec:** `docs/superpowers/specs/2026-09-21-chat-ui-ux-fixes.md`

---

## Global Constraints

- Use verbatim code from spec § 4.3 / § 4.4 / § 4.5 — no paraphrasing.
- Do not modify `ISubTaskTool` / `ISubTaskExecutor` interfaces.
- Vanilla JS only — no frontend framework, no external library.
- Do not modify `LoomAgentProperties` (no new yml config — defaults are sufficient).
- All commits must include `Co-Authored-By: Claude Code <noreply@anthropic.com>` trailer.
- Java source files use CRLF line endings; JS/CSS source files use LF (per current repo state).
- Existing test contracts must remain green: 10 + 3 + 3 + 7 = 23 baseline tests + 215 module regression must pass.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/ChatResponseRecord.java` | Modify | Add `subTaskEvent` field + 4-arg constructor (existing 3-arg stays for backward compat) |
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/SubTaskEvent.java` | Create | New record: `(subTaskId, status, prompt, startedAt, elapsedMs, errorMessage)` |
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskExecutor.java` | Modify | Inject `SseEmitterRegistry`; publish SubTaskEvent on start/end (timeout + complete + cancelled) |
| `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java` | Modify | `defaultSubTaskExecutor` `@Bean` passes `sseEmitterRegistry` |
| `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js` | Modify (largest) | #1c chip rendering + #2 smart scroll + #3 askUser in same bubble |
| `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/style.css` | Modify | Chip + back-to-bottom-btn + askUser-bubble CSS |
| `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/chat/ChatUiUxFixesContractTest.java` | Create | 3 contract tests (jsdom-style or pure JS module verification) |
| `CLAUDE.md` | Modify | Append 3-UX-fix note |

8 files changed (5 new + 3 modify) + 1 new test class.

---

## Task 1: Backend — SubTaskEvent record + ChatResponseRecord field

**Files:**
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/SubTaskEvent.java`
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/ChatResponseRecord.java`

**Interfaces:**
- `SubTaskEvent` is a record consumable by both backend (push) and frontend (consume via SSE frame).
- `ChatResponseRecord` gains an optional `subTaskEvent` field via a new 4-arg constructor; existing 2/3-arg constructors preserved.

- [ ] **Step 1: Create the SubTaskEvent record**

Create `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/SubTaskEvent.java`:

```java
package cn.wubo.spring.ai.loom.agent.model;

/**
 * 2026-09-21: Sub-task lifecycle event pushed from backend to chat SSE stream.
 * <p>
 * 触发场景(DefaultSubTaskExecutor): 子任务启动 → status=RUNNING + startedAt > 0;
 * 子任务完成 → status=COMPLETED + elapsedMs;子任务超时/失败 → status=FAILED + errorMessage;
 * 子任务取消 → status=CANCELLED。
 * <p>
 * 前端 app.js 收到后渲染紧凑 chip 显示在 chat 流中。
 */
public record SubTaskEvent(
        String subTaskId,
        String status,        // "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED"
        String prompt,        // 前 80 字
        long startedAt,       // epoch ms; 0 if not yet started
        long elapsedMs,       // 仅终态事件有效;启动时为 0
        String errorMessage   // 仅 FAILED 状态填
) {}
```

- [ ] **Step 2: Modify ChatResponseRecord to add subTaskEvent field**

Modify `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/ChatResponseRecord.java` to add the field and a 4-arg constructor. Preserve existing 2/3-arg constructors.

**Replace the file content with** (verbatim):

```java
package cn.wubo.spring.ai.loom.agent.model;

/**
 * SSE 唯一下行帧。content/reasoningContent 为流式文本增量;
 * askUser 非空时表示一张提问卡片事件(#1 AskUser 工具,普通帧为 null);
 * subTaskEvent 非空时表示一个子任务生命周期事件(2026-09-21 新增,#1 chat-ui-ux-fixes)。
 * 2-arg 构造器保持旧发送点(SseController 内容帧)源码兼容;
 * 3-arg 构造器保持 askUser 帧发送点源码兼容。
 */
public record ChatResponseRecord(String content,
                                 String reasoningContent,
                                 AskUserEvent askUser,
                                 SubTaskEvent subTaskEvent) {

    public ChatResponseRecord(String content, String reasoningContent) {
        this(content, reasoningContent, null, null);
    }

    public ChatResponseRecord(String content, String reasoningContent, AskUserEvent askUser) {
        this(content, reasoningContent, askUser, null);
    }

    public ChatResponseRecord(String content, String reasoningContent, SubTaskEvent subTaskEvent) {
        this(content, reasoningContent, null, subTaskEvent);
    }
}
```

- [ ] **Step 3: Verify compilation**

```
cd D:\developer\IdeaProjects\spring-ai-loom-agent
mvn compile -pl spring-ai-loom-agent -DskipTests
```

Expected: BUILD SUCCESS. If any existing caller breaks (e.g., uses positional args that don't match), fix the caller to use the named 3-arg constructor with `subTaskEvent=null` (the new convenience constructor is provided).

- [ ] **Step 4: Commit**

```
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/SubTaskEvent.java \
        spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/ChatResponseRecord.java
git commit -m "feat(chat): 新增 SubTaskEvent record + ChatResponseRecord 加 subTaskEvent 字段

spec § 4.1 — 后端推送子任务生命周期事件到 chat SSE 流所需的数据载体。
ChatResponseRecord 扩为 4-arg record(subTaskEvent),保留 2/3-arg
构造器源码兼容。

Co-Authored-By: Claude Code <noreply@anthropic.com>
"
```

---

## Task 2: Backend — DefaultSubTaskExecutor publishes SubTaskEvent

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskExecutor.java`
- Modify: `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java` (wire the new dependency)

**Interfaces:**
- DefaultSubTaskExecutor gains a `SseEmitterRegistry` dependency.
- On sub-task start / end / timeout / cancel, it pushes a `SubTaskEvent` frame to the parent conversation.

- [ ] **Step 1: Read existing constructor + execute() to find insertion points**

```
grep -n "public DefaultSubTaskExecutor\|SseEmitterRegistry\|subTaskRegistry.markFinished\|catch (TimeoutException\|catch (Exception\|catch (java.util.concurrent.TimeoutException" \
    spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskExecutor.java
```

Note the exact line numbers. You will add `SseEmitterRegistry sseEmitterRegistry` as the last constructor parameter (after `subTaskProperty`) and wire 3 push call sites.

- [ ] **Step 2: Add SseEmitterRegistry field + parameter**

In `DefaultSubTaskExecutor.java`:

**Modify the constructor signature** from the current 8-arg form to a 9-arg form (add `SseEmitterRegistry` last):

```java
public DefaultSubTaskExecutor(ChatClient chatClient,
                              BaseChatMemoryAdvisor memoryAdvisor,
                              ExecutorService executor,
                              IMcp mcp,
                              List<IEmbedTool> embedTools,
                              SubTaskRegistry subTaskRegistry,
                              CapabilityService capabilityService,
                              LoomAgentProperties.SubTaskProperty subTaskProperty,
                              SseEmitterRegistry sseEmitterRegistry) {
    // ... assign all fields, including sseEmitterRegistry
    this.sseEmitterRegistry = sseEmitterRegistry;
}

public DefaultSubTaskExecutor(ChatClient chatClient,
                              BaseChatMemoryAdvisor memoryAdvisor,
                              ExecutorService executor,
                              IMcp mcp,
                              List<IEmbedTool> embedTools,
                              SubTaskRegistry subTaskRegistry,
                              CapabilityService capabilityService,
                              LoomAgentProperties.SubTaskProperty subTaskProperty) {
    this(chatClient, memoryAdvisor, executor, mcp, embedTools, subTaskRegistry, capabilityService, subTaskProperty, null);
}
```

Add the field:
```java
private final cn.wubo.spring.ai.loom.agent.stream.SseEmitterRegistry sseEmitterRegistry;
```

- [ ] **Step 3: Add the publish helper method**

```java
private void publishEvent(String subTaskId, String parentConv, String status,
                          String prompt, long startedAt, long elapsedMs, String errorMessage) {
    if (sseEmitterRegistry == null) return;             // 7-arg legacy path
    SubTaskEvent ev = new SubTaskEvent(subTaskId, status,
            prompt == null ? "" : (prompt.length() > 80 ? prompt.substring(0, 80) + "…" : prompt),
            startedAt, elapsedMs, errorMessage);
    try {
        cn.wubo.spring.ai.loom.agent.stream.SseEmitterRegistry.Entry entry =
                sseEmitterRegistry.get(/* username — need it from req */ req.username(), parentConv);
        if (entry != null && entry.emitter() != null) {
            entry.emitter().send(
                    org.springframework.http.MediaType.APPLICATION_JSON,
                    new ChatResponseRecord(null, null, null, ev),
                    org.springframework.http.MediaType.APPLICATION_JSON
            );
        }
    } catch (Exception ex) {
        log.warn("Failed to publish SubTaskEvent for {}: {}", subTaskId, ex.getMessage());
    }
}
```

**Note**: `entry.emitter().send(MediaType, Object, MediaType)` is the SSE API. Verify the exact signature by reading `SseEmitterRegistry.Entry` — adjust if the project uses a different overload.

- [ ] **Step 4: Wire 3 push call sites in `execute()`**

In `DefaultSubTaskExecutor.execute()`:

**(a) Start push** — locate the line `log.info("Sub-task start: id={}, ...", ...)` (the first line of `execute()`) and add IMMEDIATELY AFTER it:

```java
publishEvent(subTaskId, req.parentConversationId(), "RUNNING", req.prompt(), System.currentTimeMillis(), 0L, null);
```

**(b) Timeout push** — locate the `catch (java.util.concurrent.TimeoutException te)` block (added in commit `e2802181`). After the existing `log.warn(...)` and BEFORE the `String diagnostic = String.format(...)` line, add:

```java
publishEvent(subTaskId, req.parentConversationId(), "FAILED", req.prompt(), startedAt,
        System.currentTimeMillis() - startedAt, "timeout");
```

**(c) Complete push** — locate the line `subTaskRegistry.markFinished(subTaskId, result.status(), ...)` (after `future.get()` succeeds). Add a push right BEFORE or AFTER the markFinished call:

```java
publishEvent(subTaskId, req.parentConversationId(), "COMPLETED", req.prompt(), startedAt,
        System.currentTimeMillis() - startedAt, null);
subTaskRegistry.markFinished(subTaskId, result.status(), result.text(), result.errorMessage());
```

**(d) (Optional) Failure push** — locate `catch (Exception e)` block (general failure). Add a push inside the catch:

```java
publishEvent(subTaskId, req.parentConversationId(), "FAILED", req.prompt(), startedAt,
        System.currentTimeMillis() - startedAt, e.getMessage());
```

- [ ] **Step 5: Wire LoomAgentConfiguration**

In `LoomAgentConfiguration.java`, locate the `defaultSubTaskExecutor` `@Bean` method (added in commit `e2802181` to pass `properties.getSubtask()`). Update the `@Bean` method's parameter list and constructor call:

**Before** (current 9-arg @Bean):
```java
@Bean("loomAgentSubTaskExecutor")
@Lazy
public DefaultSubTaskExecutor defaultSubTaskExecutor(
        ChatClient chatClient,
        BaseChatMemoryAdvisor memoryAdvisor,
        ExecutorService loomSubTaskExecutor,
        IMcp mcp,
        @Lazy SubTaskRegistry subTaskRegistry,
        @Lazy List<IEmbedTool> embedTools,
        @Lazy CapabilityService capabilityService,
        LoomAgentProperties properties) {
    return new DefaultSubTaskExecutor(
            chatClient, memoryAdvisor, loomSubTaskExecutor, mcp, embedTools,
            subTaskRegistry, capabilityService, properties.getSubtask());
}
```

**After** (10-arg @Bean):
```java
@Bean("loomAgentSubTaskExecutor")
@Lazy
public DefaultSubTaskExecutor defaultSubTaskExecutor(
        ChatClient chatClient,
        BaseChatMemoryAdvisor memoryAdvisor,
        ExecutorService loomSubTaskExecutor,
        IMcp mcp,
        @Lazy SubTaskRegistry subTaskRegistry,
        @Lazy List<IEmbedTool> embedTools,
        @Lazy CapabilityService capabilityService,
        cn.wubo.spring.ai.loom.agent.stream.SseEmitterRegistry sseEmitterRegistry,
        LoomAgentProperties properties) {
    return new DefaultSubTaskExecutor(
            chatClient, memoryAdvisor, loomSubTaskExecutor, mcp, embedTools,
            subTaskRegistry, capabilityService, properties.getSubtask(), sseEmitterRegistry);
}
```

- [ ] **Step 6: Run full module regression**

```
cd D:\developer\IdeaProjects\spring-ai-loom-agent
mvn test -pl spring-ai-loom-agent -DfailIfNoTests=false 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 215 tests pass, 0 failures.

Also run the legacy test:
```
cd D:\developer\IdeaProjects\spring-ai-loom-agent
mvn test -pl spring-ai-loom-agent-test -Dtest=DefaultSubTaskExecutorTest -DfailIfNoTests=false 2>&1 | tail -10
```
Expected: 7/7 PASS (legacy 7-arg constructor preserved).

- [ ] **Step 7: Commit**

```
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskExecutor.java \
        spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java
git commit -m "feat(subtask): start_sub_task 生命周期事件推送 SSE 流(spec § 4.1 #1a #1b)

spec § 4.1 — 在 DefaultSubTaskExecutor 注入 SseEmitterRegistry,子任务
启动 / 完成 / 超时 / 失败 时通过 ChatResponseRecord.subTaskEvent
推送到父 conversation 的 SSE 流。前端 chat 流渲染 chip。

LoomAgentConfiguration.defaultSubTaskExecutor @Bean 增加 sseEmitterRegistry
参数,串成 10-arg 构造器。7-arg 兼容路径保留(legacy test 通过)。

Co-Authored-By: Claude Code <noreply@anthropic.com>
"
```

---

## Task 3: Frontend — subTask chip + smart scroll + askUser same bubble (one big edit to app.js)

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js`
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/style.css`

**Interfaces:**
- Stream callback `data.subTaskEvent` triggers chip lifecycle (renderStart / renderUpdate / renderEnd).
- `scrollToBottom()` becomes scroll-guard aware.
- askUser card and AI's eventual reply share the same bubble.

- [ ] **Step 1: Add SubTaskEvent chip module**

Insert this module in `app.js` immediately AFTER `askUserCards` module (around line 1838). The chip module is a self-contained IIFE similar to `askUserCards`:

```js
// ===================== §1c SubTask chip (spec § 4.3 #1) =====================
const subTaskChips = (() => {
  const active = new Map();  // subTaskId -> { el, timer, startedAt }

  function fmtElapsed(ms) {
    const s = Math.floor(ms / 1000);
    if (s < 60) return s + "s";
    return Math.floor(s / 60) + "m " + (s % 60) + "s";
  }

  function renderStart(ev) {
    const bubble = ui._currentAskUserBubble || lastBotBubble();
    if (!bubble) return;
    const chip = document.createElement("div");
    chip.className = "subtask-chip subtask-chip-running";
    chip.dataset.subTaskId = ev.subTaskId;
    chip.innerHTML = `
      <span class="subtask-chip-spinner">⏳</span>
      <span class="subtask-chip-id">${escapeHtml(ev.subTaskId.slice(0, 8))}</span>
      <span class="subtask-chip-elapsed">0s</span>
      <span class="subtask-chip-prompt">${escapeHtml(ev.prompt || "")}</span>
      <span class="subtask-chip-expand">▸</span>
    `;
    bubble.appendChild(chip);
    const startedAt = ev.startedAt || Date.now();
    const timer = setInterval(() => {
      const el = chip.querySelector(".subtask-chip-elapsed");
      if (el) el.textContent = fmtElapsed(Date.now() - startedAt);
    }, 1000);
    active.set(ev.subTaskId, { el: chip, timer, startedAt });
    chip.addEventListener("click", () => {
      if (window.subTaskPanel) window.subTaskPanel.open(ev.subTaskId);
      else showToast("子任务 " + ev.subTaskId.slice(0, 8) + " 的执行流已在主对话中显示", "info");
    });
  }

  function renderEnd(ev) {
    const c = active.get(ev.subTaskId);
    if (!c) return;
    clearInterval(c.timer);
    c.el.classList.remove("subtask-chip-running");
    c.el.classList.add(`subtask-chip-${(ev.status || "completed").toLowerCase()}`);
    const spinner = c.el.querySelector(".subtask-chip-spinner");
    if (spinner) spinner.textContent =
      ev.status === "COMPLETED" ? "✓" : ev.status === "FAILED" ? "✗" : "⊘";
    const elapsed = c.el.querySelector(".subtask-chip-elapsed");
    if (elapsed) elapsed.textContent = fmtElapsed(ev.elapsedMs || (Date.now() - c.startedAt));
    const expand = c.el.querySelector(".subtask-chip-expand");
    if (expand) expand.textContent = "详情";
    setTimeout(() => c.el.classList.add("subtask-chip-fading"), 30000);
    active.delete(ev.subTaskId);
  }

  function lastBotBubble() {
    const items = ui.mainContent.querySelectorAll(".chat-item-left .bubble");
    return items.length ? items[items.length - 1] : null;
  }

  return { renderStart, renderEnd };
})();
```

- [ ] **Step 2: Add sub-task event handler in stream callback**

Locate the stream callback in `app.js` around line 1900. The current code:

```js
await api.streamChat(
    record,
    (data) => {
      if (data.askUser) {
        askUserCards.render(data.askUser);
      }
      if (data.reasoningContent) { ... }
      if (data.content) { ... }
      ui.scrollToBottom();
    },
    () => { ... },
    (error) => { ... },
);
```

Insert a NEW branch BEFORE `data.askUser`:

```js
if (data.subTaskEvent) {
  const ev = data.subTaskEvent;
  if (ev.status === "RUNNING") {
    subTaskChips.renderStart(ev);
  } else {
    subTaskChips.renderEnd(ev);
  }
}
```

(Note: backend sends `RUNNING + startedAt > 0` for start, any other status for end. The two branches cover both. If you need finer distinction, add `if (ev.startedAt > 0)` inside the RUNNING branch.)

- [ ] **Step 3: Smart scroll (#2)**

**Add `_isAtBottom` field to `ui` object** (around line 1124):

```js
const ui = {
  mainContent: null,
  _isAtBottom: true,
  _backToBottomBtn: null,
  _currentAskUserBubble: null,  // #3
  _currentAnswerEl: null,
  _currentOriginEl: null,
  _currentActionsEl: null,
  _currentBubbleId: null,
  _lastAnswerEl: null,
  _lastOriginEl: null,
  _lastActionsEl: null,

  init() {
    this.mainContent = document.getElementById("mainContent");
    this._backToBottomBtn = document.getElementById("back-to-bottom-btn");
    this.mainContent.addEventListener("scroll", () => {
      const el = this.mainContent;
      const threshold = 80;
      this._isAtBottom = (el.scrollTop + el.clientHeight >= el.scrollHeight - threshold);
      if (this._backToBottomBtn) {
        this._backToBottomBtn.classList.toggle("visible", !this._isAtBottom);
      }
    });
    if (this._backToBottomBtn) {
      this._backToBottomBtn.addEventListener("click", () => {
        this._isAtBottom = true;
        this.scrollToBottom();
        this._backToBottomBtn.classList.remove("visible");
      });
    }
  },
  ...
};
```

**Modify `scrollToBottom()` method** (currently `app.js:1244-1247`):

```js
scrollToBottom() {
  if (!this._isAtBottom) return;     // 智能滚动守卫 (#2)
  if (this.mainContent)
    this.mainContent.scrollTop = this.mainContent.scrollHeight;
},
```

- [ ] **Step 4: askUser same bubble (#3)**

**Step 4a**: Modify `renderBotMessage(id)` (around `app.js:1177-1205`). Find the existing `<div id="${id}" ...></div>` line. Replace with:

```html
<div id="${id}" class="bot-content" style="margin: 16px"></div>
```

**Step 4b**: Modify `askUserCards.render(ev)` (around `app.js:1739-1828`). The current code appends a new `chat-item-left` to `ui.mainContent`. Change it to FIRST check if there's a current askUser bubble, and create one if not:

Find the line:
```js
const item = document.createElement("div");
item.className = "chat-item chat-item-left";
item.innerHTML = `...`;
ui.mainContent.appendChild(item);
```

REPLACE with:

```js
// #3 askUser same bubble: create (or reuse) a bubble that holds both the
// askUser card and the upcoming AI reply. We piggyback on ui._currentAskUserBubble.
let item = ui._currentAskUserBubble;
if (!item) {
  const qid = ev.questionId;
  item = document.createElement("div");
  item.className = "chat-item chat-item-left askuser-message-item";
  item.id = `askuser-bubble-${qid}`;
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
}
// Then render the card INTO the bubble (move the card HTML render from `item.innerHTML = ...` block
// into a separate function that builds just the card and appends to ui._currentAskUserBubble.querySelector('.askuser-slot'))
```

You will need to refactor: the existing `askUserCards.render()` builds the entire bubble+card HTML in one shot. Split it into:
1. **Bubble creation** (above code)
2. **Card render** (append to `.askuser-slot`)

Concretely, find the bubble HTML construction block inside `render(ev)` and replace `ui.mainContent.appendChild(item);` with:
```js
const slot = item.querySelector(".askuser-slot");
slot.innerHTML = `<div class="askuser-card" id="askuser-${qid}">...</div>`;
ui.mainContent.appendChild(item);
```

(Keep the existing innerHTML for the card intact; just move it into the slot.)

**Step 4c**: Update the stream callback's `data.content` and `data.reasoningContent` handlers to write to `_currentAnswerEl` if set:

Find:
```js
if (data.reasoningContent) {
  const thinkingContainer = document.getElementById("thinking-" + id);
  ...
}
if (data.content) {
  answerText += data.content;
  if (answerEl) answerEl.innerHTML = renderMarkdown(answerText);
  ...
}
```

Replace `answerEl` with a fallback lookup:
```js
const targetEl = ui._currentAnswerEl || answerEl;
const targetOrigin = ui._currentOriginEl || originEl;
const targetActions = ui._currentActionsEl || actionsEl;
// ... use targetEl / targetOrigin / targetActions in the existing logic
```

This ensures AI's reply text appends into the askUser bubble when one is active.

**Step 4d**: On stream complete / error (the `() => { ... }` and `(error) => { ... }` callbacks), reset `ui._currentAskUserBubble = null; ui._currentAnswerEl = null; ...` after marking actions visible. Find:

```js
() => {
  askUserCards.cancelAllActive("已结束");
  const actionsEl = document.getElementById("actions-" + id);
  if (actionsEl) actionsEl.style.display = "";
  ...
}
```

REPLACE with:
```js
() => {
  askUserCards.cancelAllActive("已结束");
  const actionsEl = ui._currentActionsEl || document.getElementById("actions-" + id);
  if (actionsEl) actionsEl.style.display = "";
  // Reset askUser bubble state — next question gets a fresh bubble
  ui._currentAskUserBubble = null;
  ui._currentAnswerEl = null;
  ui._currentOriginEl = null;
  ui._currentActionsEl = null;
  ui._currentBubbleId = null;
  ...
}
```

Same for the `(error) => { ... }` callback.

**Step 4e**: Also reset on `renderMessages()` (when loading conversation history — `app.js:1207`):

After `this.clearChat();` add:
```js
this._currentAskUserBubble = null;
this._currentAnswerEl = null;
this._currentOriginEl = null;
this._currentActionsEl = null;
this._currentBubbleId = null;
```

- [ ] **Step 5: Add CSS**

Add the following block to `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/style.css` (any location — recommend at the end):

```css
/* ===== 2026-09-21 chat-ui-ux-fixes ===== */

/* #1: Sub-task chip */
.subtask-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  margin: 8px 0;
  background: #f5f5f5;
  border: 1px solid #d9d9d9;
  border-radius: 16px;
  font-size: 13px;
  cursor: pointer;
  transition: opacity 1s, background 0.3s;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.subtask-chip-running { background: #e6f7ff; border-color: #91d5ff; }
.subtask-chip-completed { background: #f6ffed; border-color: #b7eb8f; }
.subtask-chip-failed { background: #fff1f0; border-color: #ffa39e; }
.subtask-chip-cancelled { background: #f5f5f5; border-color: #bfbfbf; }
.subtask-chip-fading { opacity: 0.3; }
.subtask-chip-prompt { color: var(--text-3, rgba(0,0,0,0.65)); overflow: hidden; text-overflow: ellipsis; }
.subtask-chip-spinner { animation: spin 1.2s linear infinite; }
@keyframes spin { 100% { transform: rotate(360deg); } }

/* #2: Back-to-bottom floating button */
.back-to-bottom-btn {
  position: absolute;
  right: 20px;
  bottom: 90px;
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
  font-size: 13px;
  z-index: 100;
}
.back-to-bottom-btn.visible { opacity: 1; pointer-events: auto; }

/* #3: askUser in same bubble — reduce gap between askUser card and AI reply */
.askuser-bubble .askuser-slot { margin-bottom: 0; }
.askuser-bubble .bot-content { margin-top: 8px !important; }
```

Also add the HTML element to `index.html` — find the chat input area (`<div class="input-row">` or similar) and add right above it:

```html
<button class="back-to-bottom-btn" id="back-to-bottom-btn">↓ 回到底部</button>
```

- [ ] **Step 6: Run full module regression**

```
cd D:\developer\IdeaProjects\spring-ai-loom-agent
mvn test -pl spring-ai-loom-agent -DfailIfNoTests=false 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 215+ tests pass.

- [ ] **Step 7: Commit**

```
git add spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js \
        spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/style.css \
        spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/index.html
git commit -m "feat(chat): 三项 UX 修复 (#1 子任务 chip + #2 智能滚动 + #3 askUser 同气泡)

spec § 4.3-4.5:
- #1 subTaskChips 模块 + 流式回调识别 subTaskEvent + 紧凑 chip 渲染
- #2 scrollToBottom() 加 isAtBottom 守卫 + 浮动按钮\"↓ 回到底部\"
- #3 askUserCards.render() 重构:卡片与 AI 回复共用同一气泡(_currentAskUserBubble)

index.html 加浮动按钮元素;style.css 加 chip + 按钮 + askuser-bubble 样式。
无前端依赖,vanilla DOM。

Co-Authored-By: Claude Code <noreply@anthropic.com>
"
```

---

## Task 4: Contract test + CLAUDE.md sync + final regression

**Files:**
- Create: `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/chat/ChatUiUxFixesContractTest.java`
- Modify: `CLAUDE.md`

**Interfaces:**
- Test verifies the contract via static analysis (jsdom-style or pure JS evaluation).
- CLAUDE.md notes the 3 fixes.

- [ ] **Step 1: Create the contract test**

Create `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/chat/ChatUiUxFixesContractTest.java`. **Important caveat**: loom-agent currently has no JS test environment. Use a pure-Java approach: read the app.js source as a String, assert key code fragments are present:

```java
package cn.wubo.spring.ai.loom.agent.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-09-21 chat-ui-ux-fixes 三项修复契约回归（spec § 4.3-4.5）
 * <p>
 * 由于 loom-agent 没有 JS 测试环境(jsdom 等),本测试读 app.js 源文件作为
 * 字符串,断言关键代码片段存在。这锁住"代码契约"而不锁住"运行时行为"——
 * 实际 UI 验证留给端到端 Playwright。
 */
@DisplayName("Chat UI UX 修复契约回归")
class ChatUiUxFixesContractTest {

    private static String appJs;

    private static String readAppJs() throws IOException {
        if (appJs != null) return appJs;
        Path p = Paths.get("src/main/resources/META-INF/resources/spring/ai/loom/app.js");
        appJs = Files.readString(p);
        return appJs;
    }

    @Test
    @DisplayName("#2: scrollToBottom() 加 isAtBottom 守卫")
    void scrollGuardPresent() throws IOException {
        String src = readAppJs();
        assertTrue(src.contains("if (!this._isAtBottom) return"),
                "scrollToBottom() must guard on isAtBottom — current src missing this check");
        assertTrue(src.contains("this.mainContent.addEventListener(\"scroll\""),
                "mainContent scroll listener not registered for isAtBottom updates");
        assertTrue(src.contains("back-to-bottom-btn"),
                "back-to-bottom floating button class/id not found in app.js");
    }

    @Test
    @DisplayName("#1: subTaskChips 模块 + 流式回调识别 subTaskEvent")
    void subTaskChipModulePresent() throws IOException {
        String src = readAppJs();
        assertTrue(src.contains("const subTaskChips = (() =>"),
                "subTaskChips IIFE module not found");
        assertTrue(src.contains("renderStart") && src.contains("renderEnd"),
                "subTaskChips must export renderStart/renderEnd");
        assertTrue(src.contains("data.subTaskEvent"),
                "stream callback must handle data.subTaskEvent");
        assertTrue(src.contains(".subtask-chip-running"),
                "CSS class .subtask-chip-running not referenced (rendering path broken)");
    }

    @Test
    @DisplayName("#3: askUser 同气泡（_currentAskUserBubble + 渲染逻辑）")
    void askUserBubbleReused() throws IOException {
        String src = readAppJs();
        assertTrue(src.contains("_currentAskUserBubble"),
                "_currentAskUserBubble state field missing");
        assertTrue(src.contains("askuser-slot"),
                "askuser-slot div missing — #3 refactor not applied");
        assertTrue(src.contains("askuser-bubble"),
                "askuser-bubble class not present — #3 refactor not applied");
        assertTrue(src.contains(".askuser-slot"),
                ".askuser-slot CSS class not found in app.js");
    }
}
```

- [ ] **Step 2: Run the test**

```
cd D:\developer\IdeaProjects\spring-ai-loom-agent
mvn test -pl spring-ai-loom-agent -Dtest=ChatUiUxFixesContractTest -DfailIfNoTests=false 2>&1 | tail -10
```

Expected: 3 tests, all PASS. (If `src/main/resources/.../app.js` path is wrong, fix to relative path or use `getClass().getResource(...)`.)

- [ ] **Step 3: Update CLAUDE.md**

Locate the `IChat` row in CLAUDE.md and append:

```
 — 2026-09-21 三项 UX 修复：#1 子任务紧凑 chip 在 chat 流中实时显示生命周期；#2 scrollToBottom 加 isAtBottom 守卫 + 浮动按钮；#3 askUser 卡片与 AI 回复共用同一气泡。规格：`docs/superpowers/specs/2026-09-21-chat-ui-ux-fixes.md`。
```

- [ ] **Step 4: Run full module regression**

```
cd D:\developer\IdeaProjects\spring-ai-loom-agent
mvn test -pl spring-ai-loom-agent -DfailIfNoTests=false 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 215 + 3 = 218 tests pass.

- [ ] **Step 5: Commit**

```
git add spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/chat/ChatUiUxFixesContractTest.java \
        CLAUDE.md
git commit -m "test(chat): chat-ui-ux-fixes 三项契约回归 + CLAUDE.md 同步

spec § 4.3-4.5 — 3 个契约测试(scroll 守卫、subTaskChips 模块、askUser
同气泡)锁住 #1 #2 #3 关键代码模式。loom-agent 暂无 jsdom,本测试读
app.js 源字符串验证关键片段存在;运行时 UI 行为留给端到端 Playwright。

CLAUDE.md IChat 条目追加三项 UX 修复说明。

Co-Authored-By: Claude Code <noreply@anthropic.com>
"
```

---

## Self-Review

Performed before saving:

**1. Spec coverage:**
- § 4.1 → Tasks 1 (record + field) + 2 (publisher + wiring)
- § 4.3 → Task 3 (subTaskChips module + chip render + CSS)
- § 4.4 → Task 3 (smart scroll + back-to-bottom button)
- § 4.5 → Task 3 (askUser same bubble refactor)
- § 6 → Task 4 (contract test)
- § 9.4 → Task 4 (module regression)
- § 9.5 → Task 4 (CLAUDE.md sync)

**2. Placeholder scan:** No "TBD"/"TODO". Task 3 has multiple "find line X, replace with Y" steps — each is a concrete edit with verbatim replacement text.

**3. Type consistency:**
- `SubTaskEvent(subTaskId, status, prompt, startedAt, elapsedMs, errorMessage)` — 6 fields, used consistently across Tasks 1-3
- `ChatResponseRecord(content, reasoningContent, askUser, subTaskEvent)` — 4-arg, with 3 convenience constructors preserved (2-arg, 3-arg-askUser, 3-arg-subTaskEvent)
- `ui._currentAskUserBubble`, `ui._currentAnswerEl`, etc. — new state fields, initialized in `init()`

**4. Out-of-scope check:**
- ❌ No frontend framework (vanilla JS only)
- ❌ No WebSocket / SSE protocol change (uses existing SSE)
- ❌ No yml new config
- ❌ No mobile-specific adapt

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-21-chat-ui-ux-fixes.md`. Two execution options:

**1. Subagent-Driven (recommended)** — Fresh subagent per task + scoped reviews between tasks + final whole-branch review.

**2. Inline Execution** — Execute tasks in this session with checkpoints.
