# 任务分段执行 prompt 引导 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a system prompt section + Tool description enhancement that nudges the LLM to proactively split long-reasoning tasks via `start_sub_task`, preventing the Spring AI 1.1.8 `JsonEOFException` triggered by tool_use JSON truncation.

**Architecture:** Two-layer guidance (system prompt section + Tool description) + contract test that locks the prompt wording. The change is purely additive — no interface change, no yml config, no migration. Aligns structurally with the existing `【提问与澄清】` askUser guidance to reuse the proven 3-layer (scenarios + protocol + few-shot) pattern.

**Tech Stack:** Java 17, Spring Boot 3.x, Spring AI 1.1.8, JUnit 5, reflection-based test contracts (matches `FriendlyMessageJsonEofTest` pattern)

**Spec:** `docs/superpowers/specs/2026-09-21-task-segmentation-prompt-design.md`

---

## Global Constraints

- Use verbatim text from spec § 5.1 for the new prompt section — no paraphrasing.
- Use verbatim text from spec § 5.2 for the Tool description addition — no paraphrasing.
- Single contract test file (`DefaultChatSubTaskGuidanceContractTest`) holds all 9 assertions — keep test methods independent, no shared state.
- Reflection-based testing mirrors `FriendlyMessageJsonEofTest` style — see `src/test/java/cn/wubo/spring/ai/loom/agent/chat/FriendlyMessageJsonEofTest.java` for the pattern.
- New prompt section must be inserted **after** `【提问与澄清】` and **before** the dynamic `【技能】` / `【知识库】` blocks (matches existing structural order).
- Do not modify `ISubTaskTool` / `ISubTaskExecutor` interfaces — only `DefaultSubTaskTool` implementation.
- Java source files use CRLF line endings (existing repo convention; `git config core.autocrlf` will normalize).
- All test assertions are keyword-substring checks (`contains`), not exact-match — locks the contract while tolerating future wording tweaks.
- Commit messages: `feat(chat):` / `feat(subtask):` / `docs:` prefix per CLAUDE.md.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChat.java` | Modify | Insert new prompt section in `buildDynamicSystemPrompt` |
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskTool.java` | Modify | Extend `@Tool(description=...)` on `start_sub_task` |
| `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChatSubTaskGuidanceContractTest.java` | Create | 9 contract assertions via reflection |
| `CLAUDE.md` | Modify | Sync `ISubTaskTool` and `IChat` entries |

All 4 files are independently testable; each task commits a self-contained change.

---

## Task 1: Add `【任务分段执行】` prompt section + 8 contract assertions

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChat.java:355-367` (insert after the `【提问与澄清】` block, before the `// 动态拼装可用能力` comment at line 369)
- Create: `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChatSubTaskGuidanceContractTest.java`

**Interfaces:**
- Consumes: existing `DefaultChat.buildDynamicSystemPrompt(String username, List<String> enabledKnowledgeIds)` signature — already public
- Produces: new string appended to prompt output containing the `【任务分段执行】` section

- [ ] **Step 1: Write the failing contract test**

Create `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChatSubTaskGuidanceContractTest.java`:

```java
package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.capability.CapabilityService;
import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.knowledge.IKnowledge;
import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.skill.ISkillStorage;
import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.IToolCallLogRepository;
import cn.wubo.spring.ai.loom.agent.user.IUserConversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 【任务分段执行】段契约回归测试（spec § 5.1 / § 5.3）
 * <p>
 * 8 个断言锁住 system prompt 新段的关键词、触发协议、few-shot 模板、护栏。
 * 另一个 Tool description 断言（A8）在 DefaultSubTaskToolTest 同文件里（Task 2）。
 */
@DisplayName("DefaultChat.buildDynamicSystemPrompt【任务分段执行】契约测试")
class DefaultChatSubTaskGuidanceContractTest {

    private DefaultChat chat;
    private Method buildDynamicSystemPrompt;
    private String prompt;

    @BeforeEach
    void setUp() throws Exception {
        LoomAgentProperties properties = new LoomAgentProperties();
        properties.setDefaultSystem("test persona");
        chat = new DefaultChat(
                mock(ChatClient.class),
                mock(IMcp.class),
                List.<IEmbedTool>of(),
                mock(IUserConversation.class),
                mock(IFile.class),
                mock(ISkillStorage.class),
                mock(IKnowledge.class),
                properties,
                mock(IToolCallLogRepository.class),
                mock(CapabilityService.class));
        buildDynamicSystemPrompt = DefaultChat.class.getDeclaredMethod(
                "buildDynamicSystemPrompt", String.class, List.class);
        buildDynamicSystemPrompt.setAccessible(true);
        prompt = (String) buildDynamicSystemPrompt.invoke(chat, "test-user", List.of());
    }

    @Test
    @DisplayName("A1: 段标题【任务分段执行】存在")
    void title() {
        assertTrue(prompt.contains("【任务分段执行】"),
                "应包含段标题【任务分段执行】,实际:" + prompt);
    }

    @Test
    @DisplayName("A2: 场景 1 — 大型代码关键词")
    void scenario1LargeCode() {
        assertTrue(prompt.contains("大型代码"),
                "场景 1 关键词缺失,实际:" + prompt);
    }

    @Test
    @DisplayName("A3: 场景 2 — 多工具链关键词")
    void scenario2ToolChain() {
        assertTrue(prompt.contains("多工具链"),
                "场景 2 关键词缺失,实际:" + prompt);
    }

    @Test
    @DisplayName("A4: 场景 3 — 长 reasoning 风险关键词")
    void scenario3LongReasoning() {
        assertTrue(prompt.contains("长 reasoning 风险"),
                "场景 3 关键词缺失,实际:" + prompt);
    }

    @Test
    @DisplayName("A5: 场景 4 — 大文件写入/编辑关键词")
    void scenario4LargeFile() {
        assertTrue(prompt.contains("大文件写入"),
                "场景 4 关键词缺失,实际:" + prompt);
    }

    @Test
    @DisplayName("A6: few-shot 模板含 start_sub_task + renderHtmlFile 调用")
    void fewShotCoversAppAndEngine() {
        assertTrue(prompt.contains("start_sub_task"),
                "few-shot 缺 start_sub_task 调用,实际:" + prompt);
        assertTrue(prompt.contains("renderHtmlFile"),
                "few-shot 缺 renderHtmlFile 调用,实际:" + prompt);
    }

    @Test
    @DisplayName("A7: 触发协议含 ≥3 个独立子目标")
    void triggerProtocolThreshold() {
        assertTrue(prompt.contains("≥3 个独立子目标")
                        || prompt.contains("≥ 3 个独立子目标"),
                "触发协议缺 ≥3 个独立子目标阈值,实际:" + prompt);
    }

    @Test
    @DisplayName("A9: 护栏 ≤5（与 askUser 5-ask 护栏对称）")
    void guardrailMaxFive() {
        assertTrue(prompt.contains("≤ 5") || prompt.contains("≤5"),
                "护栏缺 ≤5,实际:" + prompt);
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run from repo root:
```bash
mvn test -pl spring-ai-loom-agent -Dtest=DefaultChatSubTaskGuidanceContractTest -DfailIfNoTests=false
```

Expected: 8 tests, **all FAIL** with messages like "应包含段标题【任务分段执行】,实际:..." because the section hasn't been added yet.

- [ ] **Step 3: Insert the new prompt section in DefaultChat**

In `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChat.java`, locate the end of the `【提问与澄清】` block (currently ending at the line containing `"→ 没有关键疑问了，开始编写脚本，不再提问"`). Insert the following block **immediately after** that line, **before** the `// 动态拼装可用能力 + 对应使用说明` comment:

```java
// 【任务分段执行】— 引导 LLM 主动把长 reasoning 风险任务拆给 start_sub_task,避免主对话
// 流被截断后 JsonEOFException(spec § 5.1;契约由 DefaultChatSubTaskGuidanceContractTest 锁定)。
// 结构对齐【提问与澄清】段(正向场景枚举 + 触发协议 + few-shot 轨迹 + 护栏),复用 askUser
// 触发率优化的三层引导范式(2026-09-20 askUser 验证有效)。
sb.append("\n\n【任务分段执行】\n");
sb.append("单次主对话的流式输出长度有上限（Spring AI 1.1.8 Anthropic 协议下，max_tokens=16384 时\n");
sb.append("thinking 占用后留给 tool_use JSON 的 token 不到 8K）。当你判断一个任务超出单次主对话的\n");
sb.append("能力边界时，应主动调 start_sub_task(prompt, systemContext)把它拆出去异步执行，避免\n");
sb.append("主对话 reasoning 撞上限导致流截断（症状：聊天最终弹\"工具响应流被中途截断\"，工具\n");
sb.append("调用被强制中断）。\n");
sb.append("\n");
sb.append("必须主动拆的 4 类场景：\n");
sb.append("1. 大型代码/HTML 生成：单次要写出超过 200 行的完整文件（HTML 原型、SQL 脚本、复杂配置）\n");
sb.append("2. 多工具链调用：一次任务里需要顺序或并行调多个工具\n");
sb.append("   （writeFile → renderHtmlFile → 桥接 fileId → 拼装 markdown 链接）\n");
sb.append("3. 长 reasoning 风险：用户需求模糊、需先枚举可能方案再收敛\n");
sb.append("   （视觉风格选型、方案权衡、数据建模字段确认）\n");
sb.append("4. 大文件写入/编辑：整文件替换、超 5K 字符单 patch、整段重写\n");
sb.append("\n");
sb.append("触发协议（拆之前先评估）：\n");
sb.append("1. 估算任务是否含 ≥3 个独立子目标（如 4 张表 = 4 个独立 HTML 生成）\n");
sb.append("2. 是 → 第一步先调 start_sub_task，每个子目标一个独立 prompt\n");
sb.append("3. 子任务在 loomSubTaskExecutor 池异步执行，子任务完成会自动回流结果\n");
sb.append("4. 主对话做收口/汇总，不重复执行子任务的实际工作\n");
sb.append("护栏：能 1 步完成的任务不要拆；子任务总数 ≤ 5（与 askUser 5-ask 护栏对齐）。\n");
sb.append("\n");
sb.append("示例（任务分段）：\n");
sb.append("用户：帮我写一个四张表的 CRUD 维护页面原型 HTML（工厂/产线/工序/产品工序关系），下载为图片\n");
sb.append("→ 主对话先评估：4 张表 + 多工具链调用 + 需生成大型 HTML（命中场景 1、2）→ 第一步先\n");
sb.append("   start_sub_task(prompt=\"生成工厂表 HTML，含查询区、表格、分页；Ant Design 风格；\n");
sb.append("   列：工厂编码/工厂名称/删除标识\", systemContext=\"Ant Design 蓝白风格，标签化展示删除标识\")\n");
sb.append("→ start_sub_task(prompt=\"生成产线表 HTML ...\", systemContext=\"...\")\n");
sb.append("→ start_sub_task(prompt=\"生成工序表 HTML ...\", systemContext=\"...\")\n");
sb.append("→ start_sub_task(prompt=\"生成产品工序关系表 HTML ...\", systemContext=\"...\")\n");
sb.append("→ 主对话不做 HTML 生成，统一调 renderHtmlFile(...) 把拼好的 HTML 渲染成图片，\n");
sb.append("   返回预览+下载链接给用户\n");
```

- [ ] **Step 4: Run test to verify pass**

```bash
mvn test -pl spring-ai-loom-agent -Dtest=DefaultChatSubTaskGuidanceContractTest -DfailIfNoTests=false
```

Expected: 8 tests, **all PASS**. If any fail, re-check the inserted text matches spec § 5.1 verbatim (whitespace, line breaks, Chinese punctuation matter for `contains`).

- [ ] **Step 5: Commit**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChat.java \
        spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChatSubTaskGuidanceContractTest.java
git commit -m "feat(chat): 新增【任务分段执行】段引导 LLM 主动拆分长 reasoning 任务

spec § 5.1 — 4 场景(大型代码/多工具链/长 reasoning 风险/大文件写入)
+ 触发协议(≥3 子目标) + few-shot 轨迹(HTML 原型生成) + 护栏(≤5)。
结构对齐【提问与澄清】段,复用 askUser 三层引导范式。
契约:DefaultChatSubTaskGuidanceContractTest 8 断言。

补 D2 第二步(CLAUDE.md IChat 条目同步)见 Task 3。"
```

---

## Task 2: Strengthen `start_sub_task` `@Tool(description=...)` + A8 assertion

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskTool.java` (`start_sub_task` method, the `@Tool(description = "...")` annotation)
- Modify: `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChatSubTaskGuidanceContractTest.java` (append the 9th test method `toolDescriptionCoversStreamTruncation`)

**Interfaces:**
- Consumes: existing `DefaultSubTaskTool.start_sub_task(String prompt, String systemContext, ToolContext toolContext)` signature
- Produces: extended `@Tool(description)` string covering the stream-truncation rationale

- [ ] **Step 1: Add the failing A8 test method to the contract test file**

In `DefaultChatSubTaskGuidanceContractTest.java`, append this method (within the class, after the existing 8 test methods, before the final closing `}`):

```java
    @Test
    @DisplayName("A8: Tool description 含\"流协议截断\"关键短语（spec § 5.2 D1 改动到位）")
    void toolDescriptionCoversStreamTruncation() throws Exception {
        // 反射读 DefaultSubTaskTool.start_sub_task 上的 @Tool(description=...)
        Class<?> subTaskToolClass = Class.forName("cn.wubo.spring.ai.loom.agent.subtask.DefaultSubTaskTool");
        Method startSubTask = subTaskToolClass.getDeclaredMethod("start_sub_task",
                String.class, String.class, org.springframework.ai.tool.context.ToolContext.class);
        org.springframework.ai.tool.annotation.Tool toolAnnotation =
                startSubTask.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
        assertNotNull(toolAnnotation, "DefaultSubTaskTool.start_sub_task 必须有 @Tool 注解");
        String desc = toolAnnotation.description();
        assertTrue(desc.contains("流协议截断"),
                "Tool description 应明确提及流协议截断根因,实际:" + desc);
    }
```

- [ ] **Step 2: Run test to verify failure**

```bash
mvn test -pl spring-ai-loom-agent -Dtest=DefaultChatSubTaskGuidanceContractTest#toolDescriptionCoversStreamTruncation -DfailIfNoTests=false
```

Expected: **FAIL** with message "Tool description 应明确提及流协议截断根因,实际:..." — confirming the existing description doesn't have this phrase yet.

- [ ] **Step 3: Extend the `@Tool(description=...)` on `DefaultSubTaskTool.start_sub_task`**

In `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskTool.java`, locate the `@Tool(description = "启动一个子任务异步执行...")` annotation on the `start_sub_task` method. Append the spec § 5.2 text to the **end** of the existing description string (don't rewrite the existing part — only concatenate):

```java
    @Tool(description = "<existing description text>" +
            "用途：把需要大量 reasoning、规划或多步工具调用的大型任务拆出来异步执行，避免主对话" +
            "因 reasoning 超长被 Spring AI 1.1.8 流协议截断（截断症状：聊天最终弹\"工具响应流被" +
            "中途截断\"，用户体验断裂）。单次主对话适合 ≤ 200 行代码 / ≤ 3 个工具调用 / ≤ 5K 字符" +
            "文件的简单任务；超过这个尺度的任务请用 start_sub_task 拆分。")
```

The existing description starts with "启动一个子任务异步执行" and ends at "持续调用本工具逐轮提问"。Use string concatenation (`+`) to append the new spec § 5.2 text after the existing closing punctuation. Preserve all existing wording verbatim.

- [ ] **Step 4: Run test to verify pass**

```bash
mvn test -pl spring-ai-loom-agent -Dtest=DefaultChatSubTaskGuidanceContractTest -DfailIfNoTests=false
```

Expected: **9 tests, all PASS**.

- [ ] **Step 5: Commit**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskTool.java \
        spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChatSubTaskGuidanceContractTest.java
git commit -m "feat(subtask): start_sub_task @Tool description 加用途段

spec § 5.2 — 明确\"避免主对话因 reasoning 超长被 Spring AI 1.1.8 流协议截断\" +
\"简单任务尺度过线\"判断阈值(≤200 行 / ≤3 工具 / ≤5K 字符)。
与 DefaultChat【任务分段执行】段双层引导。契约 A8 锁定。
"
```

---

## Task 3: Sync `CLAUDE.md` (D2)

**Files:**
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: existing CLAUDE.md structure (look at the `IAskUserTool` paragraph — it's the template to mirror)
- Produces: updated `ISubTaskTool` and `IChat` entries pointing to this spec

- [ ] **Step 1: Read the existing `ISubTaskTool` and `IChat` entries in CLAUDE.md**

Run:
```bash
grep -n -A 3 "ISubTaskTool | \`DefaultSubTaskTool\`" CLAUDE.md
grep -n -A 5 "IChat | \`DefaultChat\`" CLAUDE.md
```

These two entries are the insertion targets.

- [ ] **Step 2: Append the spec reference to the `ISubTaskTool` entry**

In `CLAUDE.md`, locate the line `| \`ISubTaskTool\` | \`DefaultSubTaskTool\` | LLM-callable \`start_sub_task(prompt, systemContext)\` ...` (a row in the Core Interfaces table). Add the following **after** that row's content but before the next table row:

The convention in CLAUDE.md is to embed guidance inside backtick-enclosed phrases. Add at the end of the `ISubTaskTool` row's existing description (before the final backtick on that line), append:

```
 — `@Tool` 描述 2026-09-21 起为正向引导措辞（"用途：把需要大量 reasoning、规划或多步工具调用的大型任务拆出来异步执行，避免主对话因 reasoning 超长被流协议截断"），与 DefaultChat【任务分段执行】段双层引导，`DefaultChatSubTaskGuidanceContractTest` 锁定（9 断言：4 场景关键词 + few-shot + 触发协议 + Tool description + 护栏）。
```

**Note**: If the existing CLAUDE.md row uses single-line table cell format (no line breaks inside cells), append the addition **inline after the existing description** with a separator like ` — `. If the format allows multi-line cells, put the addition on a new line within the cell. Match the existing convention.

- [ ] **Step 3: Append the spec reference to the `IChat` entry**

In `CLAUDE.md`, locate the `IChat | DefaultChat | Chat streaming (SSE), MCP tool orchestration, RAG augmentation. ...` row. Append at the end of that row's description cell (within the backticks):

```
 — `buildDynamicSystemPrompt` 新增【任务分段执行】段（4 类必拆场景 + 触发协议 + 1 完整 few-shot 轨迹），紧跟【提问与澄清】段后；与 askUser 三层引导模式完全对称。规格：`docs/superpowers/specs/2026-09-21-task-segmentation-prompt-design.md`。
```

- [ ] **Step 4: Verify both edits render correctly in the rendered Markdown**

Open `CLAUDE.md` in any viewer and confirm:
- The `ISubTaskTool` row now ends with the spec reference and is no longer broken (table cells don't overflow)
- The `IChat` row now ends with the spec reference and is no longer broken
- The Markdown table still renders correctly (no extra `|` breaking alignment)

If the table breaks, split the addition into a footnote below the table or shorten the wording.

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: CLAUDE.md 同步 ISubTaskTool / IChat 条目(spec § 5.4 D2)

记录本次任务分段执行 prompt 引导的设计契约位置 + 9 断言。
spec: docs/superpowers/specs/2026-09-21-task-segmentation-prompt-design.md
"
```

---

## Task 4: Final regression sweep + JAR build

**Files:**
- (no production changes — verification only)

**Interfaces:**
- Consumes: existing test infrastructure
- Produces: green test report + deployable JAR

- [ ] **Step 1: Run full module test suite (must include all 3 test files)**

```bash
mvn test -pl spring-ai-loom-agent -DfailIfNoTests=false
```

Expected output:
- `FriendlyMessageJsonEofTest` — 3 tests, all PASS (no regression)
- `DefaultAskUserToolTest` — all PASS (no askUser regression)
- `DefaultChatThinkingBridgeTest` — all PASS
- `ChatReasoningHistoryTest` — all PASS
- `DefaultChatTest` — all PASS
- `DefaultChatSubTaskGuidanceContractTest` — 9 tests, all PASS (this spec's new tests)
- **BUILD SUCCESS** at the end

If any test fails, investigate the regression — do NOT modify this plan to skip the test. Fix the underlying issue and re-run.

- [ ] **Step 2: Build deployable JAR**

```bash
mvn clean install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure,spring-ai-loom-agent-spring-boot-starter -am -Dgpg.skip=true -DskipTests
```

Expected: BUILD SUCCESS for all 3 modules, with the new `spring-ai-loom-agent-1.0-SNAPSHOT.jar` in `spring-ai-loom-agent/target/`.

- [ ] **Step 3: Verify the JAR contains the new prompt section**

```bash
unzip -p spring-ai-loom-agent/target/spring-ai-loom-agent-1.0-SNAPSHOT.jar cn/wubo/spring/ai/loom/agent/chat/DefaultChat.class | strings | grep -c "【任务分段执行】"
```

Expected output: a number `>= 1` (the literal Chinese text is embedded as a UTF-8 string in the bytecode).

- [ ] **Step 4: (Optional) Local end-to-end retest**

This is **not a hard gate** — recommended but can be skipped if the engineer has already manually verified the LLM behavior in a prior session. If skipped, document the skip reason in the commit message of Task 4's verification commit (if any).

To run: navigate to `D:\developer\IdeaProjects\loom-agent`, start the app with `mvn spring-boot:run -pl .`, login, send the same prompt used in the original reproduction (see spec § 1.2 for the prompt text), answer all 5 askUser clarifications, and observe whether the LLM proactively calls `start_sub_task` for each of the 4 HTML tables.

- [ ] **Step 5: Final summary commit (only if changes were made)**

If Steps 1–3 revealed no issues, no commit is needed. If anything was tweaked (e.g., a typo in the prompt section), commit those tweaks:

```bash
git add -A
git commit -m "fix: post-implementation regression sweep fixes"
```

---

## Self-Review

Performed before saving:

**1. Spec coverage:** Walked through spec sections § 1 (background) → § 13 (estimates).
- § 1, § 2, § 9, § 11, § 12, § 13: reference (no task needed)
- § 3 (architecture): Task 1 + Task 2 file structure tables match
- § 4 (data flow): documented in spec, no task needed
- § 5.1 (C1): Task 1 Step 3 reproduces the text verbatim
- § 5.2 (D1): Task 2 Step 3 reproduces the text verbatim
- § 5.3 (T1): Task 1 Steps 1–4 + Task 2 Step 1 implement all 9 assertions (A1–A9)
- § 5.4 (D2): Task 3 implements both CLAUDE.md edits
- § 6 (risks): documented in spec, no task needed
- § 7 (test strategy): Task 1 + Task 2 + Task 4 cover the contract, regression, and JAR steps
- § 8 (deploy/rollback): deploy is the JAR build (Task 4 Step 2); rollback is `git revert` of Tasks 1–3
- § 10 (acceptance): Tasks 1–4 collectively satisfy all 5 acceptance criteria

**2. Placeholder scan:** No "TBD", "TODO", "implement later", or generic "similar to Task N". Code blocks contain verbatim text from spec.

**3. Type consistency:**
- `DefaultChat` constructor signature used in Task 1 and Task 2 test setup matches the public ctor (`ChatClient, IMcp, List<IEmbedTool>, IUserConversation, IFile, ISkillStorage, IKnowledge, LoomAgentProperties, IToolCallLogRepository, CapabilityService` — 10 args).
- `buildDynamicSystemPrompt(String, List<String>)` reflection target matches the actual method signature.
- `start_sub_task(String, String, ToolContext)` reflection target in Task 2 matches the actual method signature.
- `ToolContext` fully qualified import used to avoid package collision with other test imports.

**4. Out-of-scope check:** Confirm not doing these (spec § 11):
- ❌ ISubTaskTool / ISubTaskExecutor interface changes (correct — none in this plan)
- ❌ yml new config (correct — none)
- ❌ DefaultChat auto-segmentation (correct — separate spec)
- ❌ Spring AI 2.0+ adapter (correct — long-term plan)

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-21-task-segmentation-prompt.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast feedback on the prompt wording contract.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints for review.