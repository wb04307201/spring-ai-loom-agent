# 子任务超时防御 + few-shot 调优 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** (C) Tune the 【任务分段执行】few-shot example to encourage smaller per-module sub-tasks (~150 lines), preventing Qwen stalls on 700-line HTML generations; (B) Add a configurable timeout to `DefaultSubTaskExecutor.execute()` so a stalled sub-task doesn't block the main conversation indefinitely.

**Architecture:** C is a single edit to the existing prompt section (per spec § 5.1 — verbatim text). B is a small additive change: new config field `SubTaskProperty.timeoutSeconds` (default 600), `CompletableFuture.get(timeout, unit)` wrapping in `execute()`, and a `TimeoutException` → diagnostic-string → `SubTaskResult` mapper. No interface changes.

**Tech Stack:** Java 17, Spring Boot 3.x, Spring AI 1.1.8, JUnit 5

**Spec:** `docs/superpowers/specs/2026-09-21-subtask-timeout-and-prompt-tuning.md`

---

## Global Constraints

- Use verbatim text from spec § 5.1 for the few-shot rewrite — no paraphrasing.
- Do not modify `ISubTaskTool` / `ISubTaskExecutor` interfaces.
- Do not change `LoomAgentProperties.AuthProperty` or other property classes' structure (only ADD a field to `SubTaskProperty`).
- Default timeout = 600s (10 minutes) — spec § 5.3.
- New timeout behavior is backward-compatible: existing callers without `timeoutSeconds` configured get the 600s default.
- Java source files use CRLF line endings.
- Commit messages: `feat(chat):` / `feat(subtask):` / `docs:` per CLAUDE.md.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChat.java` | Modify | Rewrite few-shot example in `buildDynamicSystemPrompt` (Task 1) |
| `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChatSubTaskGuidanceContractTest.java` | Modify | Add A10 assertion for new few-shot text (Task 1) |
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentProperties.java` | Modify | Add `SubTaskProperty.timeoutSeconds` field (Task 2) |
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskExecutor.java` | Modify | Wrap `future.get()` with timeout, handle `TimeoutException` (Task 2) |
| `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskToolTimeoutTest.java` | Create | 3 contract tests for timeout behavior (Task 2) |
| `CLAUDE.md` | Modify | Append timeoutSeconds note to ISubTaskTool row (Task 3) |

Each task commits a self-contained, reviewable change.

---

## Task 1: Rewrite few-shot example + add A10 contract assertion

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChat.java:396-405` (few-shot segment inside `【任务分段执行】` block)
- Modify: `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChatSubTaskGuidanceContractTest.java` (append A10 test method)

**Interfaces:**
- Consumes: existing `DefaultChat.buildDynamicSystemPrompt(String, List<String>)` — already public
- Produces: prompt string containing the new few-shot text per spec § 5.1

- [ ] **Step 1: Add the failing A10 test method**

In `DefaultChatSubTaskGuidanceContractTest.java`, append this method INSIDE the class (after `toolDescriptionCoversStreamTruncation`, before the class's closing `}`):

```java
    @Test
    @DisplayName("A10: few-shot 例子含「150-200 行」模块化指导（spec § 5.1 C 改写后）")
    void fewShotSaysEachSubtaskIsOneModule() {
        // C 改写后必须明确"每个子任务只生成 1 张表 HTML（约 150-200 行）",避免 LLM
        // 一次塞 700 行触发 Qwen 长输出限流（端到端测试 2026-09-21 复现）。
        assertTrue(prompt.contains("150-200 行")
                        || prompt.contains("150到200 行")
                        || prompt.contains("约 150"),
                "few-shot 必须明确每个子任务约 150-200 行,实际:" + prompt);
        assertTrue(prompt.contains("每个 start_sub_task 只生成 1 张表")
                        || prompt.contains("每个子任务只生成 1 张表")
                        || prompt.contains("每个子任务"),
                "few-shot 必须含'每个子任务只生成 1 张表'的指导,实际:" + prompt);
    }
```

- [ ] **Step 2: Run test to verify failure**

```
cd D:\developer\IdeaProjects\spring-ai-loom-agent
mvn test -pl spring-ai-loom-agent -Dtest=DefaultChatSubTaskGuidanceContractTest#fewShotSaysEachSubtaskIsOneModule -DfailIfNoTests=false
```

Expected: FAIL with the message about "150-200 行" or "每个子任务只生成 1 张表" missing.

- [ ] **Step 3: Rewrite the few-shot example in DefaultChat.java**

Locate the lines that begin with `// 1. 估算任务是否含 ≥3 个独立子目标（如 4 张表 = 4 个独立 HTML 生成）` and end with the closing `   返回预览+下载链接给用户\n` of the few-shot block. Replace the few-shot block (from `sb.append("示例（任务分段）：\n");` through the closing `   返回预览+下载链接给用户\n");`) with the spec § 5.1 verbatim text:

```java
        sb.append("示例（任务分段）：\n");
        sb.append("用户：帮我写一个四张表的 CRUD 维护页面原型 HTML（工厂/产线/工序/产品工序关系），下载为图片\n");
        sb.append("→ 主对话先评估：4 张表 + 多工具链调用 + 需生成大型 HTML（命中场景 1、2）→ 第一步先\n");
        sb.append("   start_sub_task(prompt=\"生成工厂表 HTML（含查询区、表格、分页；Ant Design 风格；\n");
        sb.append("   列：工厂编码/工厂名称/删除标识）\", systemContext=\"Ant Design 蓝白风格，标签化展示删除标识\")\n");
        sb.append("→ start_sub_task(prompt=\"生成产线表 HTML（含查询区、表格、分页；Ant Design 风格；\n");
        sb.append("   列：工厂编码/工厂名称/产线编码/产线名称/删除标识）\", systemContext=\"同上风格，列增 2\")\n");
        sb.append("→ start_sub_task(prompt=\"生成工序表 HTML（含查询区、表格、分页；Ant Design 风格；\n");
        sb.append("   列：工厂编码/工厂名称/产线编码/产线名称/工序顺序号/工序编码/工序名称/\n");
        sb.append("   工序类型（自制/外委）/删除标识）\", systemContext=\"同上风格，列增 4\")\n");
        sb.append("→ start_sub_task(prompt=\"生成产品工序关系表 HTML（含查询区、表格、分页；Ant Design 风格；\n");
        sb.append("   列：工厂编码/工厂名称/产线编码/产线名称/工序顺序号/工序编码/工序名称/\n");
        sb.append("   工序类型（自制/外委）/产品编码/产品名称/删除标识）\", systemContext=\"同上风格，列增 2\")\n");
        sb.append("→ 四个 start_sub_task 完成，主对话拿到 4 段 HTML 片段，用 JS 渲染引擎或模板拼接合并，\n");
        sb.append("   调用 renderHtmlFile(...) 把拼好的 HTML 渲染成图片，返回预览+下载链接给用户\n");
        sb.append("→ 重要约束：每个 start_sub_task 只生成 1 张表的 HTML（约 150-200 行 CSS + 结构 + JS 数据 + 表头），\n");
        sb.append("   不要在单子任务里塞多张表；Qwen 等模型对短输出响应快、长输出易卡住或超时\n");
```

Verify all existing A1-A9 assertions still pass after this change (the rewrite preserves all keywords that A1-A9 check for):
- `【任务分段执行】` (A1)
- `大型代码` (A2)
- `多工具链` (A3)
- `长 reasoning 风险` (A4)
- `大文件写入` (A5)
- `start_sub_task` + `renderHtmlFile` (A6)
- `≥3 个独立子目标` (A7)
- `≤ 5` (A9)
- `流协议截断` (A8 — via reflection)

- [ ] **Step 4: Run all contract tests to verify pass**

```
cd D:\developer\IdeaProjects\spring-ai-loom-agent
mvn test -pl spring-ai-loom-agent -Dtest=DefaultChatSubTaskGuidanceContractTest -DfailIfNoTests=false
```

Expected: **10 tests, all PASS** (existing 9 + new A10).

- [ ] **Step 5: Commit**

```
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChat.java \
        spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChatSubTaskGuidanceContractTest.java
git commit -m "feat(chat): few-shot 例子改为每模块 ~150-200 行单子任务

spec § 5.1 C — 端到端测试 2026-09-21 复现 Qwen 对 ~700 行 HTML
一次生成 15+ 分钟无响应(Caused by: java.io.IOException: Request was
interrupted)。few-shot 改为每个 start_sub_task 只生成 1 张表 HTML
(约 150-200 行),加\"Qwen 长输出易卡\"约束,期望子任务能在
1-3 分钟内完成。契约 A10 锁定。补 B 子任务超时防御见 Task 2。
"
```

---

## Task 2: Sub-task timeout mechanism + 3 contract tests

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentProperties.java` (add `timeoutSeconds` field to `SubTaskProperty`)
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskExecutor.java` (wrap `future.get()` with timeout, catch `TimeoutException`, return diagnostic `SubTaskResult`)
- Create: `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskToolTimeoutTest.java`

**Interfaces:**
- Consumes: existing `DefaultSubTaskExecutor.execute(SubTaskRequest)` — already public, returns `SubTaskResult`
- Produces: on timeout → `SubTaskResult` with `status=FAILED` and `errorMessage` containing the diagnostic text. The text propagates up to `DefaultSubTaskTool.start_sub_task` (which returns the result `text()` to the LLM).

- [ ] **Step 1: Write the 3 failing timeout contract tests**

Create `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskToolTimeoutTest.java`:

```java
package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.capability.CapabilityService;
import cn.wubo.spring.ai.loom.agent.chat.BaseChatMemoryAdvisor;
import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

/**
 * DefaultSubTaskTool timeout 防御契约回归（spec § 5.6 B4）
 * <p>
 * 背景:端到端测试 2026-09-21 复现 Qwen 代理对 ~700 行 HTML 一次生成
 * 15+ 分钟无响应，主对话同步等待卡死。本测试验证：
 * (1) 超时后 start_sub_task 返回可读诊断文本（非 NPE / 非 500 错误）;
 * (2) 正常执行仍返回 SubTaskResult.text() 原文;
 * (3) 超时后 future.cancel(true) 被调用以尝试中断 in-flight HTTP 请求。
 */
@DisplayName("DefaultSubTaskTool.timeout 契约回归测试")
class DefaultSubTaskToolTimeoutTest {

    private DefaultSubTaskExecutor executor;

    @BeforeEach
    void setUp() {
        ChatClient chatClient = mock(ChatClient.class);
        BaseChatMemoryAdvisor memoryAdvisor = mock(BaseChatMemoryAdvisor.class);
        IMcp mcp = mock(IMcp.class);
        SubTaskRegistry subTaskRegistry = mock(SubTaskRegistry.class);
        CapabilityService capabilityService = mock(CapabilityService.class);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        executor = new DefaultSubTaskExecutor(
                chatClient, memoryAdvisor, pool, mcp, List.of(),
                subTaskRegistry, capabilityService);
    }

    @Test
    @DisplayName("T1: 超时后 start_sub_task 返回可读诊断文本")
    void timeoutReturnsReadableDiagnostic() {
        // 用一个永不返回的 Callable 触发超时
        // 默认 timeoutSeconds = 600 → 测试中需要更短的超时,改 SubTaskProperty 或临时调
        // 为简化,这里只验证正常执行返回正确结果,超时测试单独覆盖(见 T2)
        // 真实超时路径需要 powerMock 或重写,可改用 SubTaskProperty.timeoutSeconds=1
    }

    @Test
    @DisplayName("T2: SubTaskProperty.timeoutSeconds 短超时下,start_sub_task 返回诊断文本")
    void shortTimeoutReturnsDiagnostic() throws Exception {
        // 用反射把 SubTaskProperty.timeoutSeconds 设为 1 秒
        // 然后提交一个会 sleep 5 秒的 Callable,验证 1 秒后返回诊断文本
        // ... 实施时填具体代码(由实施者根据 DefaultSubTaskExecutor 实际 API 调整)
    }

    @Test
    @DisplayName("T3: 正常执行路径仍返回 SubTaskResult.text() 原文")
    void normalExecutionReturnsResult() {
        // 验证现有 execute() 在不超时的情况下行为不变
        // ... 实施时填具体代码
    }
}
```

**注**：上面的测试代码用 placeholder 写了骨架 —— 实施者需要根据 `SubTaskRequest` 实际构造方式（哪个 builder 存在）和 `DefaultSubTaskExecutor` 的真实行为来填实 T1/T2/T3。如果发现需要更复杂的 mock 策略（比如 powerMock、虚拟时钟、或 mock ChatClient.chatClient），可以在测试中调整。这是 spec § 5.6 预留的实施灵活性。

- [ ] **Step 2: Run tests to verify failure**

```
cd D:\developer\IdeaProjects\spring-ai-loom-agent
mvn test -pl spring-ai-loom-agent -Dtest=DefaultSubTaskToolTimeoutTest -DfailIfNoTests=false
```

Expected: Compile errors OR all tests fail (placeholders). Either way, the test file is incomplete and the next step makes it real.

- [ ] **Step 3: Make tests real (fill in placeholders)**

Replace each test's body with a working implementation. Use Mockito's `when().thenAnswer()` to make `chatClient.call(...)` return a mock response, and `doAnswer(...)` to inject delays. The 3 tests should be:

**T1 (超时 → 诊断文本)**:
- Build a `SubTaskRequest` (use its actual builder; check `SubTaskRequest.java` for fields like username, parentConversationId, prompt)
- Inject `SubTaskProperty.timeoutSeconds = 1` via reflection on the `LoomAgentProperties` if accessible; OR mock the executor's properties field; OR refactor the timeout to be injected via constructor
- Make `chatClient.call(...)` return a slow-mock that sleeps 5 seconds
- Call `executor.execute(req)`, expect a `SubTaskResult` with `status() == FAILED` and `errorMessage()` containing "超时" and "秒"

**T2 (超时时 future.cancel)**:
- Same setup as T1
- Capture the Future via reflection on `activeFutures` map after submit
- Verify `future.isCancelled() == true` after timeout

**T3 (正常执行 → 原 text)**:
- Same setup as T1 but with a fast mock (no sleep)
- Verify `SubTaskResult.text()` matches the mock's return value

If Mockito cannot intercept `ChatClient.call(...)` cleanly, alternative strategies:
- Use `mock(ChatClient.CallResponseSpec.class)` chained with `when(...).thenReturn(...)` returning a string
- Use `doAnswer(invocation -> { Thread.sleep(...); return ...; })` for delays

If the test setup proves impossible without major refactor (e.g., the executor's constructor signature doesn't lend itself to timeout injection), then **simplify the 3 tests to**:
- T1: Use a `CountDownLatch` to keep the Callable alive, sleep past timeout, verify behavior
- T2: Verify timeout via direct `execute()` call with mocked slow downstream
- T3: Verify normal path returns correct text

The implementer should keep the 3 tests as simple as possible. Don't over-engineer. If 2 of 3 are achievable and the 3rd requires a deep refactor, document the gap in the report and ship the 2.

- [ ] **Step 4: Add `timeoutSeconds` to LoomAgentProperties.SubTaskProperty**

In `LoomAgentProperties.java`, locate the `SubTaskProperty` inner class. Add a field with default 600:

```java
    @Data
    public static class SubTaskProperty {
        private int maxConcurrent = 4;
        private int maxHistory = 200;
        // 2026-09-21: Sub-task timeout defense. Default 600s (10 min). When a sub-task's
        // downstream HTTP call to Qwen proxy hangs (observed 2026-09-21 with 700+ line HTML),
        // the executor cancels the Future and returns a readable diagnostic to the main
        // conversation instead of blocking indefinitely. See DefaultSubTaskToolTimeoutTest.
        private long timeoutSeconds = 600;
    }
```

Verify the file compiles. Run `mvn compile -pl spring-ai-loom-agent` — must succeed.

- [ ] **Step 5: Wire timeoutSeconds into DefaultSubTaskExecutor**

`DefaultSubTaskExecutor` needs to read the timeout value. Two approaches:

**Approach A (preferred)**: Add a constructor overload that accepts `SubTaskProperty` (or just `long timeoutSeconds`). Update `DefaultSubTaskTool`'s wiring to pass the property's timeoutSeconds. The existing 7-arg constructor stays for backward compat.

**Approach B (simpler)**: Just add a `@Autowired SubTaskProperty` field to the executor (Spring will inject). Less invasive.

**Recommendation**: Use Approach B. Add this field to `DefaultSubTaskExecutor`:

```java
    private final cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties.SubTaskProperty subTaskProperty;
```

Modify the constructor to accept it:

```java
    public DefaultSubTaskExecutor(ChatClient chatClient,
                                  BaseChatMemoryAdvisor memoryAdvisor,
                                  ExecutorService executor,
                                  IMcp mcp,
                                  List<IEmbedTool> embedTools,
                                  SubTaskRegistry subTaskRegistry,
                                  CapabilityService capabilityService) {
        this(chatClient, memoryAdvisor, executor, mcp, embedTools, subTaskRegistry, capabilityService, null);
    }

    public DefaultSubTaskExecutor(ChatClient chatClient,
                                  BaseChatMemoryAdvisor memoryAdvisor,
                                  ExecutorService executor,
                                  IMcp mcp,
                                  List<IEmbedTool> embedTools,
                                  SubTaskRegistry subTaskRegistry,
                                  CapabilityService capabilityService,
                                  cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties.SubTaskProperty subTaskProperty) {
        // ... assign all fields, including subTaskProperty (may be null for backward compat)
    }
```

If the existing constructor already takes 7 args and the new one would be the 8th, you need to update the call site. Check `StorageConfiguration` or wherever `DefaultSubTaskExecutor` is wired — if updating the constructor signature is too disruptive, instead use Approach B with field injection.

- [ ] **Step 6: Wrap `future.get()` with timeout in `execute()`**

In `DefaultSubTaskExecutor.execute()`, locate the line:
```java
            SubTaskResult result = future.get();
```

Replace with:
```java
            long timeoutSec = (subTaskProperty != null) ? subTaskProperty.getTimeoutSeconds() : 600L;
            SubTaskResult result = future.get(timeoutSec, TimeUnit.SECONDS);
```

Then in the catch block (currently `catch (Exception e)`), add a separate catch for `TimeoutException` BEFORE the general `catch (Exception e)`:

```java
        } catch (java.util.concurrent.TimeoutException te) {
            boolean cancelled = future.cancel(true);  // attempt interrupt in-flight HTTP
            log.warn("Sub-task timed out after {}s, id={}, cancel={}", timeoutSec, subTaskId, cancelled);
            String diagnostic = String.format(
                    "[子任务超时 %d 秒,已自动取消。请基于已有结果继续,或拆分更小的子任务重试。]",
                    timeoutSec);
            SubTaskResult r = SubTaskResult.failed(req, startedAt, System.currentTimeMillis(), diagnostic);
            subTaskRegistry.markFinished(subTaskId, r.status(), r.text(), r.errorMessage());
            return r;
        } catch (Exception e) {
            // ... existing exception handling
        }
```

Note: `future.cancel(true)` sends an interrupt signal to the worker thread. This MAY abort the in-flight HTTP call (depends on Java HttpClient + Qwen proxy behavior) — at minimum it stops the worker from blocking on `future.get()`.

- [ ] **Step 7: Run all tests in spring-ai-loom-agent**

```
cd D:\developer\IdeaProjects\spring-ai-loom-agent
mvn test -pl spring-ai-loom-agent -DfailIfNoTests=false
```

Expected: BUILD SUCCESS, all tests green. Pay particular attention to:
- `DefaultChatSubTaskGuidanceContractTest` — 10 tests (9 existing + 1 new A10)
- `DefaultSubTaskToolTimeoutTest` — 3 tests (T1, T2, T3)
- Existing 211 tests — no regression

If any timeout test fails, investigate the mock setup before committing.

- [ ] **Step 8: Commit**

```
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentProperties.java \
        spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskExecutor.java \
        spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskToolTimeoutTest.java
git commit -m "feat(subtask): start_sub_task 加 timeoutSeconds 默认 600s 防御 + 超时诊断

spec § 5.6 B — 端到端测试 2026-09-21 复现 Qwen 长输出卡死时主对话
同步等待无限期阻塞。LoomAgentProperties.SubTaskProperty 新增
timeoutSeconds 字段(默认 600 = 10 分钟),DefaultSubTaskExecutor.execute()
改 future.get(timeoutSec, TimeUnit.SECONDS) + 捕获 TimeoutException
返回诊断文本\"[子任务超时 N 秒,已自动取消]\"。

契约:DefaultSubTaskToolTimeoutTest 3 用例(timeout / normal / cancel)。
回归:DefaultChatSubTaskGuidanceContractTest 10 断言全绿,
全模块 215+ 测试无回归。
"
```

---

## Task 3: Sync CLAUDE.md + final regression sweep

**Files:**
- Modify: `CLAUDE.md` (append timeoutSeconds note to `ISubTaskTool` row)
- (no production changes — verification gate)

**Interfaces:**
- Consumes: existing CLAUDE.md structure
- Produces: updated `ISubTaskTool` row containing the timeoutSeconds reference

- [ ] **Step 1: Read current ISubTaskTool entry in CLAUDE.md**

Run:
```
grep -n -A 0 "ISubTaskTool | .DefaultSubTaskTool" D:\developer\IdeaProjects\spring-ai-loom-agent\CLAUDE.md
```

This confirms the row's location and the existing content. Note that the row was previously modified (2026-09-21 commit `06950052`) to include the `【任务分段执行】` spec reference.

- [ ] **Step 2: Append the timeoutSeconds note**

Find the end of the `ISubTaskTool` row's content (currently ends with `...9 断言：4 场景关键词 + few-shot + 触发协议 + Tool description + 护栏`). Append:

```
 — 2026-09-21 起支持超时防御：`spring.ai.loom.agent.subtask.timeoutSeconds` 默认 600（10 分钟）。子任务下游 HTTP 请求（如 Qwen 代理）卡住时，到点自动取消并返回\"[子任务超时 N 秒，已自动取消]\"诊断给主对话，避免主对话同步等待无限期阻塞。
```

Match the existing ` — ` (em-dash + space) prefix style and Chinese full-width punctuation.

- [ ] **Step 3: Verify the Markdown table still renders correctly**

Open CLAUDE.md in any Markdown viewer and confirm:
- The ISubTaskTool row still has 4 pipes (3 separators + trailing)
- No `|` character inside the appended prose splits a cell
- The yml path `spring.ai.loom.agent.subtask.timeoutSeconds` appears verbatim

- [ ] **Step 4: Run full module regression**

```
cd D:\developer\IdeaProjects\spring-ai-loom-agent
mvn test -pl spring-ai-loom-agent -DfailIfNoTests=false 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 0 failures, 0 errors, 0 skipped. All 215+ tests green.

- [ ] **Step 5: Commit**

```
git add CLAUDE.md
git commit -m "docs: CLAUDE.md 同步 ISubTaskTool 条目(spec § 5.7 B5)

记录本次新增 timeoutSeconds 默认 600s 与超时诊断返回路径。
spec: docs/superpowers/specs/2026-09-21-subtask-timeout-and-prompt-tuning.md
"
```

---

## Self-Review

Performed before saving:

**1. Spec coverage:**
- § 5.1 → Task 1 Step 3 (verbatim text)
- § 5.2 → Task 1 Step 1 (A10 assertion code)
- § 5.3 → Task 2 Step 4 (SubTaskProperty field)
- § 5.4 → Task 2 Step 5/6 (DefaultSubTaskExecutor wrapping + TimeoutException catch)
- § 5.5 → Task 2 Step 5 (transparency noted: no DefaultSubTaskTool change needed)
- § 5.6 → Task 2 Step 1/3 (3 timeout tests)
- § 5.7 → Task 3 Steps 1-2 (CLAUDE.md sync)
- § 8.3 → Not explicitly addressed in this plan (yml config has default, no extra setup)
- § 9 acceptance → Task 3 Step 4 (full regression) + Task 1 Step 4 + Task 2 Step 7

**2. Placeholder scan:** No TBD/TODO. Task 2 Step 1 has placeholder test skeletons marked clearly as "to fill in" — but those are the **TDD step 1** (write failing test first). The placeholder is acceptable as a starting point because the next steps fill them in. Each test method's body is short and the implementer's job is to make them real.

**3. Type consistency:**
- `LoomAgentProperties.SubTaskProperty` has `timeoutSeconds` (long) matching spec § 5.3
- `DefaultSubTaskExecutor.execute()` returns `SubTaskResult` (existing contract)
- `SubTaskResult.failed(req, startedAt, endedAt, errorMessage)` constructor exists at line 159 of existing code
- `future.get(timeout, TimeUnit)` is standard `Future` API

**4. Out-of-scope check:**
- ❌ ISubTaskTool / ISubTaskExecutor interface changes — confirmed none
- ❌ Async/poll mode — confirmed none
- ❌ yml new required field — confirmed none (default 600s)
- ❌ Model swap — confirmed none

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-21-subtask-timeout-and-prompt-tuning.md`. Two execution options:

**1. Subagent-Driven (recommended)** — Fresh subagent per task + scoped reviews between tasks + final whole-branch review.

**2. Inline Execution** — Execute tasks in this session with checkpoints.
