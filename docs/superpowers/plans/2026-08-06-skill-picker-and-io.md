# Skill Picker / IO 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户在聊天输入框中通过 `/` 精准选中某个 Skill（强指令注入），并修正技能下载/导入功能。

**Architecture:** 前端 textarea 上增加 `/` 浮层 + 标签条，选中后 `chat.send()` 在 `ChatRequestRecord` 中附带 `selectedSkillName`；后端 `DefaultChat.stream()` 按用户权限读取 Skill 并将完整 content 追加到 system prompt 末尾。前端技能详情增加「下载」按钮，技能库侧栏增加「导入」按钮。

**Tech Stack:** Spring Boot 3.x + Spring AI 1.x + JDK 17 + 原生 JavaScript（无构建步骤）+ JUnit 5 + Mockito + Maven

## Global Constraints

- JDK 17+，Spring Boot 3.x，Spring AI 1.x
- 不引入新依赖（沿用现有 JUnit 5 / Mockito / 原生 JS）
- 后端 record 字段必须向后兼容（旧调用不传 = null）
- 前端 `app.js` 用纯原生 ES module（`type="module"`），禁止引入打包器
- 文件名/位置：spec 中已明确定义，逐项照搬
- 每次任务结束 `git commit` 一次，使用 `Co-Authored-By: Claude <noreply@anthropic.com>` 后缀
- 所有 toast / 提示文案使用中文，与现有 UI 风格一致
- 测试断言：后端用 JUnit 5 + Mockito；前端用 Node `node --check` + 手动浏览器验证

---

## 文件结构

| 文件 | 类型 | 责任 |
|------|------|------|
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/ChatRequestRecord.java` | 修改 | 记录增加 `selectedSkillName` 字段 |
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChat.java` | 修改 | 注入 Skill content 到 system prompt |
| `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChatTest.java` | 修改 | 新增 Skill 注入测试 |
| `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/index.html` | 修改 | 增加标签条 DOM |
| `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js` | 修改 | slash picker / 标签条 / 修正方法位置 / 修正事件绑定 |
| `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/style.css` | 修改 | 新增样式 |

---

## Task 1: 后端 ChatRequestRecord 新增 selectedSkillName

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/ChatRequestRecord.java`

**Interfaces:**
- Consumes: 无
- Produces: `ChatRequestRecord` 新增 1 个 record 组件

- [ ] **Step 1: 编辑 record**

完整内容：

```java
package cn.wubo.spring.ai.loom.agent.model;

import java.util.List;

/**
 * 聊天请求 record。
 *
 * 新增字段：
 * - selectedSkillName: 用户在前端通过 / 命令精准选中的 Skill 名；
 *   null/空表示无显式选择。DefaultChat.stream() 会按当前用户权限读取该 Skill，
 *   并将完整 content 追加到 system prompt 末尾（仅作用于本轮对话）。
 */
public record ChatRequestRecord(String message,
                                String conversationId,
                                List<String> mcps,
                                List<String> enabledKnowledgeIds,
                                List<String> fileIds,
                                String selectedSkillName) {
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn -pl spring-ai-loom-agent compile -DskipTests
```

预期：`BUILD SUCCESS`。

- [ ] **Step 3: 提交**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/ChatRequestRecord.java
git commit -m "feat(chat): ChatRequestRecord 新增 selectedSkillName 字段

为 / Skill 精准选择功能预留字段：前端在 chat.send() 时附带，
DefaultChat.stream() 按用户权限读取 Skill 并注入到 system prompt 末尾。

向后兼容：旧调用不传 = null，行为与改动前一致。

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 2: DefaultChat 注入 Skill content（红→绿）

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChat.java:60-104`
- Modify: `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChatTest.java`

**Interfaces:**
- Consumes: `ChatRequestRecord.selectedSkillName` (Task 1)
- Consumes: `ISkillStorage.get(name, username)` (已有)
- Produces: `dynamicSystemPrompt` 追加 Skill 块，**不抛错**

- [ ] **Step 1: 写红测试**

在 `DefaultChatTest.java` 文件**末尾**追加以下 3 个 `@Test` 方法（在最后一个 `}` 之前）。注意：现有 `setUp()` 已经把 `ISkillStorage` mock 出来并 `when(skillStorage.list(anyString())).thenReturn(List.of())` —— 我们需要为本次新增的 `get(name, username)` 调用追加 stub。

在 `setUp()` 中找到：

```java
ISkillStorage skillStorage = mock(ISkillStorage.class);
when(skillStorage.list(anyString())).thenReturn(List.of());
```

在其后追加：

```java
when(skillStorage.get(anyString(), anyString())).thenAnswer(inv -> {
    String name = inv.getArgument(0);
    return new cn.wubo.spring.ai.loom.agent.model.SkillRecord(
            name, "test-desc", true, "skill-body-" + name, "USER_CREATED");
});
```

然后在文件**末尾**（在最后一个 `}` 前）追加：

```java
// ============= selectedSkillName 注入 =============

private String captureSystemPrompt(ChatRequestRecord record) {
    chat.stream(record, "alice", request);
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(requestSpec, atLeastOnce()).system(captor.capture());
    return captor.getValue();
}

@Test
@DisplayName("selectedSkillName=null：不追加 Skill 块（兼容旧调用）")
void selectedSkillNameNullKeepsBasePrompt() {
    ChatRequestRecord record = new ChatRequestRecord("hi", "conv-1", null, null, null, null);

    String prompt = captureSystemPrompt(record);

    assertFalse(prompt.contains("【本轮用户选择的 Skill】"), "null 时不注入 Skill 块");
}

@Test
@DisplayName("selectedSkillName 合法：Skill 完整 content 追加到 system prompt 末尾")
void selectedSkillNameValidAppendsSkillBlock() {
    ChatRequestRecord record = new ChatRequestRecord("hi", "conv-1", null, null, null, "note-health");

    String prompt = captureSystemPrompt(record);

    assertTrue(prompt.contains("【本轮用户选择的 Skill】"), "应注入 Skill 块标题");
    assertTrue(prompt.contains("name: note-health"), "应包含 Skill 名");
    assertTrue(prompt.contains("skill-body-note-health"), "应包含 Skill 完整 content");
    // 顺序：基础 system 提示在前，Skill 块在后
    int baseIdx = prompt.indexOf("base-system");
    int skillIdx = prompt.indexOf("【本轮用户选择的 Skill】");
    assertTrue(baseIdx >= 0 && skillIdx > baseIdx, "Skill 块应追加在基础提示之后");
}

@Test
@DisplayName("selectedSkillName 非法（skillStorage.get 抛异常）：仅 warn，不抛错，prompt 不变")
void selectedSkillNameInvalidFailsSoft() {
    ISkillStorage bad = mock(ISkillStorage.class);
    when(bad.list(anyString())).thenReturn(List.of());
    when(bad.get(anyString(), anyString())).thenThrow(
            new cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException("Skill 不存在或无权限: x"));
    ChatClient cc = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
    ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
    when(cc.prompt()).thenReturn(spec);
    when(spec.system(anyString())).thenReturn(spec);
    when(spec.user(anyString())).thenReturn(spec);
    when(spec.tools(any())).thenReturn(spec);
    when(spec.toolContext(any())).thenReturn(spec);
    when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
    when(spec.stream()).thenReturn(streamSpec);
    when(streamSpec.chatResponse()).thenReturn(reactor.core.publisher.Flux.<ChatResponse>empty());
    DefaultChat c2 = new DefaultChat(cc, mcp(), List.of(), userConversation, file(), bad, knowledge(), properties());

    ChatRequestRecord record = new ChatRequestRecord("hi", "conv-1", null, null, null, "missing-skill");

    String prompt;
    try {
        prompt = captureSystemPromptWith(c2, spec, record);
    } catch (Exception ex) {
        // 不应抛错
        throw new AssertionError("非法 selectedSkillName 不应抛错，实际：" + ex);
    }
    assertNotNull(prompt);
    assertFalse(prompt.contains("【本轮用户选择的 Skill】"), "非法时不应注入 Skill 块");
}
```

注意：上面第二个 `@Test` 已经够清晰；第三个 `@Test` 用了一些未在 setUp() 中提供 getter 的辅助方法（`mcp()`、`file()` 等）。最稳妥的写法是**只测前两种**（null 与合法），把非法测试在 Step 1 末尾用 `// 跳过：非法场景留待 Task 3 验证` 注释掉，本任务专注于"成功路径"。

**简化版 Step 1** —— 删除第三个 `@Test`，文件末尾只追加前两个 + captureSystemPrompt 辅助方法：

```java
// ============= selectedSkillName 注入 =============

private String captureSystemPrompt(ChatRequestRecord record) {
    chat.stream(record, "alice", request);
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(requestSpec, atLeastOnce()).system(captor.capture());
    return captor.getValue();
}

@Test
@DisplayName("selectedSkillName=null：不追加 Skill 块（兼容旧调用）")
void selectedSkillNameNullKeepsBasePrompt() {
    ChatRequestRecord record = new ChatRequestRecord("hi", "conv-1", null, null, null, null);

    String prompt = captureSystemPrompt(record);

    assertFalse(prompt.contains("【本轮用户选择的 Skill】"), "null 时不注入 Skill 块");
}

@Test
@DisplayName("selectedSkillName 合法：Skill 完整 content 追加到 system prompt 末尾")
void selectedSkillNameValidAppendsSkillBlock() {
    ChatRequestRecord record = new ChatRequestRecord("hi", "conv-1", null, null, null, "note-health");

    String prompt = captureSystemPrompt(record);

    assertTrue(prompt.contains("【本轮用户选择的 Skill】"), "应注入 Skill 块标题");
    assertTrue(prompt.contains("name: note-health"), "应包含 Skill 名");
    assertTrue(prompt.contains("skill-body-note-health"), "应包含 Skill 完整 content");
    int baseIdx = prompt.indexOf("base-system");
    int skillIdx = prompt.indexOf("【本轮用户选择的 Skill】");
    assertTrue(baseIdx >= 0 && skillIdx > baseIdx, "Skill 块应追加在基础提示之后");
}
```

- [ ] **Step 2: 运行测试，确认红**

```bash
mvn -pl spring-ai-loom-agent test -Dtest=DefaultChatTest -q
```

预期：`selectedSkillNameNullKeepsBasePrompt` 与 `selectedSkillNameValidAppendsSkillBlock` **失败**（因为 ChatRequestRecord 构造器现在需要 6 个参数，但测试用的现有 record 还在用 5 参构造器 —— 现有 4 个测试会**全部**编译失败）。

- [ ] **Step 3: 修正现有 4 个测试的 record 构造调用**

在 `DefaultChatTest.java` 中，找到所有 5 参的 `new ChatRequestRecord(...)` 调用，追加 `null` 作为第 6 参：

- 第 86 行：`new ChatRequestRecord("hi", "conv-1", null, null, null)` → `new ChatRequestRecord("hi", "conv-1", null, null, null, null)`
- 第 97 行：`new ChatRequestRecord("hi", "conv-1", null, List.of("kb-1", "kb-2"), null)` → `new ChatRequestRecord("hi", "conv-1", null, List.of("kb-1", "kb-2"), null, null)`
- 第 107 行：`new ChatRequestRecord("hi", "conv-9", null, null, null)` → `new ChatRequestRecord("hi", "conv-9", null, null, null, null)`
- 第 120 行：`new ChatRequestRecord("hi", null, null, null, null)` → `new ChatRequestRecord("hi", null, null, null, null, null)`
- 第 136 行：`new ChatRequestRecord("hi", "conv-1", null, null, null)` → `new ChatRequestRecord("hi", "conv-1", null, null, null, null)`
- 第 160 行：`new ChatRequestRecord("hi", "conv-err", null, null, null)` → `new ChatRequestRecord("hi", "conv-err", null, null, null, null)`

- [ ] **Step 4: 再次运行测试**

```bash
mvn -pl spring-ai-loom-agent test -Dtest=DefaultChatTest -q
```

预期：

- 4 个旧测试：PASS
- `selectedSkillNameNullKeepsBasePrompt`：PASS（因为 selectedSkillName=null 时确实不注入）
- `selectedSkillNameValidAppendsSkillBlock`：**FAIL**（因为还没实现注入逻辑）

- [ ] **Step 5: 在 setUp() 中添加 skillStorage.get 的 stub**

找到 setUp() 里的：

```java
ISkillStorage skillStorage = mock(ISkillStorage.class);
when(skillStorage.list(anyString())).thenReturn(List.of());
```

在下一行添加：

```java
when(skillStorage.get(anyString(), anyString())).thenAnswer(inv -> {
    String name = inv.getArgument(0);
    return new cn.wubo.spring.ai.loom.agent.model.SkillRecord(
            name, "test-desc", true, "skill-body-" + name, "USER_CREATED");
});
```

- [ ] **Step 6: 在 DefaultChat.stream() 中实现注入**

打开 `DefaultChat.java`。找到 line 77：

```java
String dynamicSystemPrompt = buildDynamicSystemPrompt(username, chatRequestRecord.enabledKnowledgeIds());
```

在**它的下一行**插入：

```java
// 注入用户通过 / 命令精准选中的 Skill（仅作用于本轮聊天）。
// 解析失败仅 log.warn，聊天继续进行。
if (chatRequestRecord.selectedSkillName() != null
        && !chatRequestRecord.selectedSkillName().isBlank()) {
    try {
        SkillRecord selected = skillStorage.get(chatRequestRecord.selectedSkillName().trim(), username);
        if (selected != null && selected.content() != null) {
            StringBuilder extra = new StringBuilder();
            extra.append("\n\n【本轮用户选择的 Skill】\n");
            extra.append("name: ").append(selected.name()).append("\n");
            if (selected.description() != null) {
                extra.append("description: ").append(selected.description()).append("\n");
            }
            extra.append("\n").append(selected.content());
            dynamicSystemPrompt = dynamicSystemPrompt + extra;
        }
    } catch (LoomAgentRuntimeException ex) {
        log.warn("selectedSkillName 解析失败: name={}, user={}, msg={}",
                chatRequestRecord.selectedSkillName(), username, ex.getMessage());
    }
}
```

确保 import 包含 `SkillRecord`（检查 line 9，已经有了）和 `LoomAgentRuntimeException`。如果没有，补：

```java
import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.SkillRecord;
```

- [ ] **Step 7: 跑测试，确认全绿**

```bash
mvn -pl spring-ai-loom-agent test -Dtest=DefaultChatTest -q
```

预期：6 个测试全 PASS。

- [ ] **Step 8: 提交**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChat.java \
        spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChatTest.java
git commit -m "feat(chat): 注入 selectedSkillName Skill 全文到 system prompt

- ChatRequestRecord 现有 5 参构造改为 6 参 + null（向后兼容）
- selectedSkillName 为 null/空：不追加
- 合法：把 Skill 完整 content 拼到 dynamicSystemPrompt 末尾
- 非法（skillStorage.get 抛异常）：log.warn 后继续，聊天不中断

测试：DefaultChatTest 新增 2 用例（null/合法），旧 4 个测试补 null 参，6/6 绿。

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 3: 前端 state 增加 selectedSkill 字段

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js:60-100`

**Interfaces:**
- Consumes: 无
- Produces: `state.selectedSkill` 全局可读可写

- [ ] **Step 1: 在 state 对象中找到合适位置**

打开 `app.js`，搜索 `state = {`，找到 `state` 对象声明。在该对象中（建议放在 `enabledKnowledgeIds: []` 之后）新增一行：

```js
    selectedSkill: null, // {name, description} | null，用户通过 / 命令精准选中的 Skill
```

- [ ] **Step 2: 语法检查**

```bash
node --check spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js && echo JS_OK
```

预期：`JS_OK`。

- [ ] **Step 3: 提交**

```bash
git add spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js
git commit -m "feat(ui): state 新增 selectedSkill 字段

为 / Skill 精准选择功能准备状态：null 表示无显式选择，
{name, description} 表示已选中（待 chat.send() 时随请求发出）。

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 4: 前端增加标签条 DOM

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/index.html:135-152`

**Interfaces:**
- Consumes: `state.selectedSkill` (Task 3)
- Produces: `<div id="chat-selected-skill" class="chat-selected-skill hidden">` 容器

- [ ] **Step 1: 在 textarea 上方插入标签条**

打开 `index.html`，找到：

```html
                <textarea id="textarea" placeholder="输入消息... (Ctrl+Enter 换行，Enter 发送)"></textarea>
```

在**它的上一行**插入：

```html
                <div id="chat-selected-skill" class="chat-selected-skill hidden">
                    <span class="skill-tag" id="chat-selected-skill-name"></span>
                    <button class="skill-tag-close" id="chat-selected-skill-clear" title="移除">&times;</button>
                </div>
```

- [ ] **Step 2: 验证 HTML 完整性**

用浏览器打开 `index.html`（或在 chat 页加载），确保页面没有报错，textarea 仍可输入。

- [ ] **Step 3: 提交**

```bash
git add spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/index.html
git commit -m "feat(ui): textarea 上方增加 Skill 标签条容器

初始 hidden。state.selectedSkill 非空时由 JS 填充文本并显示。
× 按钮、Backspace、切换对话、发送成功都会触发清空。

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 5: 前端标签条 CSS 样式

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/style.css`

**Interfaces:**
- Consumes: `.chat-selected-skill`、`.skill-tag`、`.skill-tag-close`、`.hidden`（最后一个全局已有）

- [ ] **Step 1: 在 style.css 末尾追加样式**

定位：在文件末尾（`}` 之前）。直接使用 Edit 工具，找到文件最后一行（`}`），在其前追加：

```css
/* ===== Chat Selected Skill Tag ===== */
.chat-selected-skill {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 6px 10px;
    background: var(--bg-secondary, #f1f5f9);
    border: 1px solid var(--primary-color, #6366f1);
    border-radius: var(--radius-sm, 6px);
    margin-bottom: 8px;
    font-size: 13px;
    color: var(--text-primary, #1e293b);
}

.chat-selected-skill.hidden {
    display: none;
}

.chat-selected-skill .skill-tag {
    font-weight: 500;
    color: var(--primary-color, #6366f1);
}

.chat-selected-skill .skill-tag-close {
    background: transparent;
    border: none;
    color: var(--text-muted, #64748b);
    cursor: pointer;
    font-size: 18px;
    line-height: 1;
    padding: 0 4px;
    border-radius: 4px;
    transition: all var(--transition-fast, 0.15s ease);
}

.chat-selected-skill .skill-tag-close:hover {
    background: var(--bg-primary, #fff);
    color: var(--error-color, #ef4444);
}
```

- [ ] **Step 2: 浏览器视觉确认**

打开 index.html，开发者工具切到 Elements 面板，确认 `.chat-selected-skill` 默认 `display: none`。

- [ ] **Step 3: 提交**

```bash
git add spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/style.css
git commit -m "style(ui): Skill 标签条视觉

- .chat-selected-skill：flex 容器，淡紫底 + primary 边框
- .skill-tag：primary 色文字显示 Skill 名
- .skill-tag-close：× 按钮，hover 变红

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 6: 前端标签条 JS 行为（× / Backspace / 切换对话）

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js`

**Interfaces:**
- Consumes: `state.selectedSkill` (Task 3)、`<div id="chat-selected-skill">` (Task 4)
- Produces: `setSelectedSkill(skill)`、`clearSelectedSkill()`、`updateSelectedSkillTag()` 函数

- [ ] **Step 1: 在 `const bindAllEvents` 之前新增 Skill 标签条管理模块**

定位：搜索 `const bindAllEvents = () => {`。在它**前一行**（即 `const bindAllEvents` 上面）新增：

```js
// ===================== §X Chat Selected Skill Tag =====================
// 管理输入框上方的 [name ×] 标签条。state.selectedSkill = {name, description} 时显示。
const selectedSkillTag = {
    set(skill) {
        state.selectedSkill = skill;
        this.update();
    },
    clear() {
        state.selectedSkill = null;
        this.update();
    },
    update() {
        const el = document.getElementById('chat-selected-skill');
        if (!el) return;
        const nameEl = document.getElementById('chat-selected-skill-name');
        if (state.selectedSkill) {
            if (nameEl) nameEl.textContent = state.selectedSkill.name;
            el.classList.remove('hidden');
        } else {
            el.classList.add('hidden');
        }
    },
    /** 在 chat.send() 成功回调中调用 */
    onSendSuccess() { this.clear(); },
    /** 切换对话时调用，避免跨会话污染 */
    onConversationSwitch() { this.clear(); },
};
```

- [ ] **Step 2: 绑定 × 按钮**

定位：`const bindAllEvents = () => {`。在 `safeBindById('send-btn', 'click', () => chat.send());` 这一行**之前**或**之后**插入：

```js
    safeBindById('chat-selected-skill-clear', 'click', () => selectedSkillTag.clear());
```

- [ ] **Step 3: Backspace 在 textarea 为空时清空**

定位：现有 textarea keydown 监听（`safeBind(ta, 'keydown', ...)`）。在它的内部、第一个 `if` 之前增加：

```js
        if (event.key === 'Backspace' && ta.value === '' && state.selectedSkill) {
            event.preventDefault();
            selectedSkillTag.clear();
        }
```

完整 keydown 监听块应变为（注意 `}` 闭合）：

```js
    safeBind(ta, 'keydown', (event) => {
        if (event.key === 'Backspace' && ta.value === '' && state.selectedSkill) {
            event.preventDefault();
            selectedSkillTag.clear();
        }
        if (event.key === 'Enter' && event.ctrlKey) {
            event.preventDefault();
            const start = ta.selectionStart;
            const end = ta.selectionEnd;
            ta.value = ta.value.substring(0, start) + '\n' + ta.value.substring(end);
            ta.setSelectionRange(start + 1, start + 1);
        }
        if (event.key === 'Enter' && !event.shiftKey && !event.ctrlKey) {
            event.preventDefault();
            chat.send();
        }
    });
```

- [ ] **Step 4: 切换对话时清空**

定位：搜索 `switchTo` 函数。在函数体**最开头**插入：

```js
        selectedSkillTag.onConversationSwitch();
```

（紧跟 `state.conversationId = id;` 之后也行，放在最前面更稳。）

- [ ] **Step 5: 语法检查**

```bash
node --check spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js && echo JS_OK
```

预期：`JS_OK`。

- [ ] **Step 6: 提交**

```bash
git add spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js
git commit -m "feat(ui): 标签条管理 + × / Backspace / 切换对话触发清空

- selectedSkillTag.set/clear/update：单一职责封装
- × 按钮 → clear
- Backspace + textarea 为空 → clear
- conversation.switchTo → clear（防跨会话污染）
- chat.send() 成功回调接入放到 Task 8

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 7: 前端 slash picker 浮层

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/index.html`
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/style.css`
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js`

**Interfaces:**
- Consumes: `state.selectedSkill` (Task 3)、`ISkillStorage.list(username)` (走 `api.listSkills()`)
- Produces: `<div id="slash-picker" class="slash-picker hidden">` 浮层；`state.slashPicker = {open, query, items, activeIndex}`

- [ ] **Step 1: HTML 容器（紧跟 textarea 后面）**

定位：在 `index.html` 找到 `<textarea id="textarea" ...>` 行。在它**之后**插入：

```html
                <div id="slash-picker" class="slash-picker hidden"></div>
```

- [ ] **Step 2: CSS**

在 `style.css` 末尾追加（紧跟 Task 5 的样式）：

```css
/* ===== Slash Picker ===== */
.slash-picker {
    position: absolute;
    bottom: 100%;
    left: 0;
    right: 0;
    max-height: 240px;
    overflow-y: auto;
    background: var(--bg-primary, #fff);
    border: 1px solid var(--border-color, #e2e8f0);
    border-radius: var(--radius-md, 8px);
    box-shadow: var(--shadow-lg, 0 10px 25px rgba(0, 0, 0, 0.1));
    z-index: 1000;
    margin-bottom: 4px;
}

.slash-picker.hidden {
    display: none;
}

.slash-picker-item {
    padding: 8px 12px;
    cursor: pointer;
    display: flex;
    flex-direction: column;
    gap: 2px;
    border-bottom: 1px solid var(--border-color, #e2e8f0);
}

.slash-picker-item:last-child {
    border-bottom: none;
}

.slash-picker-item.active {
    background: var(--primary-color, #6366f1);
    color: #fff;
}

.slash-picker-item.active .slash-picker-item-desc {
    color: rgba(255, 255, 255, 0.85);
}

.slash-picker-item-name {
    font-size: 13px;
    font-weight: 500;
}

.slash-picker-item-desc {
    font-size: 11px;
    color: var(--text-muted, #64748b);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
```

- [ ] **Step 3: JS — slash picker 模块**

定位：在 Task 6 插入的 `selectedSkillTag` 模块**之后**追加：

```js
// ===================== §Y Slash Picker =====================
// 输入框输入 '/' 触发；浮层显示用户可访问的所有 Skill，键盘上下/Enter/Esc 操作。
const slashPicker = {
    state: { open: false, query: '', items: [], activeIndex: 0 },

    async open(query) {
        if (!this._el) this._el = document.getElementById('slash-picker');
        if (!this._el) return;
        // 一次拉取全部 Skill（含 load=false）。admin 特权由后端处理。
        let all = state._skillListCache;
        if (!all) {
            try {
                all = await api.listSkills();
                state._skillListCache = all || [];
            } catch (_) { all = []; }
        }
        this.state.query = query || '';
        this.state.items = this._filter(all, this.state.query);
        this.state.activeIndex = 0;
        this.state.open = true;
        this._render();
    },

    close() {
        this.state.open = false;
        if (this._el) this._el.classList.add('hidden');
    },

    setQuery(q) {
        this.state.query = q;
        const all = state._skillListCache || [];
        this.state.items = this._filter(all, q);
        this.state.activeIndex = 0;
        this._render();
    },

    move(delta) {
        if (!this.state.open) return;
        const n = this.state.items.length;
        if (n === 0) return;
        this.state.activeIndex = (this.state.activeIndex + delta + n) % n;
        this._render();
    },

    confirm() {
        if (!this.state.open) return null;
        const item = this.state.items[this.state.activeIndex];
        this.close();
        if (!item) return null;
        // 从 textarea 中去掉 '/query' 文本
        const ta = document.getElementById('textarea');
        if (ta) {
            const v = ta.value;
            const idx = v.lastIndexOf('/');
            if (idx >= 0) ta.value = v.substring(0, idx);
        }
        return item;
    },

    _filter(list, query) {
        const q = (query || '').toLowerCase();
        return list
            .filter(s => !q || s.name.toLowerCase().includes(q) || (s.description || '').toLowerCase().includes(q))
            .slice(0, 50);
    },

    _render() {
        if (!this._el) return;
        if (!this.state.open) { this._el.classList.add('hidden'); return; }
        const items = this.state.items;
        if (items.length === 0) {
            this._el.innerHTML = '<div class="slash-picker-item"><span class="slash-picker-item-name">无匹配 Skill</span></div>';
            this._el.classList.remove('hidden');
            return;
        }
        this._el.innerHTML = items.map((s, i) => `
            <div class="slash-picker-item ${i === this.state.activeIndex ? 'active' : ''}" data-idx="${i}">
                <span class="slash-picker-item-name">${escapeHtml(s.name)}</span>
                <span class="slash-picker-item-desc">${escapeHtml(s.description || '')}</span>
            </div>
        `).join('');
        this._el.classList.remove('hidden');
        // 鼠标 hover 也可切换 active
        this._el.querySelectorAll('.slash-picker-item').forEach((el, i) => {
            el.addEventListener('mouseenter', () => { this.state.activeIndex = i; this._render(); });
            el.addEventListener('click', () => {
                const item = this.confirm();
                if (item) selectedSkillTag.set({ name: item.name, description: item.description });
            });
        });
    },
};
```

- [ ] **Step 4: 在 textarea keydown 中接入 `/` 触发**

定位：Task 6 修改后的 textarea keydown 监听。把 Backspace 分支也加进来（完整版）：

```js
    safeBind(ta, 'keydown', (event) => {
        // Backspace + textarea 为空 + 有 selectedSkill → 清空
        if (event.key === 'Backspace' && ta.value === '' && state.selectedSkill) {
            event.preventDefault();
            selectedSkillTag.clear();
            return;
        }
        // slash picker 打开：键盘控制
        if (slashPicker.state.open) {
            if (event.key === 'ArrowDown') { event.preventDefault(); slashPicker.move(1); return; }
            if (event.key === 'ArrowUp')   { event.preventDefault(); slashPicker.move(-1); return; }
            if (event.key === 'Enter')     { event.preventDefault(); const item = slashPicker.confirm(); if (item) selectedSkillTag.set({ name: item.name, description: item.description }); return; }
            if (event.key === 'Tab')       { event.preventDefault(); const item = slashPicker.confirm(); if (item) selectedSkillTag.set({ name: item.name, description: item.description }); return; }
            if (event.key === 'Escape')    { event.preventDefault(); slashPicker.close(); return; }
        }
        // '/' 触发 picker：必须是刚输入 / 且光标前是 /
        if (event.key === '/' && !slashPicker.state.open) {
            const v = ta.value;
            const start = ta.selectionStart;
            // 允许开头 / 或空白后 /
            const prev = start > 0 ? v[start - 1] : '';
            if (start === 0 || /\s/.test(prev)) {
                event.preventDefault();
                // 插入 / 让用户看到，然后开 picker
                ta.value = v.substring(0, start) + '/' + v.substring(start);
                ta.setSelectionRange(start + 1, start + 1);
                slashPicker.open('');
            }
        }
        if (event.key === 'Enter' && event.ctrlKey) {
            event.preventDefault();
            const start = ta.selectionStart;
            const end = ta.selectionEnd;
            ta.value = ta.value.substring(0, start) + '\n' + ta.value.substring(end);
            ta.setSelectionRange(start + 1, start + 1);
        }
        if (event.key === 'Enter' && !event.shiftKey && !event.ctrlKey) {
            event.preventDefault();
            chat.send();
        }
    });
```

注意：这段用 `if/return` 提前 return，避免重复进入下面 Enter/Ctrl+Enter 逻辑。

- [ ] **Step 5: textarea input 事件 → 过滤 picker**

定位：在 `bindAllEvents` 中，紧跟 keydown 监听后插入：

```js
    safeBind(ta, 'input', () => {
        if (!slashPicker.state.open) return;
        const v = ta.value;
        const idx = v.lastIndexOf('/');
        if (idx < 0) { slashPicker.close(); return; }
        // / 之后不能有空格（避免被 Enter 触发发送）
        const after = v.substring(idx + 1);
        if (/\s/.test(after)) { slashPicker.close(); return; }
        slashPicker.setQuery(after);
    });
```

- [ ] **Step 6: 语法检查**

```bash
node --check spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js && echo JS_OK
```

预期：`JS_OK`。

- [ ] **Step 7: 浏览器手测**

启动服务：

```bash
mvn -pl spring-ai-loom-agent-test spring-boot:run
```

浏览器打开 chat 页面：

- 在 textarea 输入 `/` → 浮层应出现，列出可访问的 Skill
- 输入 `no` → 浮层过滤
- `↓` / `↑` → 切换
- `Enter` → 选中，标签条出现，textarea 中 `/no` 被清除
- `Esc` → 浮层关闭，textarea 中 `/query` 保留

- [ ] **Step 8: 提交**

```bash
git add spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js \
        spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/index.html \
        spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/style.css
git commit -m "feat(ui): / 触发 Skill 精准选择浮层

- 浮层显示当前用户可访问的全部 Skill（不限 load）
- 键盘：↑↓ 切换 / Enter Tab 选中 / Esc 关闭
- 选中后写入 state.selectedSkill + 标签条；textarea 中 '/query' 自动清除
- 空格或失去 '/' 即关闭
- 复用 api.listSkills()，state._skillListCache 缓存避免重复请求

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 8: chat.send() 携带 selectedSkillName + 成功清空 / 失败保留

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js:1370-1473`

**Interfaces:**
- Consumes: `state.selectedSkill` (Task 3)、`api.streamChat(record, ...)` (已有)
- Produces: 聊天请求体附加 `selectedSkillName`；成功回调调 `selectedSkillTag.onSendSuccess()`；失败保留

- [ ] **Step 1: 修改 record 对象**

定位：`app.js:1405`，找到：

```js
        const record = {
            message: text,
            conversationId: state.conversationId,
            mcps: state.selectedMcps,
            enabledKnowledgeIds: state.enabledKnowledgeIds.length > 0 ? state.enabledKnowledgeIds : null,
            fileIds: state.pendingImages.length > 0 ? state.pendingImages.map(img => img.fileId).filter(Boolean) : null,
        };
```

替换为：

```js
        const record = {
            message: text,
            conversationId: state.conversationId,
            mcps: state.selectedMcps,
            enabledKnowledgeIds: state.enabledKnowledgeIds.length > 0 ? state.enabledKnowledgeIds : null,
            fileIds: state.pendingImages.length > 0 ? state.pendingImages.map(img => img.fileId).filter(Boolean) : null,
            selectedSkillName: state.selectedSkill ? state.selectedSkill.name : null,
        };
```

- [ ] **Step 2: 成功回调中清空标签条**

定位：现有 `chat.send()` 的成功 complete 回调（在 `api.streamChat(record, ..., () => { ... }, ...)` 第 3 个参数）。在 `conversation.loadList();` 之后追加：

```js
                    selectedSkillTag.onSendSuccess();
```

完整块变为：

```js
                () => {
                    // complete
                    const actionsEl = document.getElementById('actions-' + id);
                    if (actionsEl) actionsEl.style.display = '';
                    ui.enableSend();
                    ui.setStopButtonVisible(false);
                    conversation.loadList();
                    selectedSkillTag.onSendSuccess();
                    // Auto-rename from the first user message if the conversation still
                    // carries its default placeholder title. Fire-and-forget; the title
                    // update will refresh the sidebar once the PATCH lands.
                    conversation.maybeAutoRename(state.conversationId, text);
                },
```

- [ ] **Step 3: 失败回调保留标签条**

失败回调（`api.streamChat` 第 4 个参数）**不需要**改：现在没有 `selectedSkillTag.clear()`，自然保留。确认该回调函数体中不调用 `selectedSkillTag.clear()` 即可。

- [ ] **Step 4: 语法检查**

```bash
node --check spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js && echo JS_OK
```

预期：`JS_OK`。

- [ ] **Step 5: 浏览器手测**

- 选中 Skill（如 `/note-health`）
- 输入问题 → Enter 发送
- 后端日志中应见 `selectedSkillName=note-health` 相关 warn（如果有异常）或正常注入
- 浏览器开发者工具 Network 中 `stream` 请求 body 应含 `"selectedSkillName":"note-health"`
- 发送成功后，标签条应消失
- 模拟发送失败（断网或临时关闭后端）→ 标签条保留

- [ ] **Step 6: 提交**

```bash
git add spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js
git commit -m "feat(chat): chat.send() 附带 selectedSkillName + 成功清空标签条

- record 新增 selectedSkillName: state.selectedSkill?.name || null
- 成功 complete 回调：selectedSkillTag.onSendSuccess()
- 失败回调不动，标签条自然保留方便重试

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 9: 修正 skill 下载/导入方法位置（搬到 skills 对象内）

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js:3978-4127`

**Interfaces:**
- Consumes: `api.upsertSkill`、`api.getSkill` (已有)
- Produces: `skills.handleDownload`、`skills.handleImport`、`skills._parseSkillFrontmatter`、`skills._doImportFile`、`skills._submitImport`、`skills._showImportConflictDialog`、`skills._findFreeName` 都属于 `const skills = { ... }` 对象

- [ ] **Step 1: 删除错误位置的代码块**

定位：`app.js:3978` 到 `app.js:4125`（即 `handleDownload(skill) { ... }` 到 `_findFreeName(base) { ... }` 整段），这些方法**错误地**写在了 `bindAllEvents()` 函数体内。

找到这段起点（`// 把 Skill 导出为标准 .skill.md（YAML frontmatter + body）`）和终点（`_findFreeName(base) { ... }` 的最后 `}`，再下一行是 `addIf('.ks-create-btn', ...)`）。

**整段删除**。

- [ ] **Step 2: 修正事件绑定**

定位：在删除位置之后的 `bindAllEvents` 末尾，找到：

```js
    addIf('#skill-import-btn', () => skills.handleImport());
    addIf('#skill-import-file-input', 'change', (e) => {
        const f = e.target.files && e.target.files[0];
        if (f) skills._doImportFile(f);
        e.target.value = '';
    });
```

`addIf` 只接受 `(sel, handler)`，第二个签名错。**替换为**：

```js
    addIf('#skill-import-btn', () => skills.handleImport());
    safeBindById('skill-import-file-input', 'change', (e) => {
        const f = e.target.files && e.target.files[0];
        if (f) skills._doImportFile(f);
        e.target.value = '';
    });
```

- [ ] **Step 3: 把方法加到 skills 对象内**

定位：搜索 `const skills = {` 起始处，找到 `showCreateForm() {` 之后、`handleCreate() {` 之前的位置。或者直接加到 `handleDelete(skill)` 之后、`showEditForm(skill)` 之前。

在该位置插入：

```js
    /** 把 Skill 导出为标准 .skill.md（YAML frontmatter + body） */
    handleDownload(skill) {
        const name = (skill.name || '').replace(/[\r\n]+/g, ' ').trim();
        const description = (skill.description || '').replace(/[\r\n]+/g, ' ').trim();
        const body = (skill.content || '').replace(/\r\n/g, '\n');
        const md =
            '---\n' +
            `name: ${name}\n` +
            `description: ${description}\n` +
            '---\n' +
            '\n' + body + '\n';
        const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `${name || 'untitled'}.skill.md`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        if (skill.source === 'ROLE_GRANTED' || skill.source === 'MARKET_PULLED') {
            showToast('已下载；如需导入为可编辑 Skill，请使用新的名称', 'success');
        } else {
            showToast('已下载 ' + a.download, 'success');
        }
    },

    /** 解析 .skill.md 的 YAML frontmatter + body。返回 {name, description, content} 或 {error} */
    _parseSkillFrontmatter(text) {
        const m = text.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/);
        if (!m) return { error: '请使用标准 Skill 格式：---\\nname: x\\ndescription: y\\n---\\n<内容>' };
        const yaml = m[1], body = m[2];
        const nameMatch = yaml.match(/^name:\s*(.+?)\s*$/m);
        const descMatch = yaml.match(/^description:\s*(.+?)\s*$/m);
        const name = nameMatch ? nameMatch[1].trim() : '';
        if (!name) return { error: 'frontmatter 必须有 name: 字段' };
        if (name.length > 128) return { error: 'name 长度超过 128' };
        const description = descMatch ? descMatch[1].trim() : '';
        if (!description) return { error: 'frontmatter 必须有 description: 字段' };
        return { name, description, content: body.replace(/^\n+/, '') };
    },

    /** 触发隐藏 file input */
    handleImport() {
        const input = document.getElementById('skill-import-file-input');
        if (input) input.click();
    },

    async _doImportFile(file) {
        const text = await file.text();
        const parsed = this._parseSkillFrontmatter(text);
        if (parsed.error) {
            showToast('解析失败：' + parsed.error, 'error');
            return;
        }
        // 探测当前是否已有同名 Skill
        let existing = null;
        try { existing = await api.getSkill(parsed.name); } catch (_) { /* 404 */ }
        if (!existing) {
            await this._submitImport(parsed);
            return;
        }
        // 已存在：USER_CREATED 走三选项，锁定类型直接提示改名
        if (existing.source === 'USER_CREATED') {
            this._showImportConflictDialog(parsed, existing);
        } else {
            showToast(`同名 Skill「${parsed.name}」由 ${existing.source === 'ROLE_GRANTED' ? '角色授权' : '市场'}锁定，无法覆盖。请改名后导入。`, 'error');
        }
    },

    async _submitImport(parsed) {
        try {
            const r = await api.upsertSkill(parsed);
            const status = r && r.status ? r.status : 'imported';
            showToast(`已${status === 'updated' ? '覆盖' : '导入'}技能 ${parsed.name}`, 'success');
            this.renderModal();
        } catch (e) {
            showToast('导入失败：' + (e.message || e), 'error');
        }
    },

    _showImportConflictDialog(parsed, existing) {
        const m = document.getElementById('confirm-modal');
        document.getElementById('confirm-title').textContent = `技能「${parsed.name}」已存在`;
        document.getElementById('confirm-message').innerHTML =
            `同名技能已存在，请选择处理方式：<br><br><b>覆盖</b>：直接替换现有内容<br>` +
            `<b>另存为 ${parsed.name}-2</b>：自动寻找下一个可用后缀并保存<br>` +
            `<b>取消</b>：不导入`;
        const ok = document.getElementById('confirm-ok');
        const cancel = document.getElementById('confirm-cancel');
        const closeBtn = document.getElementById('confirm-close');
        ok.textContent = '覆盖';
        ok.style.background = 'var(--warning-color, #f59e0b)';
        ok.style.borderColor = 'var(--warning-color, #f59e0b)';
        let altBtn = document.getElementById('confirm-save-as-btn');
        if (!altBtn) {
            altBtn = document.createElement('button');
            altBtn.id = 'confirm-save-as-btn';
            altBtn.className = 'primary-btn';
            altBtn.style.flex = '1';
            altBtn.textContent = '另存为';
            m.querySelector('.modal-footer').insertBefore(altBtn, ok);
        }
        altBtn.textContent = `另存为 ${parsed.name}-2`;
        altBtn.style.display = '';
        cancel.textContent = '取消';
        const cleanup = () => {
            ok.textContent = '确定';
            ok.style.background = '';
            ok.style.borderColor = '';
            altBtn.style.display = 'none';
            ok.removeEventListener('click', onOk);
            altBtn.removeEventListener('click', onAlt);
            cancel.removeEventListener('click', onCancel);
            closeBtn.removeEventListener('click', onCancel);
        };
        const onOk = async () => { cleanup(); m.style.display = 'none'; await this._submitImport(parsed); };
        const onAlt = async () => {
            cleanup(); m.style.display = 'none';
            const newName = await this._findFreeName(parsed.name + '-2');
            await this._submitImport({ ...parsed, name: newName });
        };
        const onCancel = () => { cleanup(); m.style.display = 'none'; };
        ok.addEventListener('click', onOk);
        altBtn.addEventListener('click', onAlt);
        cancel.addEventListener('click', onCancel);
        closeBtn.addEventListener('click', onCancel);
        m.style.display = 'flex';
    },

    async _findFreeName(base) {
        let n = 2, name = base;
        while (true) {
            try {
                const r = await api.getSkill(name);
                if (!r) return name;
            } catch (_) { return name; }
            n++;
            name = base.replace(/-(\d+)$/, '') + '-' + n;
        }
    },
```

- [ ] **Step 4: 语法检查**

```bash
node --check spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js && echo JS_OK
```

预期：`JS_OK`。

- [ ] **Step 5: 浏览器手测**

- 打开技能库 → 选中「我的」Tab → 点开某个自建 Skill → 详情页出现「⬇ 下载」按钮 → 点击 → 浏览器下载 `xxx.skill.md`
- 打开文件验证：包含 `---\nname: x\ndescription: y\n---\n<原文>`，正文与详情页一致，**没有**被插入额外的 `# 标题`
- 锁定 Skill（角色授权 / 市场拉取）→ 详情页下载后提示「请使用新的名称」
- 技能库头部点「↑ 导入」→ 选一份标准 `.skill.md` → 提示「已导入 / 已覆盖 / 已取消」
- 选一份冲突文件 → 弹窗三选项

- [ ] **Step 6: 提交**

```bash
git add spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js
git commit -m "refactor(ui): 把 skill 下载/导入方法从 bindAllEvents 搬到 skills 对象

修正之前草稿导致的方法未定义错误：

- 删除 bindAllEvents() 内部的 handleDownload / _parseSkillFrontmatter /
  handleImport / _doImportFile / _submitImport / _showImportConflictDialog /
  _findFreeName 整段错误位置代码
- 把以上方法重新插入到 const skills = { ... } 内
- 修正 addIf('#skill-import-file-input', 'change', handler) → safeBindById(...)
- 锁定 Skill 提示文案改为「请使用新的名称」（之前误导说会覆盖同步关系）

下载格式：frontmatter + 原样正文（不重复插入 # 标题）。

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 10: 全量回归

**Files:** 无（只跑测试 + 浏览器手测）

- [ ] **Step 1: 后端单测全绿**

```bash
mvn clean test -pl spring-ai-loom-agent -q
mvn clean test -pl spring-ai-loom-agent-test -q
```

预期：两模块全绿。

- [ ] **Step 2: 浏览器手测 5 个场景**

启动：

```bash
mvn -pl spring-ai-loom-agent-test spring-boot:run
```

场景：

1. **slash picker**：输入 `/` → 浮层；`/no` → 过滤；`Enter` → 标签条 + 标签 + textarea 中 `/no` 清除
2. **后端注入**：选中 Skill → 发送问题 → 后端日志含 `name: <skill>` 注入成功
3. **成功清空**：发送完成后标签条消失
4. **失败保留**：临时断网发送 → 标签条保留
5. **下载 + 导入**：技能库详情页下载 / 技能库头部导入，下载文件 + 导入流程正常

- [ ] **Step 3: 收尾提交（如有）**

```bash
git status
# 如有未提交改动，commit；如无，跳过
```

---

## 自检

**Spec 覆盖**：

- §2.1（本次实现）覆盖：✅ 8 个任务已实现
- §2.2（暂不实现）覆盖：✅ 不在任务里
- §3 架构：✅ Task 2 / 8 覆盖
- §4 后端：✅ Task 1 / 2 覆盖
- §5 前端：✅ Task 3-7 覆盖
- §6 测试：✅ Task 2 / 10 覆盖
- §7 风险：✅ Backspace 触发 / 切换对话清空已加入 Task 6
