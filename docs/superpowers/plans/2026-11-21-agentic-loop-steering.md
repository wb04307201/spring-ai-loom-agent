# Agentic Loop 跨轮续跑 + Steering 对话模式 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让主对话具备 Claude Code 式跨轮续跑——AI 运行中用户可随时插话(steering),插话在当前工具调用结束的同一 turn 内注入;askUser 卡片新增"聊聊这个"即时 dismiss 出口;气泡按事件拆分;历史回放与 admin 流水保持一致。

**Architecture:** 继承 Spring AI 2.0.2 `ToolCallingAdvisor` 覆写 protected 钩子(内层 `doGetNextInstructionsForToolCall` 注入 steering、外层 `doFinalizeLoop` inbox 驱动续跑);自定义 `LoomMemoryAdvisor` 替换 final 的 `MessageChatMemoryAdvisor` 实现逐迭代 ASSISTANT/逐注入 USER 落库;per-run `StreamRunContext` 桥接 SSE lambda 与 advisor;前端三态输入机(IDLE/RUNNING/ASK_PENDING)+ 灰态排队气泡 + 事件驱动气泡。

**Tech Stack:** JDK 25, Spring Boot 4, Spring AI 2.0.2, Reactor, H2 + Flyway, vanilla JS SPA, JUnit 5 + AssertJ + Mockito, Java Playwright BrowserIT。

**Spec:** `docs/superpowers/specs/2026-11-21-agentic-loop-steering-chat-mode-design.md`(执行者必读,计划从 spec 论证,所有裁定值以 spec 为准)

## Global Constraints

- 分支 `feat/agentic-loop-steering`(已自 dev 切出)。每个 task 末尾 commit,message 用中文,前缀 `feat:/fix:/refactor:/test:/docs:`,结尾 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。
- **A1 纯增量不变式**:inbox 空时单迭代,SSE 帧序列/memory 行布局/子任务行为与改造前完全相同。每个后端 task 的测试必须含一条"无 steering 退化"断言。
- **A4 硬约束**:steering UserMessage 只能注入在 tool_result 就位之后、下一次模型调用之前;绝不插在 assistant tool_call 与 tool_result 之间。
- **A5 子任务隔离**:loop advisor 只经 `DefaultChat` 的 `requestSpec.advisors(...)` 逐请求挂载,**不得**注册为 ChatClient defaultAdvisor 或共享 bean(子任务复用同一 ChatClient)。
- order 不变式:`LoomMemoryAdvisor` = `Ordered.HIGHEST_PRECEDENCE + 200`(上游);`LoomAgenticLoopAdvisor` = `ToolCallingAdvisor.DEFAULT_ORDER`(= HIGHEST_PRECEDENCE + 300)。memory 必须上游,否则 auto-register 关 `conversationHistoryEnabled`(Issue1)。
- 配置键 `spring.ai.loom.agent.loop.max-continuations` 默认 **10**、`run-timeout-seconds` 默认 **1800**;无 enabled 开关。steering 文本上限 **8KB**(超限 400);inbox 深度上限 **20**(超限 429)。
- DISMISS 返回文本必须**非 is_error、中性**,含"等待用户补充",不含"用户未作答"/"继续"指令(spec §5.3 原文)。
- 库/autoconfigure 改动后、跑 test 模块前必须 `mvn install -pl <module> -Dgpg.skip=true -DskipTests`(test 模块经 m2 加载,只改源不重装会跑旧 jar)。
- IT 门:显式 `-Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false`,跑前清 `spring-ai-loom-agent-test/target/{test-ds,test-users,e2e-files}`;**不触碰 ~/.loom**。默认 test 阶段不跑 *IT。
- 前端用户可控字段一律 `esc()`/textContent;a11y 色板不得漂移(`A11yTokenDriftContractTest` 守卫,禁用 #6366f1/#94a3b8 等废弃 token)。
- 截图基线改版后本机 `-DupdateBaselines=true` 重建。
- 项目只跑全新库;V1.0 新增段后已有库须清库重跑(既定升级策略)。

## Review Focus

spec 隐含但易被漏测、最可能咬到真实用户的 5 类输入/条件(各已落到 owning task 的测试步骤):

1. **steering/dismiss 文本含 HTML/script**(如 `<img src=x onerror=alert(1)>`)→ 灰态气泡与历史回放必须转义 inert,不得 XSS。→ Task 11/14 BrowserIT 注入断言。
2. **steer 恰好落在 finalize 收口窗口**(run 即将注销)→ 要么 queued 且续跑、要么显式 queued:false 且前端等本地 complete 后回退,**绝不静默丢**(A2)。→ Task 7 并发单测。
3. **运行中刷新/断连且有排队 steering** → 订阅必须 dispose,断连后不得继续写 memory/usage(A8 orphan)。→ Task 7 IT。
4. **"聊聊这个"双击 / dismiss 与 300s 超时/stop 竞态** → CAS 先到先得,不重复 freeze、不 404 toast 刷屏、不返回 is_error(A3)。→ Task 8 并发单测 + 契约变异。
5. **inbox spam(>20)/ 超 8KB / 多字节 emoji 文本** → 429/400 可见反馈,不 OOM;emoji 经 SSE + H2 CLOB round-trip 完整(GBK 前科)。→ Task 7 端点单测 + Task 9 repo round-trip。

## File Structure

**新建(主库 `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/`):**
- `chat/StreamRunContext.java` — per-run 上下文:inbox 引用、迭代计数、reasoning 缓冲+delta 状态、memory 写回调、帧发射回调、stop 标志、活跃计时(D-3)。SSE lambda 构造并绑定,经 advisor param 传递。
- `chat/SteeringInbox.java` — per-run 并发队列 + closing CAS + 深度上限。record `SteeringMessage(String messageId, String text)`。
- `chat/MemoryWriter.java` — 函数式接口 `void writeUser(Message m)` / `void writeAssistant(Message m)`,绑定 ChatMemory+convId。
- `chat/LoomMemoryAdvisor.java` — implements BaseChatMemoryAdvisor,order +200,逐迭代/逐注入写 + legacy after 单写。
- `chat/LoomAgenticLoopAdvisor.java` — extends ToolCallingAdvisor,order +300,覆写内层注入/外层续跑钩子。
- `model/SteerAck.java` — record `(String messageId)`。
- `model/IterationEvent.java` — record `(int index)`。
- `tool/JdbcLoomChatSteeringRepository.java` + `tool/ILoomChatSteeringRepository.java` + `model/SteeringAuditRow.java` — 审计表仓储 + ensureSchema。

**修改(主库):**
- `model/ChatResponseRecord.java` — 加 `SteerAck steerAck` / `IterationEvent iteration` 分量 + 兼容构造器。
- `model/LoomAgentProperties.java` — 加 `LoopProperty`(maxContinuations/runTimeoutSeconds)+ getLoop()。
- `chat/DefaultChat.java:219` 附近 — 逐请求挂 loop advisor + 传 StreamRunContext param + steering 引导句。
- `askuser/AskUserRegistry.java` — 加 `DISMISS_SENTINEL`。
- `askuser/DefaultAskUserTool.java:167` 附近 — 识别 dismiss 哨兵返回中性文本。
- `chat/ConversationFlowService.java:116–147` — B2 重写豁免 steering USER 行。
- `stream/SseEmitterRegistry.java:27,75` — register/autoCleanup 改 Entry 身份感知;Entry 加 inbox 载体。
- `src/main/resources/db/migration/V1.0__init.sql` 末尾 — loom_chat_steering 建表段。

**修改(autoconfigure `LoomAgentConfiguration.java`):**
- `:573–576` messageChatMemoryAdvisor bean → 返回 LoomMemoryAdvisor(类型改 MemoryAdvisor)。
- `:537–546` chatClient defaultAdvisors 参数类型同步。
- `:617–760` SSE lambda → 构造 StreamRunContext、绑定 memory/reasoning/帧回调、按迭代 flush、断连 dispose。
- `:776` 附近 @RestController → 加 steer 端点。
- `:2016–2048` ask answer 路由 → mode=DISMISS 分支。
- `:2508–2538` 历史端点 → Message 附 ts + TOOL_CALL 服务端 interleave。
- `:1210` SubTaskConfiguration memoryAdvisor 注入类型已是 MemoryAdvisor(兼容)。

**修改(前端 `META-INF/resources/spring/ai/loom/`):**
- `app.js` — inputMode 三态机、steer send、灰态气泡、事件驱动气泡、迭代分段、dismiss 按钮、提示条、历史分组。
- `style.css` — 灰态气泡/提示条/分隔线/转入对话徽章样式(复用 token)。
- `tool-call-renderer.js` — 第五终态"转入对话"徽章。

**测试(`spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/`):** 各 task 指定。

---

## Phase 1 — 后端基础(无 Spring AI 耦合,纯单元可测)

### Task 1: SteeringInbox + StreamRunContext + MemoryWriter

**Files:**
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/SteeringInbox.java`
- Create: `.../chat/MemoryWriter.java`
- Create: `.../chat/StreamRunContext.java`
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/chat/SteeringInboxTest.java`
- Test: `.../chat/StreamRunContextTest.java`

**Interfaces:**
- Produces:
  - `SteeringInbox`: `boolean offer(String messageId, String text)`(closing 或 depth≥20 → false); `List<SteeringMessage> drain()`; `void close()`; `boolean isClosing()`; `int depth()`; `List<SteeringMessage> closeAndRemaining()`(收口时返回未注入残留,供可见处置)。record `SteeringMessage(String messageId, String text)`。
  - `MemoryWriter`: `void writeUser(org.springframework.ai.chat.messages.Message m)`; `void writeAssistant(Message m)`。
  - `StreamRunContext`: 构造 `(SteeringInbox inbox, MemoryWriter memory, java.util.function.Consumer<String> reasoningFlush, java.util.function.Consumer<SteerAck> ackEmitter, java.util.function.Consumer<IterationEvent> iterationEmitter, Runnable stopSignal)`。方法:`SteeringInbox inbox()`; `MemoryWriter memory()`; `int nextIteration()`(返回递增序号,首迭代=0); `void appendReasoning(String delta)`; `String drainReasoning()`(取走当前迭代缓冲并清空); `void flushReasoning()`(调 reasoningFlush 消费 drainReasoning,空则不调); `void emitAck(String messageId)`; `void emitIteration(int index)`; `boolean isStopped()`(调 stopSignal 判定); `void pauseClock()`/`void resumeClock()`/`long activeElapsedMs()`(D-3 活跃计时,暂停区间不计); `void markPerIterationMode()`/`boolean perIterationMode()`。

- [ ] **Step 1: 写失败测试 `SteeringInboxTest`**

```java
@Test void offer_drain_fifo() {
  SteeringInbox b = new SteeringInbox();
  assertThat(b.offer("m1", "a")).isTrue();
  assertThat(b.offer("m2", "b")).isTrue();
  assertThat(b.drain()).extracting(SteeringInbox.SteeringMessage::messageId).containsExactly("m1", "m2");
  assertThat(b.depth()).isZero();
}
@Test void offer_rejected_when_closing() {
  SteeringInbox b = new SteeringInbox(); b.close();
  assertThat(b.offer("m1", "a")).isFalse();
  assertThat(b.isClosing()).isTrue();
}
@Test void offer_rejected_at_depth_20() {
  SteeringInbox b = new SteeringInbox();
  for (int i = 0; i < 20; i++) assertThat(b.offer("m" + i, "x")).isTrue();
  assertThat(b.offer("over", "x")).isFalse(); // 429 语义
}
@Test void closeAndRemaining_returns_undrained() {
  SteeringInbox b = new SteeringInbox(); b.offer("m1", "a");
  assertThat(b.closeAndRemaining()).extracting(SteeringInbox.SteeringMessage::messageId).containsExactly("m1");
  assertThat(b.isClosing()).isTrue();
}
@Test void concurrent_offer_drain_no_loss() throws Exception {
  // 2 线程各 offer 100 条,drain 收集总数=200,无重复 messageId(A2)
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=SteeringInboxTest`
Expected: FAIL(编译错误,SteeringInbox 不存在)

- [ ] **Step 3: 实现 SteeringInbox**

`ConcurrentLinkedQueue<SteeringMessage>` + `AtomicBoolean closing` + `AtomicInteger depth`。offer:`if (closing.get() || depth.get() >= 20) return false;` 入队后 depth++。drain:poll 至空,depth 归零。closeAndRemaining:`closing.set(true)` 后 drain 残留。线程安全靠 CAS/并发队列,不加大锁。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=SteeringInboxTest`
Expected: PASS(5 测试)

- [ ] **Step 5: 写失败测试 `StreamRunContextTest`**

```java
@Test void reasoning_drain_resets_buffer() {
  List<String> flushed = new ArrayList<>();
  StreamRunContext c = ctx(flushed::add);
  c.appendReasoning("ab"); c.appendReasoning("cd");
  assertThat(c.drainReasoning()).isEqualTo("abcd");
  assertThat(c.drainReasoning()).isEmpty();
}
@Test void flushReasoning_skips_when_empty() {
  List<String> flushed = new ArrayList<>();
  StreamRunContext c = ctx(flushed::add);
  c.flushReasoning();
  assertThat(flushed).isEmpty(); // 空缓冲不落库(A1:无思考不写行)
}
@Test void iteration_counter_starts_zero_increments() {
  StreamRunContext c = ctx(s -> {});
  assertThat(c.nextIteration()).isZero();
  assertThat(c.nextIteration()).isEqualTo(1);
}
@Test void active_clock_excludes_paused() {
  StreamRunContext c = ctx(s -> {});
  c.pauseClock(); /* 模拟人等待 */ c.resumeClock();
  assertThat(c.activeElapsedMs()).isLessThan(50); // 暂停区间不计(D-3)
}
@Test void perIterationMode_default_false() {
  assertThat(ctx(s -> {}).perIterationMode()).isFalse(); // 子任务/legacy after() 据此单写
}
```
辅助 `ctx(Consumer<String>)` 构造 StreamRunContext,memory/ack/iteration 用 no-op,stopSignal 返回 false。

- [ ] **Step 6: 跑确认失败 → 实现 StreamRunContext + MemoryWriter → 跑确认通过**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=StreamRunContextTest`
实现:reasoning 缓冲 `StringBuilder`(synchronized 或 ConcurrentLinkedQueue<String> 拼接);活跃计时用 `long activeStartNanos` + `long accumulatedActiveNanos` + `boolean paused`,pauseClock 累加当前活跃段,resumeClock 重置 activeStartNanos,activeElapsedMs = accumulated + (paused?0:now-activeStart)。perIterationMode AtomicBoolean。
Expected: PASS(5 测试)

- [ ] **Step 7: Commit**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/{SteeringInbox,MemoryWriter,StreamRunContext}.java spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/chat/{SteeringInboxTest,StreamRunContextTest}.java
git commit -m "feat(chat): SteeringInbox + StreamRunContext + MemoryWriter 续跑基础组件

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: ChatResponseRecord 新帧 + LoopProperty 配置

**Files:**
- Create: `.../model/SteerAck.java`, `.../model/IterationEvent.java`
- Modify: `.../model/ChatResponseRecord.java`
- Modify: `.../model/LoomAgentProperties.java`(加 LoopProperty,仿 AskUserProperty:431)
- Test: `.../model/ChatResponseRecordFrameTest.java`
- Test: `.../model/LoopPropertyBindingTest.java`

**Interfaces:**
- Consumes: 无(独立)。
- Produces:
  - `SteerAck(String messageId)`, `IterationEvent(int index)` records。
  - `ChatResponseRecord(String content, String reasoningContent, AskUserEvent askUser, SubTaskEvent subTaskEvent, SteerAck steerAck, IterationEvent iteration)` — 6 分量;**保留现有 2/3/4-arg 兼容构造器**(旧发送点源码不动),新增便捷构造器 `ChatResponseRecord(SteerAck)` / `ChatResponseRecord(IterationEvent)`。
  - `LoomAgentProperties.LoopProperty`: `int maxContinuations = 10`; `int runTimeoutSeconds = 1800`; getter/setter。`LoomAgentProperties.getLoop()` 返回非 null(默认实例)。

- [ ] **Step 1: 写失败测试 `ChatResponseRecordFrameTest`**

```java
@Test void legacy_2arg_still_null_new_fields() {
  ChatResponseRecord r = new ChatResponseRecord("hi", null);
  assertThat(r.steerAck()).isNull(); assertThat(r.iteration()).isNull();
  assertThat(r.content()).isEqualTo("hi"); // A1:旧帧形状不变
}
@Test void steerAck_frame() {
  ChatResponseRecord r = new ChatResponseRecord(new SteerAck("m1"));
  assertThat(r.steerAck().messageId()).isEqualTo("m1");
  assertThat(r.content()).isNull();
}
@Test void iteration_frame() {
  assertThat(new ChatResponseRecord(new IterationEvent(2)).iteration().index()).isEqualTo(2);
}
```

- [ ] **Step 2: 跑确认失败 → 实现 records + ChatResponseRecord 扩展 → 跑确认通过**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=ChatResponseRecordFrameTest`
实现:record 加 2 分量,所有旧构造器委托补 null;新便捷构造器其余分量 null。
Expected: PASS(3 测试)

- [ ] **Step 3: 写失败测试 `LoopPropertyBindingTest`**

```java
@Test void defaults() {
  LoomAgentProperties p = new LoomAgentProperties();
  assertThat(p.getLoop().getMaxContinuations()).isEqualTo(10);
  assertThat(p.getLoop().getRunTimeoutSeconds()).isEqualTo(1800);
}
@Test void binder_reads_loop_keys() {
  // MockEnvironment / Binder 绑 spring.ai.loom.agent.loop.max-continuations=3 → getLoop().getMaxContinuations()==3
}
```

- [ ] **Step 4: 跑确认失败 → 实现 LoopProperty + getLoop() → 跑确认通过**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=LoopPropertyBindingTest`
Expected: PASS(2 测试)

- [ ] **Step 5: 装库 + Commit**

```bash
mvn install -pl spring-ai-loom-agent -Dgpg.skip=true -DskipTests
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/{SteerAck,IterationEvent,ChatResponseRecord,LoomAgentProperties}.java spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/model/{ChatResponseRecordFrameTest,LoopPropertyBindingTest}.java
git commit -m "feat(model): steerAck/iteration 帧 + loop 护栏配置键

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: SseEmitterRegistry Entry 身份感知 + inbox 载体

**Files:**
- Modify: `.../stream/SseEmitterRegistry.java:25–97`
- Test: `.../stream/SseEmitterRegistryIdentityTest.java`

**Interfaces:**
- Consumes: `SteeringInbox`(Task 1)。
- Produces:
  - `Entry` record 加分量 `SteeringInbox inbox`(保留旧 4-arg 兼容构造器默认 new SteeringInbox())。
  - `register(...)` 签名不变,内部 put 新 Entry(含 inbox)。
  - `autoCleanup(String username, String conversationId, Entry expected)` — **身份感知重载**:仅当当前 Entry == expected 才 remove;旧 2-arg `autoCleanup` 保留委托(传 null 表示无条件,兼容现有调用点,但 SSE lambda 改用 3-arg)。
  - `boolean steer(String username, String conversationId, String messageId, String text)` — 取 Entry,inbox.offer;Entry 不存在或 closing → false。
  - `Entry get(...)` 不变。

- [ ] **Step 1: 写失败测试 `SseEmitterRegistryIdentityTest`**

```java
@Test void autoCleanup_does_not_remove_newer_entry() {
  SseEmitterRegistry r = new SseEmitterRegistry();
  Entry old = entry(); r.register("u", "c", old.emitter(), old.disposable(), old.onStop());
  Entry fresh = entry(); r.register("u", "c", fresh.emitter(), fresh.disposable(), fresh.onStop()); // 覆盖
  r.autoCleanup("u", "c", old); // 旧流 onComplete
  assertThat(r.get("u", "c")).isSameAs(fresh); // 新 run Entry 不被误删(#6)
}
@Test void steer_offers_to_active_inbox() {
  SseEmitterRegistry r = new SseEmitterRegistry(); Entry e = entry();
  r.register("u", "c", e.emitter(), e.disposable(), e.onStop());
  assertThat(r.steer("u", "c", "m1", "hi")).isTrue();
  assertThat(e.inbox().depth()).isEqualTo(1);
}
@Test void steer_false_when_no_run() {
  assertThat(new SseEmitterRegistry().steer("u", "c", "m1", "hi")).isFalse();
}
@Test void steer_false_after_stop() {
  // register → stop → steer == false(inbox closing)
}
```
辅助 `entry()` 构造 mock SseEmitter/Disposable/no-op onStop。

- [ ] **Step 2: 跑确认失败**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=SseEmitterRegistryIdentityTest`
Expected: FAIL(autoCleanup 3-arg / steer 不存在)

- [ ] **Step 3: 实现身份感知 + inbox + steer**

Entry 加 inbox 分量。autoCleanup(u,c,expected):`byConv.remove(c, expected)`(ConcurrentHashMap 条件 remove,引用相等)。stop():取出 Entry 后 `e.inbox().close()` 再 dispose/complete。steer():get Entry → null/closing 返回 false,否则 inbox.offer。
Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=SseEmitterRegistryIdentityTest`
Expected: PASS(4 测试)

- [ ] **Step 4: 装库 + Commit**

```bash
mvn install -pl spring-ai-loom-agent -Dgpg.skip=true -DskipTests
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/stream/SseEmitterRegistry.java spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/stream/SseEmitterRegistryIdentityTest.java
git commit -m "fix(stream): SseEmitterRegistry Entry 身份感知 + inbox 载体 + steer 入口

旧流 onComplete 不误删新 run Entry(#6 竞态);stop 关闭 inbox

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Phase 2 — 持久化与循环 advisor(S1/S2/S3 验证内嵌)

### Task 4: LoomMemoryAdvisor 替换 bean + Issue1 锁演进(S2)

**Files:**
- Create: `.../chat/LoomMemoryAdvisor.java`
- Modify: `LoomAgentConfiguration.java:537–576`(bean 类型 + chatClient 参数)
- Modify: `spring-ai-loom-agent-test/.../Issue1ChatMemoryAdvisorRegressionTest.java`
- Test: `.../chat/LoomMemoryAdvisorTest.java`

**Interfaces:**
- Consumes: `StreamRunContext`(Task 1), `MemoryWriter`, Spring AI `BaseChatMemoryAdvisor`/`ChatMemory`/`Message`。
- Produces: `LoomMemoryAdvisor implements BaseChatMemoryAdvisor`,构造 `(ChatMemory chatMemory)`,`getOrder()` = `Ordered.HIGHEST_PRECEDENCE + 200`。`before(ChatClientRequest, AdvisorChain)` 复刻 MessageChatMemoryAdvisor 去重读历史 + 写 turn-initial USER。`after(ChatClientResponse, AdvisorChain)`:**若 request context 含 StreamRunContext 且 perIterationMode → 跳过**(逐迭代已写);否则写单条 ASSISTANT(子任务/legacy,A1)。context key 常量 `StreamRunContext.CONTEXT_KEY = "loomStreamRunContext"`。

- [ ] **Step 1: 写失败测试 `LoomMemoryAdvisorTest`(mock ChatMemory)**

```java
@Test void order_upstream_of_tool_calling() {
  assertThat(new LoomMemoryAdvisor(mock(ChatMemory.class)).getOrder())
    .isLessThan(Ordered.HIGHEST_PRECEDENCE + 300);
}
@Test void implements_MemoryAdvisor() {
  assertThat(new LoomMemoryAdvisor(mock(ChatMemory.class))).isInstanceOf(MemoryAdvisor.class);
}
@Test void before_writes_turn_initial_user() {
  ChatMemory mem = mock(ChatMemory.class);
  LoomMemoryAdvisor a = new LoomMemoryAdvisor(mem);
  ChatClientRequest req = requestWithUser("hi", convId);
  a.before(req, mock(AdvisorChain.class));
  verify(mem).add(eq(convId), argThat(m -> m instanceof UserMessage));
}
@Test void after_writes_single_assistant_when_no_context() {
  // 子任务/legacy 路径:无 StreamRunContext → after 写一条 ASSISTANT(A1 延伸)
  ChatMemory mem = mock(ChatMemory.class);
  new LoomMemoryAdvisor(mem).after(responseWithAssistant("ok"), mock(AdvisorChain.class));
  verify(mem).add(eq(convId), argThat(list -> list.size() == 1));
}
@Test void after_skips_when_perIterationMode() {
  // context 含 StreamRunContext 且 markPerIterationMode → after 不写(防双写)
  ChatMemory mem = mock(ChatMemory.class);
  StreamRunContext c = ctx(); c.markPerIterationMode();
  ChatClientResponse resp = responseWithAssistantAndContext("ok", c);
  new LoomMemoryAdvisor(mem).after(resp, mock(AdvisorChain.class));
  verify(mem, never()).add(anyString(), anyList());
}
```

- [ ] **Step 2: 跑确认失败**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=LoomMemoryAdvisorTest`
Expected: FAIL(LoomMemoryAdvisor 不存在)

- [ ] **Step 3: 实现 LoomMemoryAdvisor**

复刻 `MessageChatMemoryAdvisor.before` 的 `isMemoryAlreadyInPrompt` 去重(从 2.0.2 sources 抄逻辑,不改语义)+ 写 lastUserOrToolResponseMessage。after:从 `chatClientResponse.context().get(CONTEXT_KEY)` 取 StreamRunContext,非 null 且 perIterationMode → return 原 response;否则写 aggregated assistant messages(同 MessageChatMemoryAdvisor.after)。adviseStream 用 BaseAdvisor 默认(before→nextStream→aggregator→after),**但 after 的跳过逻辑保证主对话不写合并行**。
Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=LoomMemoryAdvisorTest`
Expected: PASS(5 测试)

- [ ] **Step 4: 改 bean 装配 + Issue1 测试演进**

`LoomAgentConfiguration:573` bean 返回类型改 `MemoryAdvisor`,body `return new LoomMemoryAdvisor(chatMemory);`。`:538` chatClient 参数类型改 `MemoryAdvisor`。`:1210` 子任务注入已是 MemoryAdvisor(不动)。
Issue1 测试:`@Autowired @Qualifier("messageChatMemoryAdvisor") MemoryAdvisor advisor;` 删精确类名断言,改断言 `isInstanceOf(MemoryAdvisor.class)` + `getOrder() < HIGHEST_PRECEDENCE+300` + 类名**不是** `LastChunkMessageChatMemoryAdvisor`(防回潮)。javadoc 更新为"不变式锁:order 上游 + MemoryAdvisor 标记"。

- [ ] **Step 5: 装库+autoconfigure,跑 Issue1 + LoomMemoryAdvisor + 子任务存量测试**

```bash
mvn install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure -Dgpg.skip=true -DskipTests
mvn test -pl spring-ai-loom-agent-test -Dtest='Issue1ChatMemoryAdvisorRegressionTest,LoomMemoryAdvisorTest,DefaultSubTaskExecutorTest'
```
Expected: PASS。**变异自证**:把 LoomMemoryAdvisor order 改 +300 下游 ⇒ Issue1 order 断言红;after 去掉 perIterationMode 跳过 ⇒ 双写测试红。验毕还原。

- [ ] **Step 6: Commit**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/LoomMemoryAdvisor.java spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/{Issue1ChatMemoryAdvisorRegressionTest,chat/LoomMemoryAdvisorTest}.java
git commit -m "feat(chat): LoomMemoryAdvisor 逐迭代落库替换 final MessageChatMemoryAdvisor(D-1)

order +200 上游不变;after 无 StreamRunContext 时单写(子任务/legacy A1);
Issue1 锁改断言 order+MemoryAdvisor 不变式而非精确类名

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: LoomAgenticLoopAdvisor 骨架 + A1 退化 + S1 验证

**Files:**
- Create: `.../chat/LoomAgenticLoopAdvisor.java`
- Modify: `.../chat/DefaultChat.java:214–220`(逐请求挂载 + context param)
- Test: `.../chat/LoomAgenticLoopAdvisorNoOpTest.java`
- Test: `.../chat/SubTaskLoopExclusionTest.java`

**Interfaces:**
- Consumes: `StreamRunContext`(Task 1), `ToolCallingAdvisor`(Spring AI), `ToolCallingManager`, `ToolExecutionEligibilityChecker`。
- Produces: `LoomAgenticLoopAdvisor extends ToolCallingAdvisor`。builder `LoomAgenticLoopAdvisor.builder()`(仿 ToolCallingAdvisor.builder,默认 order=DEFAULT_ORDER,conversationHistoryEnabled=true)。本 task 仅骨架:覆写 `doGetNextInstructionsForToolCall(+Stream)` 与 `doFinalizeLoop(+Stream)` **全部先委托 super**(inbox 空退化)。从 `chatClientRequest.context().get(StreamRunContext.CONTEXT_KEY)` 取 context;null → 纯 super(A1/子任务)。
- **context 接线(钉死,不留 TBD)**:`StreamRunContext` 由 **SSE lambda 创建**(它持有 emitterRegistry Entry / chatUsageService / chatMemory 全部绑定源),经 `request.setAttribute(StreamRunContext.CONTEXT_KEY, ctx)` 传入;`DefaultChat.stream(record,username,request)` 读 `request.getAttribute(CONTEXT_KEY)`,非 null 时 `requestSpec.advisors(a -> a.param(CONTEXT_KEY, ctx))`。**IChat.stream 签名不变**(消费方可替换接口不受影响);attribute 缺席(自定义 IChat / 非 SSE 路径)→ context null → loop advisor 纯 super。本 task DefaultChat 先加 loop advisor + 读 attribute(此时 lambda 尚未创建 ctx,attribute 恒 null → 纯 super,A1 保持);Task 6 补 lambda 创建 ctx。

- [ ] **Step 1: 写失败测试 `LoomAgenticLoopAdvisorNoOpTest`**

```java
@Test void order_is_tool_calling_default() {
  assertThat(LoomAgenticLoopAdvisor.builder().build().getOrder())
    .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 300);
}
@Test void empty_inbox_finalize_delegates_super_no_continuation() {
  // mock chain:doFinalizeLoop 返回的 response 不含续跑重入;inbox 空 → chain.copy 不被调用
  // 断言:无 steering 时 adviseCall 的模型调用次数 == super 行为(A1)
}
@Test void null_context_pure_super() {
  // request context 无 StreamRunContext → 钩子全委托 super(子任务路径)
}
```

- [ ] **Step 2: 跑确认失败 → 实现骨架(builder + 覆写委托 super + context 读取)→ 跑确认通过**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=LoomAgenticLoopAdvisorNoOpTest`
Expected: PASS(3 测试)

- [ ] **Step 3: 写失败测试 `SubTaskLoopExclusionTest`(A5/E1)**

```java
@Test void subtask_advisor_chain_has_no_loop_advisor() {
  // 构造 DefaultSubTaskExecutor 的 spec(复用主 ChatClient),断言其 advisors 不含 LoomAgenticLoopAdvisor
  // 因 loop advisor 只经 DefaultChat requestSpec 挂载,非 defaultAdvisor
}
```
@SpringBootTest 或 mock ChatClient 捕获 advisors 列表。

- [ ] **Step 4: DefaultChat 逐请求挂载 loop advisor(骨架)+ 跑 SubTaskLoopExclusion + 存量聊天测试**

DefaultChat.stream 在 `requestSpec.advisors(new ChatUsageRecordingAdvisor(...))` 附近加 `requestSpec.advisors(LoomAgenticLoopAdvisor.builder().build())`。context 接线暂用 null(纯 super),Task 6 补。
```bash
mvn install -pl spring-ai-loom-agent -Dgpg.skip=true -DskipTests
mvn test -pl spring-ai-loom-agent-test -Dtest='SubTaskLoopExclusionTest,LoomAgenticLoopAdvisorNoOpTest'
```
Expected: PASS。**S1 验证**:此步同时确认 auto-register 抑制——若 Spring AI 仍自动注册默认 ToolCallingAdvisor 导致双 tool advisor,测试会暴露(模型轮次翻倍/帧重复);若失败,退 spec §4.1 方案(b)独立外层 advisor,记录结论。**变异**:把 loop advisor 改 chatClient.defaultAdvisors ⇒ SubTaskLoopExclusion 红。验毕还原。

- [ ] **Step 5: Commit**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/{LoomAgenticLoopAdvisor,DefaultChat}.java spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/chat/{LoomAgenticLoopAdvisorNoOpTest,SubTaskLoopExclusionTest}.java
git commit -m "feat(chat): LoomAgenticLoopAdvisor 骨架逐请求挂载 + A1 退化 + 子任务排除(S1)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 6: StreamRunContext 接线 SSE lambda + reasoning 按迭代 flush(S3)

**Files:**
- Modify: `LoomAgentConfiguration.java:617–760`(SSE lambda)
- Modify: `.../chat/DefaultChat.java`(context 传递)
- Modify: `.../chat/LoomAgenticLoopAdvisor.java`(迭代边界回调 flush/emit)
- Test: `.../chat/ReasoningPerIterationTest.java`
- Test: `.../chat/UsageNoDoubleCountTest.java`

**Interfaces:**
- Consumes: Task 1–5 全部。
- Produces: SSE lambda 构造 `StreamRunContext`(绑定 `ChatMemory`→MemoryWriter、`chatUsageService::saveReasoning`→reasoningFlush、`entry.emitter().send(new ChatResponseRecord(steerAck/iteration))`→emitters、`entry.disposable().isDisposed()`→stopSignal),**经 `request.setAttribute(StreamRunContext.CONTEXT_KEY, ctx)` 传入**(Task 5 钉死的接线);DefaultChat 读 attribute → advisor param,lambda 持同一引用做 reasoning flush。loop advisor 在每外层迭代结束调 `ctx.flushReasoning()` + `ctx.memory().writeAssistant(该迭代文本)` + `ctx.markPerIterationMode()`;续跑开始调 `ctx.emitIteration(idx)` + reset delta。

- [ ] **Step 1: 写失败测试 `ReasoningPerIterationTest`**

```java
@Test void multi_iteration_writes_one_reasoning_row_per_iteration() {
  // 模拟 2 迭代 run,每迭代有思考 → saveReasoning 被调 2 次,各含该迭代文本(非合并)
}
@Test void single_iteration_no_steering_writes_one_row_like_today() {
  // A1:无 steering 单迭代(含多工具轮)→ saveReasoning 恰 1 次,内容=整轮累积(与改造前同)
}
@Test void empty_reasoning_writes_no_row() {
  // 无思考 → saveReasoning 0 次
}
```
mock ChatUsageService 捕获 saveReasoning 调用;构造 StreamRunContext + loop advisor 驱动迭代边界。

- [ ] **Step 2: 跑确认失败 → 实现 lambda 接线 + loop advisor 迭代回调 → 跑确认通过**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=ReasoningPerIterationTest`
关键算法(reasoning delta 复位):lambda 的 `lastReasoningLen` 移入 StreamRunContext;loop advisor 续跑开始时 `ctx.resetReasoningDelta()`(lastReasoningLen 归零)+ `ctx.flushReasoning()`(上一迭代缓冲落库)。lambda 每帧 delta 计算改读 ctx 状态。
Expected: PASS(3 测试)

- [ ] **Step 3: 写失败测试 `UsageNoDoubleCountTest`(S3)**

```java
@Test void usage_rows_equal_model_rounds_no_double() {
  // 2 迭代各 1 模型轮 → loom_chat_usage 2 行(order=100 下游逐轮重入);无 UsageAccumulator 双计
}
```
mock ChatUsageService.record 计数;驱动 chain.copy 重入验证下游 usage advisor 被逐轮调用。

- [ ] **Step 4: 跑确认通过(S3 验证)+ 存量 ChatReasoningHistoryTest/ChatProviderTimeoutWiringTest 绿**

```bash
mvn install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure -Dgpg.skip=true -DskipTests
mvn test -pl spring-ai-loom-agent-test -Dtest='ReasoningPerIterationTest,UsageNoDoubleCountTest,ChatReasoningHistoryTest,ChatProviderTimeoutWiringTest'
```
Expected: PASS。若 S3 发现双计 ⇒ 退 spec §5.4 退路(落库移入 loop advisor 收口 + record 幂等键),记录结论。**变异**:reasoning 改回整条流 onComplete 单 flush ⇒ multi_iteration 测试红。验毕还原。

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(chat): StreamRunContext 接线 SSE lambda + reasoning 按外层迭代落库(S3)

usage 逐轮重入无双计;无 steering 单迭代行为与改造前一致(A1)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 7: steering 注入 + 续跑 + steer 端点 + 护栏 + 断连 dispose(S5)

**Files:**
- Modify: `.../chat/LoomAgenticLoopAdvisor.java`(内层 drain + 外层续跑 + 护栏)
- Modify: `LoomAgentConfiguration.java:776` 附近(steer 端点)、`:657–672`(断连 dispose)
- Modify: `.../chat/DefaultChat.java`(steering 引导句)
- Test: `.../chat/SteeringInjectionTest.java`
- Test: `.../chat/ContinuationGuardrailTest.java`
- Test: `.../chat/SteerEndpointTest.java`
- Test: `.../stream/DisconnectDisposeIT.java`

**Interfaces:**
- Consumes: Task 1–6。
- Produces:
  - 内层 `doGetNextInstructionsForToolCall(+Stream)`:super 结果 + drain inbox → 每条 `ctx.memory().writeUser(new UserMessage(text))` + append instructions + `ctx.emitAck(messageId)` + 写审计(Task 9 接线,本 task 先留 hook)。**注入位置断言:在 tool_result 之后**(A4)。
  - 外层 `doFinalizeLoop(+Stream)`:`ctx.inbox().closeAndRemaining()` 原子收口——若 drain 非空且护栏未触 → 续跑(`chain.copy(this).nextCall/nextStream`,迭代+1,emitIteration);空 → super 收口。护栏:`iteration >= maxContinuations` 或 `ctx.activeElapsedMs() >= runTimeoutSeconds*1000` 或 `ctx.isStopped()` → 收口,残留 inbox 经 ack 帧标"未注入"终态。
  - steer 端点 `POST /spring/ai/loom/stream/{conversationId}/steer` body `{message}`:归属校验(镜像 stop:777)+ 8KB 上限(400)+ `emitterRegistry.steer(...)` → `{queued:true,messageId}` 或 `{queued:false}`;depth≥20 → 429。
  - 断连:`emitter.onTimeout/onCompletion/onError` 补 `entry.disposable().dispose()` + `entry.inbox().close()` + 身份感知 autoCleanup。

- [ ] **Step 1: 写失败测试 `SteeringInjectionTest`**

```java
@Test void inject_after_tool_result_before_next_model_call() {
  // 消息序列断言:[...assistant(tool_call), tool_result, USER(steering), ...] —— USER 在 tool_result 后(A4)
}
@Test void emitAck_on_injection() {
  // drain 注入 → ackEmitter 收到对应 messageId
}
@Test void writes_steering_user_row_via_memory() {
  // ctx.memory().writeUser 被调,文本=steering 原文
}
@Test void empty_inbox_no_injection_no_ack() {
  // A1:inbox 空 → 指令序列与 super 完全一致,无 ack
}
```

- [ ] **Step 2: 跑确认失败 → 实现内层 drain → 跑确认通过**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=SteeringInjectionTest`
Expected: PASS(4 测试)。**变异**:去掉内层 drain ⇒ inject 测试红。

- [ ] **Step 3: 写失败测试 `ContinuationGuardrailTest`**

```java
@Test void finalize_nonempty_inbox_continues() {
  // inbox 有消息 → chain.copy 重入,迭代+1,emitIteration
}
@Test void finalize_empty_inbox_ends() {
  // A1/G2:空 → 不续跑
}
@Test void max_continuations_stops() {
  // iteration==10 → 收口,残留可见
}
@Test void run_timeout_excludes_human_wait() {
  // pauseClock 区间不计;activeElapsed>=1800s → 收口(D-3)
}
@Test void stop_signal_ends_at_boundary() {
  // ctx.isStopped → 收口
}
@Test void finalize_race_offer_during_close_no_loss() {
  // 并发:closeAndRemaining 与 offer 竞态 → 要么入队被续跑消费,要么 offer 返回 false(前端回退),无第三态(A2/Review Focus 2)
}
```

- [ ] **Step 4: 跑确认失败 → 实现外层续跑 + 护栏 + 收口 CAS → 跑确认通过**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=ContinuationGuardrailTest`
关键算法(收口竞态):`closeAndRemaining()` 用 `closing.compareAndSet(false,true)` 后 drain;offer 先查 closing。finalize 在 chain 重入前调 closeAndRemaining,非空则**重新 open**(新迭代 inbox)或续跑用新 inbox——builder 定,倾向每迭代新 inbox,closeAndRemaining 残留注入当前续跑迭代。
Expected: PASS(6 测试)。**变异**:finalize 不查 inbox ⇒ continuation 测试红。

- [ ] **Step 5: 写失败测试 `SteerEndpointTest`(mock registry + 归属)**

```java
@Test void steer_queued_when_active() { /* {queued:true,messageId} */ }
@Test void steer_false_when_no_run() { /* {queued:false} */ }
@Test void steer_400_over_8kb() { /* 8193 字符 → 400 */ }
@Test void steer_429_inbox_full() { /* depth 20 → 429(Review Focus 5) */ }
@Test void steer_403_cross_user() { /* 会话不属于 caller → 拒绝(镜像 stop 归属) */ }
@Test void steer_emoji_roundtrip() { /* "🚀部署" 经 steer → inbox 文本完整(Review Focus 5) */ }
```

- [ ] **Step 6: 实现 steer 端点 + 跑确认通过**

@RestController 加 `@PostMapping("/spring/ai/loom/stream/{conversationId}/steer")`,归属校验复用 stop 的模式(UserContextHolder + userConversation.exists),8KB/429 守卫,调 emitterRegistry.steer。
Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=SteerEndpointTest`
Expected: PASS(6 测试)

- [ ] **Step 7: 写失败 IT `DisconnectDisposeIT`(A8/Review Focus 3,S5)**

```java
@Test void disconnect_disposes_subscription_no_orphan_writes() {
  // 启动 run → 模拟 emitter onError/断连 → 断言 disposable.isDisposed() + inbox.isClosing()
  // + 断连后 ChatMemory/ChatUsageService 无新写入(orphan 不跑)
}
```
@SpringBootTest + 真实 SSE lambda 路径或 mock emitter 触发 lifecycle 回调。

- [ ] **Step 8: 实现断连 dispose + 跑 IT + 装模块**

`onTimeout/onCompletion/onError` 补 dispose + inbox.close + 身份感知 autoCleanup(u,c,entry)。S5 验证:autoCleanup 原不 dispose,现补。
```bash
mvn install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure -Dgpg.skip=true -DskipTests
mvn test -pl spring-ai-loom-agent-test -Dtest='DisconnectDisposeIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。**变异**:去掉断连 dispose ⇒ orphan 测试红。

- [ ] **Step 9: DefaultChat steering 引导句 + Commit**

buildDynamicSystemPrompt 增补:"运行中用户补充的消息具有最高优先级,先回应补充再继续原任务"(措辞进契约测试,Task 15 锁)。
```bash
git add -A
git commit -m "feat(chat): steering 内层注入 + inbox 驱动续跑 + 护栏 + steer 端点 + 断连 dispose(S5)

A4 注入位置/tool_result 后;D-3 活跃计时排除人等待;收口 CAS 无丢消息;
8KB/429/归属守卫;断连 dispose 防 orphan

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Phase 3 — askUser 即时 dismiss(D-2)

### Task 8: DISMISS 哨兵 + 工具中性返回 + answer 端点 mode + 契约锁

**Files:**
- Modify: `.../askuser/AskUserRegistry.java`(DISMISS_SENTINEL)
- Modify: `.../askuser/DefaultAskUserTool.java:167–170`
- Modify: `LoomAgentConfiguration.java:2016–2048`(answer 路由 mode 分支)
- Test: `.../askuser/AskUserDismissTest.java`
- Modify/Test: `.../askuser/AskUserGuidanceContractTest.java`(扩展)

**Interfaces:**
- Consumes: AskUserRegistry.answer(qid,user,text)(现有)。
- Produces: `AskUserRegistry.DISMISS_SENTINEL = "__ASKUSER_DISMISS__"`(区别于 CANCELLED_SENTINEL)。answer 端点 body 加 `mode`:`"DISMISS"` → 调 `askUserRegistry.answer(qid,user,DISMISS_SENTINEL)`(无需 answer 文本);缺省/`"ANSWER"` → 现有逻辑。DefaultAskUserTool:`DISMISS_SENTINEL.equals(answer)` → 返回 spec §5.3 原文中性文本(非 is_error)。

- [ ] **Step 1: 写失败测试 `AskUserDismissTest`**

```java
@Test void dismiss_returns_neutral_non_error_text() {
  // future.complete(DISMISS_SENTINEL) → 工具返回含"等待用户补充",不含"用户未作答"/"继续",非 is_error(A3)
}
@Test void dismiss_removes_pending_no_keepalive() {
  // dismiss 后 registry.get(qid)==null(即 complete+remove,无保活)
}
@Test void dismiss_vs_timeout_cas_first_wins() {
  // 并发 complete(DISMISS) 与超时哨兵 → 先到先得,后到 answer 返回 false(404),不重复处理(Review Focus 4)
}
@Test void dismiss_vs_option_click_cas() {
  // dismiss 与选项答案竞态 → 一个成功一个 404
}
@Test void answer_endpoint_dismiss_mode_no_text_required() {
  // body {mode:"DISMISS"} 无 answer 字段 → 200 ok(不要求文本)
}
@Test void answer_endpoint_default_mode_unchanged() {
  // body {answer:"x"} 无 mode → 现有 ANSWER 逻辑(A1)
}
```

- [ ] **Step 2: 跑确认失败 → 实现哨兵 + 工具返回 + 端点 mode → 跑确认通过**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=AskUserDismissTest`
DefaultAskUserTool 返回文本(spec §5.3 原文):`[用户想先聊聊] 用户选择暂不回答以上问题,想先自由讨论。请等待用户接下来的补充消息(将在下一轮送达),不要自行猜测答案或强行推进原问题。`
Expected: PASS(6 测试)

- [ ] **Step 3: 扩展 AskUserGuidanceContractTest + 变异自证**

加断言:dismiss 返回文本含"等待用户补充"、不含"用户未作答"、不含"继续"指令、非 is_error 路径。
**变异**:把 DISMISS 分支改走 CANCELLED_SENTINEL/超时路径 ⇒ 契约红;改返回 is_error ⇒ 红。验毕还原。
Run: `mvn test -pl spring-ai-loom-agent-test -Dtest='AskUserDismissTest,AskUserGuidanceContractTest'`
Expected: PASS

- [ ] **Step 4: 装模块 + Commit**

```bash
mvn install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure -Dgpg.skip=true -DskipTests
git add -A
git commit -m "feat(askuser): 聊聊这个即时 dismiss(D-2)——非错误中性返回,无保活

DISMISS_SENTINEL 即 complete+remove;answer 端点 mode 分支;
契约锁断言中性措辞,变异走 cancel/is_error 即红(A3,CC #33511 反面教材)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Phase 4 — 审计表 + admin 流水 + 历史回放一致性

### Task 9: loom_chat_steering 审计表 + 仓储 + 注入接线(S4)

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/db/migration/V1.0__init.sql`(末尾新增段)
- Create: `.../tool/ILoomChatSteeringRepository.java`, `.../tool/JdbcLoomChatSteeringRepository.java`, `.../model/SteeringAuditRow.java`
- Modify: `.../chat/LoomAgenticLoopAdvisor.java`(注入时写审计)
- Modify: `LoomAgentConfiguration.java`(仓储 bean)
- Test: `.../tool/LoomChatSteeringRepositoryTest.java`

**Interfaces:**
- Consumes: Task 7 注入点。
- Produces: `SteeringAuditRow(long id, String conversationId, String username, String messageText, Instant injectedAt, int seq)`。`ILoomChatSteeringRepository`: `void record(String convId, String username, String text, Instant injectedAt, int seq)`; `List<SteeringAuditRow> findByConversationId(String convId)`。`JdbcLoomChatSteeringRepository(JdbcTemplate)` + `ensureSchema()`(幂等 CREATE TABLE IF NOT EXISTS,仿 JdbcLoomScheduleExecutionRepository)。loop advisor 注入每条 steering 时调 record。

- [ ] **Step 1: 写失败测试 `LoomChatSteeringRepositoryTest`(H2)**

```java
@Test void roundtrip_emoji_text() {
  // record("🚀部署dev分支") → findByConversationId 文本完整(CLOB 多字节,Review Focus 5)
}
@Test void ensureSchema_idempotent() {
  // 调两次 ensureSchema 不抛(幂等)
}
@Test void seq_ordering() { /* 同 conv 多条按 seq 排序 */ }
```

- [ ] **Step 2: 跑确认失败 → V1.0 建表段 + 仓储实现 + bean + 注入接线 → 跑确认通过**

V1.0 末尾加 spec §5.9 的 CREATE TABLE loom_chat_steering 段。仓储 ensureSchema + record + find。loop advisor 内层 drain 注入时 `steeringRepo.record(convId, username, text, Instant.now(), seq)`。
```bash
mvn install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure -Dgpg.skip=true -DskipTests
mvn test -pl spring-ai-loom-agent-test -Dtest=LoomChatSteeringRepositoryTest
```
Expected: PASS(3 测试)。清库启动验证 V1.0 新段无语法错。

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(tool): loom_chat_steering 审计表 + 仓储 + 注入接线(S4)

V1.0 新增段 + ensureSchema 幂等;emoji CLOB round-trip;为 admin 流水 B2 豁免提供确定性标记

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 10: ConversationFlowService B2 豁免 + 历史端点 ts/interleave

**Files:**
- Modify: `.../chat/ConversationFlowService.java:116–147`(B2 豁免)
- Modify: `LoomAgentConfiguration.java:2508–2538`(历史端点 Message 附 ts + interleave)
- Modify: `.../chat/ToolCallHistory.java`(interleave 辅助)
- Test: `.../chat/ConversationFlowSteeringTimelineTest.java`
- Test: `.../chat/HistoryInterleaveTest.java`

**Interfaces:**
- Consumes: Task 9 审计仓储。
- Produces: ConversationFlowService B2 重写时,USER 行若匹配审计表(convId + content + injectedAt 就近)→ **豁免**(保留真实 ts);仅 turn-initial USER 沿用 createdAt 锚定。历史端点:Message 条目包装附 `timestamp`(JDBC 取,仿 ChatReasoningHistory)+ TOOL_CALL 按 ts 服务端 interleave 进正确位置(不再 addAll 末尾)。

- [ ] **Step 1: 写失败测试 `ConversationFlowSteeringTimelineTest`**

```java
@Test void steering_user_keeps_injection_ts_not_createdAt() {
  // 多轮 + 中段插话:steering USER 事件 ts == injectedAt,不被拉回 createdAt(A7/G7)
}
@Test void turn_initial_user_still_anchored() {
  // 非 steering 的 turn-initial USER 仍按 createdAt 锚定(原 B2 修复不回归)
}
@Test void finalize_continuation_injection_matched() {
  // S4:前序是 ASSISTANT(续跑注入)而非 TOOL → 审计匹配仍正确,不倒序
}
@Test void timeline_sorted_no_inversion() {
  // 插话 USER 在其后续 tool_call 之前、前序 ASSISTANT 之后
}
```

- [ ] **Step 2: 跑确认失败 → 实现 B2 豁免 → 跑确认通过**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=ConversationFlowSteeringTimelineTest`
关键算法(豁免匹配):B2 循环前查审计表得 steering 行集合;对每个 USER 事件,若存在审计行 content 相等且 |ts - injectedAt| 最小匹配 → 跳过重写。turn-initial 判定:无匹配审计行的 USER 沿用原逻辑。
Expected: PASS(4 测试)。**变异**:去掉豁免 ⇒ steering_user 测试红。

- [ ] **Step 3: 写失败测试 `HistoryInterleaveTest`**

```java
@Test void tool_call_interleaved_by_ts_not_appended() {
  // 2 迭代 run:迭代1 的 askUser TOOL_CALL 排在迭代1 ASSISTANT 附近,而非列表末尾
}
@Test void message_entries_carry_timestamp() {
  // 历史响应 Message 条目含 timestamp 字段(additive)
}
@Test void legacy_no_steering_order_unchanged() {
  // A1:无 steering 单迭代 → 顺序与改造前一致
}
```

- [ ] **Step 4: 实现历史端点 ts + interleave + 跑确认通过**

历史端点:查 chat_memory 行 ts(JDBC),Message 包装成含 timestamp 的 Map(或 additive 字段);ToolCallHistory.inject 结果按 createdAt 与 Message ts 归并排序(服务端)。renderMessages 顺序渲染即归位(Task 14 前端配合)。
```bash
mvn install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure -Dgpg.skip=true -DskipTests
mvn test -pl spring-ai-loom-agent-test -Dtest='ConversationFlowSteeringTimelineTest,HistoryInterleaveTest'
```
Expected: PASS(7 测试)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(chat): admin 流水 B2 豁免 steering USER + 历史端点 ts/TOOL_CALL interleave

修复中段插话被拉回 createdAt 致时间线倒序(A7);Message additive ts,
TOOL_CALL 服务端归位取代 addAll 末尾(取代旧形状冻结裁定)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Phase 5 — 前端对话模式

### Task 11: 输入三态机 + steer send + 灰态气泡

**Files:**
- Modify: `META-INF/resources/spring/ai/loom/app.js`(state.inputMode、chat.send 分支、steer、灰态渲染)
- Modify: `style.css`(灰态气泡样式)
- Test: `spring-ai-loom-agent-test/.../browser/SteeringInputBrowserIT.java`

**Interfaces:**
- Consumes: Task 7 steer 端点 `{queued,messageId}`、Task 2 steerAck 帧。
- Produces: `state.inputMode` ∈ {IDLE,RUNNING,ASK_PENDING};`state.isStreaming` 语义不变(8 消费点守卫仍生效)。RUNNING 态回车 → `api.steer(convId,text)` → 渲染灰态用户气泡(data-message-id)→ queued:false 时**等本地流 complete 事件**再 chat.send(#6);steerAck 帧 → 灰态转正。灰态气泡 textContent 转义(Review Focus 1)。

- [ ] **Step 1: 写失败 BrowserIT `SteeringInputBrowserIT`**

```java
@Test void running_textarea_enabled_send_steers() {
  // 发消息进入 RUNNING → textarea 不 disabled → 再输入回车 → POST /steer(拦截请求断言)→ 灰态气泡出现
}
@Test void steerAck_promotes_gray_bubble() {
  // 模拟 steerAck 帧 → 灰态气泡转正(去 gray class)
}
@Test void queued_false_waits_local_complete_then_sends() {
  // mock steer 返回 queued:false → 不立即抢发,等流 complete 后 chat.send(#6)
}
@Test void steering_html_escaped() {
  // 输入 "<img src=x onerror=alert(1)>" → 灰态气泡 innerHTML 无 active img(Review Focus 1)
}
@Test void isStreaming_guards_still_block_switch() {
  // RUNNING 态点侧栏切换 → 仍被拦(isStreaming 语义不变,A2)
}
@Test void stop_marks_gray_unsent() {
  // 有灰态时点 stop → 灰态标"未发送(已停止)"(A2 可见终态)
}
```

- [ ] **Step 2: 跑确认失败**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=SteeringInputBrowserIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL(inputMode/steer 未实现)

- [ ] **Step 3: 实现 inputMode + steer send + 灰态气泡 + ack 转正**

app.js:state 加 inputMode;disableSend/enableSend 改为按 inputMode 设置 textarea(工具栏/侧栏锁仍随 isStreaming);chat.send 在 RUNNING 态走 api.steer;新增 api.steer;SSE 回调处理 data.steerAck → 灰态转正;灰态气泡 renderQueuedUser(messageId,text) 用 textContent。queued:false 回退挂到流 complete 回调。
Run: 同 Step 2。Expected: PASS(6 测试)

- [ ] **Step 4: 同步改 ChatSmoke/HttpTool IT 开始信号(spec §5.5 裁定)**

`ChatSmokeBrowserIT:51–64`、`HttpToolBrowserIT:152–159` 流开始信号从 `send-btn.disabled===true` 改为 `stop-btn 可见 && state.isStreaming`;结束信号不变。
Run: `mvn test -pl spring-ai-loom-agent-test -Dtest='ChatSmokeBrowserIT,HttpToolBrowserIT' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(frontend): 输入三态机 + steering 发送 + 灰态排队气泡

RUNNING 解锁输入,回车入 inbox 灰态可见,ack 转正;queued:false 等本地 complete 回退;
stop 标未发送;isStreaming 守卫不变;ChatSmoke/HttpTool 开始信号契约演进

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 12: 事件驱动气泡 + 迭代分段 + 思考分段

**Files:**
- Modify: `app.js`(steerAck 开新气泡、iteration 帧分段、思考分段)
- Modify: `style.css`(迭代分隔线)
- Test: `.../browser/EventBubbleBrowserIT.java`

**Interfaces:**
- Consumes: Task 11 灰态转正、Task 2 iteration 帧、Task 6 reasoning 按迭代。
- Produces: steerAck 到达 → 当前 AI 气泡封口(操作栏落地)→ 灰态用户气泡转正 → `renderBotMessage` 新气泡 + `_currentAnswerEl` 重定向。iteration 帧 → 同气泡轻量分隔线 + 新写作分段 + 新思考折叠分段(reasonText 重置)。

- [ ] **Step 1: 写失败 BrowserIT `EventBubbleBrowserIT`**

```java
@Test void steering_splits_new_ai_bubble() {
  // 插话 ack 后:旧 AI 气泡封口 + 用户气泡 + 新 AI 气泡,时序正确(G4)
}
@Test void pure_iteration_same_bubble_segment() {
  // iteration 帧无插话 → 同一气泡内分隔线 + 新分段,不开新气泡
}
@Test void thinking_segment_per_iteration() {
  // 2 迭代各有思考 → 2 个思考折叠分段,内容不合并(A5)
}
@Test void tool_chips_stay_in_correct_bubble() {
  // 插话前的工具 chip 留旧气泡,插话后的留新气泡
}
```

- [ ] **Step 2: 跑确认失败 → 实现事件驱动气泡 + 分段 → 跑确认通过**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=EventBubbleBrowserIT -Dsurefire.failIfNoSpecifiedTests=false`
复用 `_currentAnswerEl`/`renderBotMessage`/askuser-bot-content 分段先例。steerAck 回调:封口旧气泡(actions 显示)→ 新 renderBotMessage → 重定向 _currentAnswerEl/_currentOriginEl/_currentActionsEl。iteration 回调:append 分隔线 div + 新 answer 分段 + reasonText="" + 新 thinking 容器。
Expected: PASS(4 测试)

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(frontend): 事件驱动气泡拆分 + 迭代分段 + 思考按迭代分段(G4)

插话 ack 开新气泡,纯迭代同气泡分隔;复用 _currentAnswerEl 分段机制

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 13: askUser dismiss 按钮 + 第五终态 + 提示条

**Files:**
- Modify: `app.js`(askUserCards 加 dismiss 按钮、freeze 第五终态、提示条、ASK_PENDING 态)
- Modify: `tool-call-renderer.js`(只读卡片"转入对话"徽章)
- Modify: `style.css`
- Test: `.../browser/AskUserDismissBrowserIT.java`

**Interfaces:**
- Consumes: Task 8 DISMISS 端点、Task 11 inputMode。
- Produces: 卡片恒有"💬 聊聊这个"按钮 → POST answer mode=DISMISS → freeze"💬 转入对话"(第五终态,摘要折叠,不可逆无返回选项)→ inputMode 回 RUNNING(多卡则仍 ASK_PENDING)。ASK_PENDING 态 textarea 锁 + composer 上方提示条("AI 正在等你回答 · m:ss · ↓定位",点击滚动到卡片,多卡计数)。tool-call-renderer 历史只读卡片加"转入对话"徽章。

- [ ] **Step 1: 写失败 BrowserIT `AskUserDismissBrowserIT`**

```java
@Test void dismiss_button_freezes_and_returns_running() {
  // askUser 卡片 → 点"聊聊这个" → POST mode=DISMISS → 卡片"转入对话"终态 → textarea 解锁回 RUNNING
}
@Test void dismiss_then_steering_continues() {
  // dismiss 后输入回车 → steer → 续跑(G3 衔接)
}
@Test void hint_bar_scrolls_to_card() {
  // ASK_PENDING 提示条点击 → 滚动到卡片
}
@Test void multi_card_stays_pending_until_all_resolved() {
  // 2 卡 pending,dismiss 第一张 → 仍 ASK_PENDING;全落定才 RUNNING(B8)
}
@Test void dismiss_double_click_no_double_freeze() {
  // 快速双击 → 一次 DISMISS,第二次 404 不重复 freeze/不 toast 刷屏(Review Focus 4)
}
@Test void history_readonly_card_shows_dismiss_badge() {
  // 刷新后历史只读卡片显示"转入对话"徽章(B5)
}
```

- [ ] **Step 2: 跑确认失败 → 实现 dismiss 按钮 + 终态 + 提示条 + 徽章 → 跑确认通过**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=AskUserDismissBrowserIT -Dsurefire.failIfNoSpecifiedTests=false`
askUserCards.render 加 dismiss 按钮;freeze 加第五终态分支;inputMode 切 ASK_PENDING/RUNNING;提示条 DOM + 倒计时(复用卡片现有 timer);tool-call-renderer 加徽章。
Expected: PASS(6 测试)

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(frontend): askUser 聊聊这个即时 dismiss + 转入对话终态 + 提示条(D-2/G3)

点击即 DISMISS 冻结卡片回 RUNNING,无保活;多卡计数;历史只读徽章;双击 CAS 防重

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 14: 历史回放气泡分组 + XSS 守卫

**Files:**
- Modify: `app.js`(renderMessages 按 USER 行分组 + 服务端 interleave 顺序渲染)
- Test: `.../browser/HistoryReplayBrowserIT.java`

**Interfaces:**
- Consumes: Task 10 历史端点 ts/interleave、Task 4 逐迭代 memory 行。
- Produces: renderMessages 按响应顺序渲染(服务端已 interleave),USER 行开用户气泡 + 新 AI 气泡,ASSISTANT 行填当前 AI 气泡,TOOL_CALL 挂当前 AI 气泡(顺序即正确归位)。steering USER 行 → 独立用户气泡(与 live 事件驱动分组一致,A6/G5)。所有文本 textContent/esc(Review Focus 1)。

- [ ] **Step 1: 写失败 BrowserIT `HistoryReplayBrowserIT`**

```java
@Test void replay_bubble_grouping_matches_live() {
  // 2 迭代 + 中段插话 run → 刷新 → 气泡分组与 live 一致:用户/AI/用户(插话)/AI(A6/G5)
}
@Test void tool_call_in_correct_bubble_after_replay() {
  // 迭代1 askUser 卡片回放在迭代1 气泡,非末尾气泡(#3)
}
@Test void thinking_per_iteration_after_replay() {
  // 每迭代思考分段回放正确(±10s 配对,Task 4/6)
}
@Test void steering_html_inert_after_replay() {
  // 历史 steering USER 行含 <img onerror> → inert(Review Focus 1)
}
@Test void legacy_single_turn_replay_unchanged() {
  // A1:无 steering 旧会话回放与改造前一致
}
```

- [ ] **Step 2: 跑确认失败 → 实现 renderMessages 分组 → 跑确认通过**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=HistoryReplayBrowserIT -Dsurefire.failIfNoSpecifiedTests=false`
renderMessages 现有逻辑已按 USER/ASSISTANT/TOOL_CALL 分派;确认服务端 interleave 后顺序渲染即归位(_lastBotBubble 跟随当前 AI 气泡)。steering USER 行天然开新气泡。
Expected: PASS(5 测试)

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(frontend): 历史回放按 USER 行分组 + TOOL_CALL 归位 + XSS 守卫(A6/G5)

服务端 interleave 后顺序渲染即正确气泡分组,与 live 一致;steering 文本转义

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Phase 6 — 回归门 + 基线 + 文档核销

### Task 15: 契约锁补全 + 全量回归 + 变异自证

**Files:**
- Modify/Test: `.../chat/DefaultChatSteeringGuidanceContractTest.java`(新,锁 steering 引导句)
- Test: 跑全量存量 + 新增
- Modify: 各变异点(临时,验毕还原)

**Interfaces:**
- Consumes: 全部前序 task。
- Produces: steering 引导句契约锁;A1 帧序列契约锁;全量回归绿;spec §6 变异清单逐项自证。

- [ ] **Step 1: 写 steering 引导句契约测试 + A1 帧序列契约测试**

```java
@Test void system_prompt_contains_steering_priority_guidance() {
  // buildDynamicSystemPrompt 含"运行中用户补充的消息具有最高优先级"(Task 7 引导句)
}
@Test void empty_inbox_frame_sequence_identical_to_baseline() {
  // A1:无 steering run 的 SSE 帧类型序列与改造前快照一致(无 steerAck/iteration 帧)
}
```

- [ ] **Step 2: 跑全量默认测试 + IT 门(清三目录)**

```bash
mvn install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure -Dgpg.skip=true -DskipTests
rm -rf spring-ai-loom-agent-test/target/{test-ds,test-users,e2e-files} spring-ai-loom-agent-test/target/surefire-reports
mvn test -pl spring-ai-loom-agent-test
mvn test -pl spring-ai-loom-agent-test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 默认 494+ 全绿;IT 228+ 全绿(含新增)。Issue1/ChatReasoningHistory/ChatProviderTimeoutWiring/AskUserGuidance 等存量锁绿。

- [ ] **Step 3: 逐项变异自证(spec §6.4,验毕还原)**

- 去内层 drain ⇒ SteeringInjectionTest 红
- DISMISS 走 cancel/is_error ⇒ AskUserGuidanceContractTest 红
- finalize 不查 inbox ⇒ ContinuationGuardrailTest 红
- 历史不按 USER 分组 ⇒ HistoryReplayBrowserIT 红
- B2 去豁免 ⇒ ConversationFlowSteeringTimelineTest 红
- reasoning 改整条流单行 ⇒ ReasoningPerIterationTest 红
- loop advisor 改 defaultAdvisor ⇒ SubTaskLoopExclusionTest 红
- LoomMemoryAdvisor order 改 +300 ⇒ Issue1 红

每变异跑对应测试确认红,还原确认绿。记录结果。

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "test(chat): steering 引导句 + A1 帧序列契约锁 + 全量回归 + 8 项变异自证

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 16: 截图基线重建 + 波及面矩阵核销 + 文档同步

**Files:**
- Modify: `spring-ai-loom-agent-test/src/test/resources/browser-baselines/{index,admin-conversation}.png`(本机重建)
- Modify: `CLAUDE.md`(同步新组件/配置/行为)
- Modify: spec §7.5 矩阵(逐项标注处置/验证结果)
- 可选: `docs/project-overview-{en,zh}.png`(架构有意义变更 → project-overview-image skill 重生成)

**Interfaces:**
- Consumes: 全部前序 task 完工。
- Produces: 基线与改版 UI 一致;CLAUDE.md 反映 LoomMemoryAdvisor/LoomAgenticLoopAdvisor/steering/dismiss/loop 配置/审计表;矩阵 A1–F5 全核销。

- [ ] **Step 1: 重建截图基线**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest='VisualBaselineBrowserIT' -DupdateBaselines=true -Dsurefire.failIfNoSpecifiedTests=false
mvn test -pl spring-ai-loom-agent-test -Dtest='VisualBaselineBrowserIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 重建后 diff 阈值内绿。

- [ ] **Step 2: 波及面矩阵逐项核销**

对照 spec §7.5 A1–F5,每项标注实际处置 task + 验证结果(测试名/绿)。未覆盖项补测试或说明。重点确认:工具栏/侧栏锁保留(A3)、附件/画板锁(A10)、convStatePanel 不回归(A13)、md 导出兼容(A16)、stats/user.html token 口径不变(D4/D5)、RBAC/MCP/知识空间不回归(E4/E5/E6)。

- [ ] **Step 3: CLAUDE.md 同步**

更新:Architecture 表加 LoomMemoryAdvisor/LoomAgenticLoopAdvisor;Configuration Properties 加 loop 键;askUser 行加 dismiss 双通道;Data Layer 加 loom_chat_steering;Frontend 加三态机/灰态/事件气泡;Key Commands 不变。overview 图若架构有意义变更 → 触发 project-overview-image skill 重生成(改 generate.py DATA/LABELS,不手改 PNG)。

- [ ] **Step 4: 最终全量回归 + Commit**

```bash
rm -rf spring-ai-loom-agent-test/target/{test-ds,test-users,e2e-files}
mvn test -pl spring-ai-loom-agent-test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false
git add -A
git commit -m "docs: 基线重建 + 波及面矩阵核销 + CLAUDE.md 同步 agentic loop/steering/dismiss

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Self-Review 结论

- **Spec 覆盖**:G1–G9 各有 owning task(G1/G2→T7,G3→T8/T13,G4→T12,G5→T10/T14,G6→T7,G7→T10,G8→T7,G9→T8/T13);A1–A9 反目标各有契约/变异锁;S1–S5 内嵌 T5/T4/T6/T9/T7;D-1/D-2/D-3 裁定落 T4/T8/T7;§7.5 矩阵 T16 核销。无遗漏。
- **类型一致**:StreamRunContext.CONTEXT_KEY、SteeringInbox.SteeringMessage、MemoryWriter.writeUser/writeAssistant、SteerAck(messageId)、IterationEvent(index)、DISMISS_SENTINEL、LoopProperty.getMaxContinuations/getRunTimeoutSeconds、ILoomChatSteeringRepository.record/findByConversationId 跨 task 引用一致。
- **Review Focus 5 类**各有测试落点(T11/T14 XSS、T7 收口竞态、T7 断连 orphan、T8 dismiss CAS、T7/T9 spam/emoji)。
- **比例**:步骤以签名+测试断言+命令为主,仅收口 CAS/B2 豁免/reasoning 复位/interleave 给短算法块,未转录完整代码体。