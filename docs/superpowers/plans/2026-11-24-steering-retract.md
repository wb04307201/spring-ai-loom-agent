# Steering 排队消息撤回(retract)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 未注入模型的 steering 消息可从前端撤回:queued 走服务端原子移除裁决,fallback/unsent 本地清理(杜绝幽灵补发),injected 不可撤回。

**Architecture:** 复用 SteeringInbox 身份 Node + `queue.remove(node)` 原子移除模式(与 drain 的 poll 互斥,赢家拥有处置权),零全局锁;DELETE 端点镜像 steer 归属校验;前端 ✕ 按钮按 steerState 三分支,fallback 清理双腿承重(闭包顶部 retracted 检查防发送 + 数组移除防死项);零新 SSE 帧、零审计行、零 memory 改动。

**Tech Stack:** JDK 25, Spring Boot 4, Spring AI 2.0.2, H2, vanilla JS SPA, JUnit 5 + AssertJ + Mockito, Java Playwright BrowserIT。

**Spec:** `docs/superpowers/specs/2026-11-24-steering-retract-design.md`(执行者必读;前置 spec `2026-11-21-agentic-loop-steering-chat-mode-design.md` 的 A1/A2/A4、#6 回退纪律、R28/R34 全部继承)

## Global Constraints

- 分支 `feat/steering-retract`(已自 `feat/agentic-loop-steering` HEAD 切出,spec commit `923ca10a`)。每 task 末尾 commit,message 中文,前缀 `feat:/fix:/test:/docs:`,结尾 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。
- **A1 纯增量**:不使用撤回时 SSE 帧序列/memory 行布局/现有行为逐帧零差异;`NoSteeringFrameSequenceContractTest` 必须持续绿。
- **A2 扩展**:消息终态枚举 = 已注入 / 未注入(NOT_INJECTED)/ 用户撤回;任何路径不得静默蒸发。
- **零新 SSE 帧、零审计行、零 schema 改动**;loop advisor / LoomMemoryAdvisor / ChatHistoryAssembler / ConversationFlowService **不得改动**。
- 库/autoconfigure 改动后、跑 test 模块前必须 `mvn install -pl <module> -Dgpg.skip=true -DskipTests`(test 模块经 m2 加载)。
- IT 门:显式 `-Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false`,跑前清 `spring-ai-loom-agent-test/target/{test-ds,test-users,e2e-files}`;**不触碰 ~/.loom**。
- 前端用户可控字段一律 textContent/esc();a11y 色板不得漂移(`A11yTokenDriftContractTest` 守卫,禁 #6366f1/#94a3b8 等废弃 token);所有 fetch 显式 `Content-Type: application/json; charset=UTF-8`。
- BrowserIT 扣流必走 awaitRoute 切片泵送(R26);测试 seam 生产恒 no-op(仿 `beforeEnqueue` 同包覆写模式)。
- **变异自证**(项目纪律):每个承重保险配独立变异门,断言不得互相顶替(防假绿,spec §6.3 评审订正)。
- **完工硬门(用户要求)**:全部开发完成后启动真实服务(`mvn spring-boot:run -pl spring-ai-loom-agent-test`)+ 真实 Chrome(chrome-devtools/playwright MCP)活体冒烟;E2E gotchas:curl 中文 body 用 UTF-8 文件、重启后重新登录、思考面板点开才可见。
- 撤回成功文案 "已撤回";失败文案 "未能撤回,消息可能已注入"(语义锁定,微调归 builder)。

## Review Focus

spec 隐含、最可能咬到真实用户的 5 类输入/条件(各已落到 owning task):

1. **撤回点击恰好撞上 drain/terminate(毫秒窗)** → 服务端原子 remove 唯一裁决;前端不预判,ack/汇聚点兜底,绝不"撤回成功却仍注入"或"失败却无终态"。→ Task 1 seam 单测 + Task 3 BrowserIT 竞态用例。
2. **fallback 项已 shift 进 setTimeout 闭包后用户点删(0ms 窗)** → 闭包顶部 retracted 检查丢弃,绝不幽灵补发;已撤回项不得 unshift 回数组。→ Task 4 双腿变异门。
3. **撤回文本含 HTML/script** → 气泡/按钮/toast 全程 inert。→ Task 3 XSS 用例。
4. **R20 晚响应:旧会话 queued 气泡渲染进新视图后点撤回** → DELETE 朝气泡 convId 快照发,不朝 live state。→ Task 3 快照用例。
5. **stop/断连与撤回竞态** → terminate 赢 → NOT_INJECTED 可见;撤回赢 → retracted:true 可见;两路都不蒸发。→ Task 1 竞态单测 + Task 4 unsent 清理用例。

## File Structure

**修改(主库 `spring-ai-loom-agent`):**
- `src/main/java/cn/wubo/spring/ai/loom/agent/chat/SteeringInbox.java` — 加 `retract(String)` + `beforeRetractRemove` seam + A2 javadoc 扩展 + messageId javadoc 订正。
- `src/main/java/cn/wubo/spring/ai/loom/agent/stream/SseEmitterRegistry.java` — 加 `retract(...)` 布尔委托(镜像 `steer`:143)。
- `src/main/resources/META-INF/resources/spring/ai/loom/app.js` — `api.retractSteer`(:838 steer 旁)、`ui.renderQueuedUser`(:1766,✕ 按钮 + dataset.convId)、`_promoteQueued`(:1803)、`_markUnsent`(:1811)、`steering` 对象(:2669,点击三分支 + onStreamEnd 闭包顶部检查)。
- `src/main/resources/META-INF/resources/spring/ai/loom/style.css` — `queued-retract-btn` 样式(:424 灰态样式区旁)。

**修改(autoconfigure):**
- `src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java` — SseController 加 DELETE 端点(:988 steer 端点后)。

**修改(test 模块):**
- `src/test/java/cn/wubo/spring/ai/loom/agent/chat/SteeringInboxTest.java` — retract 单测 + seam 竞态。
- `src/test/java/cn/wubo/spring/ai/loom/agent/chat/SteerEndpointTest.java` — DELETE 端点用例(沿用真 registry + mock 归属脚手架)。

**新建(test 模块):**
- `src/test/java/cn/wubo/spring/ai/loom/agent/browser/RetractSteeringBrowserIT.java` — 前端撤回 IT(镜像 SteeringInputBrowserIT 脚手架:`openChatReady`/`holdStreams`/`steerBodies`/SteerScript)。

**文档:**
- `CLAUDE.md` steering 节 + spec §5 矩阵核销。

---

### Task 1: SteeringInbox.retract + 竞态 seam + 单测

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/SteeringInbox.java`
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/chat/SteeringInboxTest.java`

**Interfaces:**
- Consumes: 现有 `Node`(身份包装)、`queue`、`depth`、`terminateAndRemaining()`。
- Produces: `public boolean retract(String messageId)` — true=原子移除成功(消息绝不进模型),false=不在队列或已被 drain/terminate 取走。包级 seam `void beforeRetractRemove(SteeringMessage msg)`(生产 no-op,定位节点后、`queue.remove` 前调用)。

- [ ] **Step 1: 写失败测试**(SteeringInboxTest 增补,6 例)

```java
@Test void retract_removes_queued_and_releases_depth() {
    SteeringInbox inbox = new SteeringInbox();
    inbox.offer("m-1", "第一条"); inbox.offer("m-2", "第二条");
    assertThat(inbox.retract("m-1")).isTrue();
    assertThat(inbox.depth()).isEqualTo(1);
    assertThat(inbox.drain()).extracting(SteeringInbox.SteeringMessage::messageId)
            .containsExactly("m-2"); // FIFO 残余不受影响
}
@Test void retract_unknown_id_returns_false() { /* 空队列 + 有队列但 id 不存在,均 false,depth 不变 */ }
@Test void retract_after_drain_returns_false() {
    // offer → drain → retract 同 id → false(已被读走)
}
@Test void retract_loses_race_to_drain() { // seam 定格:定位节点后、remove 前被 drain 抢先
    SteeringInbox inbox = new SteeringInbox() {
        @Override void beforeRetractRemove(SteeringMessage msg) { inbox_drain_synchronously(); }
    };
    // offer("m-1") → retract("m-1") 触发 seam 内 drain() → remove 失败 → false
    // 断言:retract false 且 drain 已取走该条(消息可见处置,A2)
}
@Test void retract_loses_race_to_terminate() {
    // 同上 seam,hook 内改调 terminateAndRemaining() → retract false,残留在 terminate 返回值中
}
@Test void retract_wins_race_then_drain_empty() {
    // offer → retract true → drain() 空列表(撤回赢,消息不进模型)
}
```

- [ ] **Step 2: 跑确认失败**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=SteeringInboxTest`
Expected: FAIL,`retract` 方法不存在(编译错)。

- [ ] **Step 3: 实现 retract + seam + javadoc 订正**

```java
public boolean retract(String messageId) {
    for (Node node : queue) {              // 弱一致迭代,深度 ≤20 无需索引
        if (node.message.messageId().equals(messageId)) {
            beforeRetractRemove(node.message); // 测试 seam,生产 no-op
            if (queue.remove(node)) {      // 线性化点:与 drain 的 poll 互斥
                depth.decrementAndGet();   // 每节点恰好减一次(poll XOR remove)
                return true;
            }
            return false;                  // 已被 drain/terminate 取走
        }
    }
    return false;
}
void beforeRetractRemove(SteeringMessage msg) { /* no-op */ }
```

javadoc 三处:① 类级 A2 段补"用户撤回"第三终态 + offer 复查 remove 失败双原因(被 drain **或用户撤回**,均可见处置);② `SteeringMessage.messageId` "前端生成" → "服务端 UUID 生成";③ retract 方法 javadoc 写明线性化点语义。

- [ ] **Step 4: 装库 + 跑确认通过**

```bash
mvn install -pl spring-ai-loom-agent -Dgpg.skip=true -DskipTests
mvn test -pl spring-ai-loom-agent-test -Dtest=SteeringInboxTest
```
Expected: 全绿(含存量 offer/drain/close/reopen/terminate 用例)。

- [ ] **Step 5: 变异自证 + Commit**

变异:把 `queue.remove(node)` 改成按值 `queue.remove(node.message)`(Node 身份 equals 失效,remove 恒 false)⇒ `retract_removes_queued_and_releases_depth` 红。还原后 commit:

```bash
git add -A && git commit -m "feat(chat): SteeringInbox.retract 逐消息原子撤回 + 竞态 seam + A2 三终态扩展

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: Registry.retract + DELETE 端点 + 端点单测

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/stream/SseEmitterRegistry.java`
- Modify: `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java`(SseController,:988 后)
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/chat/SteerEndpointTest.java`

**Interfaces:**
- Consumes: Task 1 `inbox.retract(String)`;现有 `registry.get(user, conv)`、`Entry.inbox()`、`userConversation.exists(record)`。
- Produces: `public boolean SseEmitterRegistry.retract(String username, String conversationId, String messageId)`;`DELETE /spring/ai/loom/stream/{conversationId}/steer/{messageId}` → 200 `{retracted:true}` / 200 `{retracted:false, reason:"no_active_run"|"not_in_queue"}` / 403 `{error:"forbidden",code:403}`。controller 方法名 `retractSteer(String conversationId, String messageId)`(测试直调)。

- [ ] **Step 1: 写失败测试**(SteerEndpointTest 增补,5 例,沿用现有 setup/registerRun 脚手架)

```java
@Test void retract_queued_message_returns_true() {
    // registerRun → controller.steer 入队 → 取 inbox drain 前 retractSteer(CONV, messageId)
    // → 200 {retracted:true},inbox.depth()==0
}
@Test void retract_no_active_run_reason() { /* 不 register → {retracted:false, reason:"no_active_run"} */ }
@Test void retract_terminated_run_reason() {
    // registerRun → registry.terminateInbox(entry) → retract → no_active_run
}
@Test void retract_unknown_message_not_in_queue() {
    // registerRun + steer 入队 m → retract 不存在的 id → {retracted:false, reason:"not_in_queue"}
}
@Test void retract_cross_user_403() {
    // userConversation.exists 改 false → 403 {error:"forbidden",code:403}(R33 归属先于一切)
}
```

- [ ] **Step 2: 跑确认失败**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=SteerEndpointTest`
Expected: FAIL,`retractSteer` 不存在(编译错)。

- [ ] **Step 3: 实现 registry.retract + 端点**

registry(镜像 steer:143):
```java
public boolean retract(String username, String conversationId, String messageId) {
    Entry e = get(username, conversationId);
    if (e == null) return false;
    return e.inbox().retract(messageId);
}
```

端点(reason 由端点预检派生,镜像 steer 端点结构):
```java
@DeleteMapping("/spring/ai/loom/stream/{conversationId}/steer/{messageId}")
public ResponseEntity<Map<String, Object>> retractSteer(
        @PathVariable String conversationId, @PathVariable String messageId) {
    // 1. 归属校验逐字镜像 steer(:953-961):username + userConversation.exists,不通过 403
    // 2. entry = emitterRegistry.get(username, conversationId);
    //    entry == null || entry.inbox().isTerminated() → {retracted:false, reason:"no_active_run"}
    //    (closing 未 terminated 不在此分支 —— 队列已 drain 空,自然落 not_in_queue)
    // 3. emitterRegistry.retract(...) ? {retracted:true} : {retracted:false, reason:"not_in_queue"}
}
```

- [ ] **Step 4: 装库 + autoconfigure,跑确认通过**

```bash
mvn install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure -Dgpg.skip=true -DskipTests
mvn test -pl spring-ai-loom-agent-test -Dtest=SteerEndpointTest
```
Expected: 全绿(含存量 steer 端点用例)。

- [ ] **Step 5: 变异自证 + Commit**

变异:删归属校验 ⇒ `retract_cross_user_403` 红。还原后 commit:

```bash
git add -A && git commit -m "feat(chat): DELETE steer/{messageId} 撤回端点——归属校验镜像 steer + 双 reason 契约

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: 前端 ✕ 按钮 + api + queued 撤回分支 + BrowserIT

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js`(api :838 旁 / renderQueuedUser :1766 / _promoteQueued :1803 / _markUnsent :1811 / steering :2669)
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/style.css`(:424 灰态区)
- Create: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/browser/RetractSteeringBrowserIT.java`

**Interfaces:**
- Consumes: Task 2 DELETE 端点契约;现有 `steerBodies`/`holdStreams`/`openChatReady` 脚手架(镜像 SteeringInputBrowserIT)。
- Produces: `api.retractSteer(convId, messageId)` → `{status, body}`(镜像 api.steer 形态);`ui.renderQueuedUser(messageId, text, kind, convId)`(**加第 4 参**,现有 3 处调用点 :2700/:2741/:2744 同步传 convId,fallback 两处传 bound);气泡 `dataset.convId` 快照;`steering.retractBubble(el)` 点击分派入口(queued 分支本 task,fallback/unsent 分支 Task 4 补全,本 task 先占位 no-op + TODO 注释指向 Task 4)。

- [ ] **Step 1: 写失败 IT**(RetractSteeringBrowserIT,5 例;route 拦截 `**/steer/*` DELETE 按脚本 fulfill)

```java
// ① queued_retract_removes_bubble:steer 脚本 queued:true m-1 → 灰态出现 → 点 .queued-retract-btn
//    → DELETE fulfill {retracted:true} → 气泡 DOM 消失 + toast 含"已撤回"
// ② retract_failure_keeps_bubble_for_ack:DELETE fulfill {retracted:false,reason:"not_in_queue"}
//    → 气泡保留;再投递 INJECTED ack 帧 → 转正(steerState=injected,按钮随 note 移除)
// ③ retract_failure_then_not_injected:同②但投递 NOT_INJECTED → 标"未发送"
// ④ retract_uses_conv_snapshot:queued 气泡渲染后 evaluate 改 state.conversationId 为另一值
//    → 点 ✕ → 断言 DELETE URL 含气泡 dataset.convId(快照),不含 live 值(Review Focus 4)
// ⑤ retract_xss_inert:steer 文本 "<img src=x onerror=alert(1)>" → 撤回全程无 alert/新 img
//    (textContent 纪律;toast 同样 inert)
```

- [ ] **Step 2: 跑确认失败**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=RetractSteeringBrowserIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL,`.queued-retract-btn` 不存在(waitForSelector 超时)。

- [ ] **Step 3: 实现前端 queued 路径**

- `api.retractSteer(convId, messageId)`:DELETE `/spring/ai/loom/stream/${encodeURIComponent(convId)}/steer/${encodeURIComponent(messageId)}`,显式 JSON charset header,返回 `{status, body}`(body 解析失败容错空对象,镜像 api.steer)。
- `renderQueuedUser(messageId, text, kind, convId)`:`item.dataset.convId = convId || ""`;bubble 内追加 `button.queued-retract-btn`(textContent "✕",title "撤回",type="button"),onclick → `steering.retractBubble(item)`;按钮是 body/note 兄弟节点,**不进任何 textContent 载荷**。
- `_promoteQueued`:按钮随 note 一起 remove。`_markUnsent`:保留已有按钮(unsent 可清理,Task 4 接线)。
- `steering.retractBubble(el)` 按 `el.dataset.steerState` 分派:
  - `queued` → `api.retractSteer(el.dataset.convId || state.conversationId, el.dataset.messageId)`;`{retracted:true}` → `el.remove()` + showToast("已撤回");`{retracted:false}` → 保留气泡 + showToast("未能撤回,消息可能已注入"),**不预判状态**(ack/汇聚点裁决);网络错误 → 保留 + 失败 toast;点击时状态已迁移(injected/unsent)→ 转对应分支或忽略。
  - `fallback`/`unsent` → 本 task 占位 no-op(Task 4 实现)。
- CSS:`.queued-retract-btn` 小尺寸、复用现有 token(muted 色用 #64748b,禁废弃色),不改气泡布局骨架。

- [ ] **Step 4: 装库 + 跑确认通过 + 存量不回归**

```bash
mvn install -pl spring-ai-loom-agent -Dgpg.skip=true -DskipTests
mvn test -pl spring-ai-loom-agent-test -Dtest='RetractSteeringBrowserIT,SteeringInputBrowserIT,EventBubbleBrowserIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 新 5 例绿 + 存量 steering/气泡 IT 全绿(断言锚 .queued-body/.queued-note/count 不受兄弟按钮影响)。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(frontend): 灰态气泡 ✕ 撤回按钮 + queued 端点撤回分支 + convId 快照绑定

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: fallback/unsent 本地清理 + 幽灵补发双腿 + 变异门

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js`(steering.retractBubble fallback/unsent 分支 + onStreamEnd :2817 闭包)
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/browser/RetractSteeringBrowserIT.java`(增补)

**Interfaces:**
- Consumes: Task 3 `retractBubble` 分派骨架、✕ 按钮;现有 `_fallback` 数组元素 `{bubble, text, convId}`。
- Produces: `_fallback` 元素加 `retracted` 布尔字段;`onStreamEnd` 闭包顶部检查契约:**`first.retracted` 为 true → 直接 return,先于 isStreaming/pendingImages 的 unshift 支路**。

- [ ] **Step 1: 写失败 IT**(增补 4 例)

```java
// ⑥ fallback_retract_no_ghost_send(打闭包标志腿):steer 脚本 queued:false → fallback 气泡
//    → 同任务 evaluate 序列:先 steering.onStreamEnd()(项 shift 进闭包)再同步点 ✕
//    → 放行流 complete → chat.send 计数 0(POST /stream 计数)。
//    变异门:去掉闭包顶部 retracted 检查 ⇒ 本例红。
//    ⚠ 本例不承重数组移除(闭包持同一引用,标志兜住)——不得当数组移除的变异门(spec §6.3)。
// ⑦ fallback_retract_no_dead_item(打数组移除腿):fallback ✕(未触发 complete)
//    → 断言 window._loomAgent.steering._fallback 不含该项;再走 stop 路径无状态写。
//    变异门:去掉数组移除 ⇒ 本例红。
// ⑧ retracted_not_unshifted:fallback ✕ 时 pendingImages 有未落项 → 触发 onStreamEnd
//    → 断言 _fallback 不含已撤回项(检查点在 unshift 之前)。变异门:检查点移到 send 前 ⇒ 红。
// ⑨ unsent_retract_removes_dom:stop 后"未发送(已停止)"气泡点 ✕ → DOM 移除,无端点调用。
```

- [ ] **Step 2: 跑确认失败**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=RetractSteeringBrowserIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: ⑥⑦⑧⑨ FAIL(占位 no-op),①-⑤ 仍绿。

- [ ] **Step 3: 实现 fallback/unsent 分支 + 闭包顶部检查**

```js
// retractBubble fallback 分支:
const f = this._fallback.find(x => x.bubble === el);
if (f) { f.retracted = true; this._fallback.splice(this._fallback.indexOf(f), 1); }
el.remove();
// unsent 分支:el.remove()(队列引用早已清空)

// onStreamEnd setTimeout 闭包,顶部第一行(先于 unshift 支路):
setTimeout(() => {
  if (first.retracted) return;   // 已撤回:不回插、不发送、不 markUnsent(气泡已移除)
  if (state.isStreaming || ...) { this._fallback.unshift(first); return; }
  ...
}, 0);
```

- [ ] **Step 4: 装库 + 跑确认通过**

```bash
mvn install -pl spring-ai-loom-agent -Dgpg.skip=true -DskipTests
mvn test -pl spring-ai-loom-agent-test -Dtest='RetractSteeringBrowserIT,SteeringInputBrowserIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 9 例全绿 + 存量回退纪律用例(stop 抑制/会话切换/R34)全绿。

- [ ] **Step 5: 三腿变异自证 + Commit**

依次变异并确认对应用例红:① 去掉闭包顶部检查 ⇒ ⑥红;② 去掉数组移除 ⇒ ⑦红;③ 检查点移到 send 前 ⇒ ⑧红。全部还原后 commit:

```bash
git add -A && git commit -m "feat(frontend): fallback/unsent 撤回本地清理 + 幽灵补发双腿保险(闭包顶部检查/数组移除)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: 全量回归门 + 活体 Chrome E2E + 文档核销

**Files:**
- Modify: `CLAUDE.md`(steering 节增撤回描述 + A2 三终态)
- Modify: `docs/superpowers/specs/2026-11-24-steering-retract-design.md`(§5 矩阵逐项核销)
- 视结果: `spring-ai-loom-agent-test/src/test/resources/browser-baselines/`(仅当 VisualBaselineBrowserIT 红时本机重建)

**Interfaces:**
- Consumes: Task 1-4 全部产物。
- Produces: 回归门绿证据 + 活体冒烟记录 + 矩阵核销。

- [ ] **Step 1: 默认全量套件**

```bash
mvn test -pl spring-ai-loom-agent-test
```
Expected: 全绿(含 NoSteeringFrameSequenceContractTest / AskUserGuidanceContractTest / A11yTokenDriftContractTest 等存量契约锁)。

- [ ] **Step 2: IT 全量门(清隔离目录)**

```bash
rm -rf spring-ai-loom-agent-test/target/{test-ds,test-users,e2e-files}
mvn test -pl spring-ai-loom-agent-test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 全绿(环境守卫 skip 除外);VisualBaselineBrowserIT 若红 → 确认是 ✕ 按钮入镜所致才 `-DupdateBaselines=true` 本机重建,否则是缺陷回修。

- [ ] **Step 3: 活体 Chrome E2E(用户硬性要求;controller 直接驱动,镜像 R31 先例)**

启动真实服务(`mvn spring-boot:run -pl spring-ai-loom-agent-test`,jvmArguments 已固化 UTF-8),真实 Chrome(chrome-devtools/playwright MCP)冒烟:
1. 登录 → 新对话 → 发一条会触发工具调用的消息(如"列出我文件目录")→ RUNNING 中插话 → 灰态出现 → 点 ✕ → 气泡消失 + "已撤回" toast;
2. 再插话不撤回 → 确认下一轮注入生效(转正 + AI 回应补充);
3. 断网/queued:false 场景造 fallback 气泡 → ✕ → 流结束后确认无幽灵补发;
4. console 零 error、favicon 正常、中文无 GBK 乱码。
发现缺陷 → 回 owning task 修复 + 补 headless IT 锁 → 重跑。冒烟记录归档进 spec 核销节。

- [ ] **Step 4: 文档同步 + 矩阵核销**

CLAUDE.md:steering 节加 retract 描述(端点/三分支/双腿保险/A2 三终态);spec §5 M1-M12 逐项标注处置 task + 验证结果(测试名/绿)。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "docs: CLAUDE.md/spec 同步 steering 撤回 + 波及面矩阵核销 + 全量回归/活体 E2E 通过

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Self-Review 结论

- **Spec 覆盖**:G1→T1/T2/T3,G2→T4,G3→T1(remove 失败)+T3(转正移除按钮),G4→T5 存量契约锁;§4.1 三处后端→T1/T2;§4.2 前端四处→T3/T4;§4.3 CSS→T3;§5 矩阵→T5 核销;§6 测试计划→各 task Step 1 + T5;§8 边界→Review Focus 5 类各有落点。无遗漏。
- **类型一致**:`retract(String)`/`retract(String,String,String)`/`retractSteer(String,String)`/`api.retractSteer(convId,messageId)`/`renderQueuedUser(messageId,text,kind,convId)`/`retractBubble(el)`/`first.retracted` 跨 task 引用一致;renderQueuedUser 第 4 参的 3 处存量调用点已在 T3 Interfaces 列明。
- **比例**:伪码仅给算法核心(原子 remove/闭包顶部检查/端点三分支),测试给名字+断言锚,未转录完整代码体。