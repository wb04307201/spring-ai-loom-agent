# Steering 排队消息撤回(retract)设计

- 日期:2026-11-24
- 状态:已审批 · 已实施(2026-11-24,5 task 全落地;核销见 §5.1)
- 分支:`feat/steering-retract`(自 `feat/agentic-loop-steering` HEAD 切出;steering 主特性尚未合并 master,本特性是其增量)
- 前置 spec:`docs/superpowers/specs/2026-11-21-agentic-loop-steering-chat-mode-design.md`(A1/A2/A4 不变式、三态机、回退纪律 #6、R28/R34 会话快照绑定 —— 本 spec 全部继承,不重述)

## 1. 意图与背景

steering 已实现"AI 运行中用户插话 → 缓存区(SteeringInbox)→ 下一轮注入"。用户新需求:

> 缓存区里**尚未注入模型**的消息,允许用户从前端删除(撤回);**已注入**的不可删除。

用户同时直觉"缓存区是不是需要读写锁"。分析结论:需要的是**注入与撤回之间的唯一裁决点**,但粒度是单条消息的原子移除,不是全局读写锁(见 §3)。

**现状澄清**(用户提案的前半部分已落地,本 spec 只做撤回):
- 续跑判定已是两层:内层工具轮边界 drain 注入(`doGetNextInstructionsForToolCall*`);外层 `doFinalizeLoop*` 查 inbox,残留非空且护栏未触 → reopen + 续跑。"有工具调用或缓存区有内容 → 下一轮"即现有语义。
- messageId 由**服务端 UUID 生成**(`LoomAgentConfiguration.SseController.steer`,约 :981),天然唯一。`SteeringInbox.SteeringMessage` javadoc 里"前端生成的消息 ID"是陈旧措辞,实施时顺带订正。

## 2. 目标 / 非目标 / 反目标

**目标**
- G1:`queued` 态(已入服务端 inbox、等 steerAck)消息可撤回;服务端原子裁决,撤回成功 ⇒ 消息绝不进模型。
- G2:`fallback` 态(queued:false/403 本地回退,等流结束补发)与 `unsent` 态(未发送终态)可本地清理;**fallback 清理必须杜绝幽灵补发**(已删消息在流结束后被 `chat.send` 发出)。
- G3:`injected` 态不可撤回:UI 无按钮 + 服务端原子移除兜底(drain 已取走 ⇒ remove 失败 ⇒ 撤回被拒)。
- G4:A1 纯增量:不使用撤回时,SSE 帧序列 / memory 行布局 / 现有行为逐帧零差异。

**非目标**
- 编辑排队消息(只删不改)。
- 撤回已注入消息(用户明确裁定不可)。
- 跨标签页撤回同步(灰态气泡是发送 tab 本地的,各 tab 只撤回自己的消息;端点幂等即可)。
- 撤回审计行(消息从未进模型,无注入可审计;`loom_chat_steering` 不动)。
- 新 SSE 帧(撤回确认走 HTTP 响应;不新增 steerAck 状态)。

**反目标(出现即失败)**
- A2 破缺:任何路径静默蒸发用户消息(撤回是用户主动的可见处置,属 A2 合法终态,但"撤回成功却仍被注入"或"撤回失败却无终态"都是缺陷)。
- 幽灵补发:已删除的 fallback 项被补发(含 setTimeout 0ms 窗口)。
- XSS:撤回按钮/提示引入 innerHTML 注入面。
- 破坏现有三态机 / 工具栏锁 / askUser / 回退纪律 / 会话隔离。

## 3. 方案选择

| 方案 | 取舍 | 裁定 |
|---|---|---|
| A. 全局读写锁保护 inbox | offer/drain/撤回全是写操作,读写锁退化为互斥锁,串行化整个收件箱;与现有无锁 CAS 协议(closing/reopen/terminate)叠加增加死锁面;仍不解决前端/服务端状态分歧 | ✗ |
| **B. 逐消息原子移除(身份 Node + `queue.remove(node)`)** | 复用 offer 侧已验证的"入队后复查 + 身份撤单"模式(SteeringInbox:100,R6 裁定同源);ConcurrentLinkedQueue 保证每节点恰好被移除一次 —— drain 的 poll 与撤回的 remove 只有一个赢,**赢家拥有处置权**;零全局锁 | ✓ 选定 |
| C. tombstone 两阶段(标记已删,drain 跳过) | 多一个中间态:depth 计数、A2 可见性、tombstone 清理都要额外机制;把竞态变成状态管理问题而非消除它 | ✗ |

**核心原则:前端灰态只是提示,服务端原子移除结果才是权威。** UI 显示灰色时消息可能已被 drain;撤回成败由服务端裁决,前端按响应分支。

## 4. 机制设计(已裁定部分)

### 4.1 后端

**`SteeringInbox.retract(String messageId)`**(新增):
```java
// 弱一致迭代扫队列(深度 ≤20,O(20) 无需索引),按 messageId 定位身份 Node
for (Node node : queue) {
    if (node.message.messageId().equals(messageId)) {
        if (queue.remove(node)) {   // 线性化点:与 drain 的 poll 互斥,恰好一方成功
            depth.decrementAndGet(); // 每节点 depth 恰好减一次(poll XOR remove),不变式保持
            return true;             // 撤回成立:消息从未被 drain,绝不进模型
        }
        return false;                // 已被 drain 取走(注入或 NOT_INJECTED 残留处置中)
    }
}
return false;                        // 不在队列(已 drain / 从未存在 / 已收口清空)
```
- 与 `close`/`reopen`/`terminate` 的组合全部由同一原子 remove 裁决,无需新标志:
  - terminate 赢 → 残留走 NOT_INJECTED 终帧,撤回返 false;
  - 撤回赢 → 消息消失于队列,terminate 的残留不含它,无帧(前端已收 retracted:true)。
- **A2 措辞扩展**(类 javadoc + offer 复查注释):offer 复查 `queue.remove(node)` 失败现在有**两个原因** —— 被 drain 取走 **或用户撤回**,两者均为可见处置,A2 不变式(offer true ⇒ 必被读走或可见终态)不破。终态枚举扩为:已注入 / 未注入(NOT_INJECTED)/ 用户撤回。
- 顺带订正 `SteeringMessage.messageId` javadoc:"前端生成" → "服务端 UUID 生成"。

**`SseEmitterRegistry.retract(username, conversationId, messageId)`**(新增,镜像 `steer`,**布尔返回**):get Entry → null 返 false → `inbox.retract(messageId)`。Entry swap 竞态与 steer 同款 benign(新 Entry 里找不到 id → false)。

**`DELETE /spring/ai/loom/stream/{conversationId}/steer/{messageId}`**(SseController 新增):
- 归属校验镜像 steer 端点(`userConversation.exists`,跨用户 403 `{error:"forbidden",code:403}`)—— R33 教训:破坏性操作先过所有权。
- **reason 由端点预检派生,镜像 steer 端点现有结构**(端点先 `emitterRegistry.get` + 状态判定,再调 registry 委托方法):entry == null 或 `inbox.isTerminated()` → 200 `{retracted:false, reason:"no_active_run"}`;**closing 但未 terminated**(finalize 收口窗口)不在此分支 —— 队列已被 closeAndRemaining drain 清空,走 retract 自然返 false → `not_in_queue`(残留正被续跑注入或护栏处置,前端交给 ack 裁决)。
- `registry.retract` true → 200 `{retracted:true}`;false → 200 `{retracted:false, reason:"not_in_queue"}`。
- **`not_in_queue` 不等于"已注入"**:drain 走的残留可能续跑注入(INJECTED),也可能护栏/stop 收口(NOT_INJECTED)。服务端不预判,前端也不预判(见 4.2)。
- 无请求体;无 8KB/深度守卫(无内容输入)。AuthenticationFilter 已覆盖该路径前缀,无需新配置。

### 4.2 前端(app.js)

**✕ 撤回按钮**:`renderQueuedUser` 在 bubble 内追加小按钮(class `queued-retract-btn`,textContent "✕",title "撤回"),queued / fallback 两态渲染时即带;`_markUnsent` 转 unsent 时保留(或追加)按钮。按钮是 `.queued-body`/`.queued-note` 的兄弟节点,**不进入任何 textContent 复制载荷**(灰态气泡无复制动作;`origin-*` 载荷只覆盖 AI 气泡)。
- `_promoteQueued`(INJECTED 转正):按钮随 note 一起移除。
- `onAck` 幂等守卫不变(转正/未发送终态后按钮已不存在或状态已迁移)。

**点击行为按 steerState 分支**:
- `queued` → `api.retractSteer(convId, messageId)`(DELETE,显式 `Content-Type: application/json; charset=UTF-8` 遵守全仓 fetch 纪律)。**convId 取气泡渲染时刻的会话快照**(`renderQueuedUser` 增 `item.dataset.convId`,由 `sendSteering` 入队时的 convId 传入 —— additive,不触碰 R28/R34 的 `_fallback` 快照逻辑):R20 晚响应支路可能在会话已切走后把旧 conv 气泡渲染进新视图,点击时 live `state.conversationId` 会朝错误 conv 发 DELETE;快照绑定杜绝该窗(与 R34 同哲学)。
  - `{retracted:true}` → **气泡 DOM 移除** + toast "已撤回"。消息从未进模型,无 DB 痕迹,历史回放无痕迹(by design)。
  - `{retracted:false}`(任意 reason)→ **气泡保留,不预判状态**,toast "未能撤回,消息可能已注入";后续由现有机制裁决:INJECTED ack → 转正;NOT_INJECTED ack → 未发送;流结束汇聚点 `_resolveQueuedAwaits` → 未发送。点击时若本地状态已迁移(injected/unsent,ack 抢先到达)→ 按新状态走对应分支或忽略。
  - 网络错误/5xx → 气泡保留 + toast 失败提示,可重试(幂等)。
- `fallback` → **纯本地,不调端点**:项上置 `retracted:true` 标志 + 从 `steering._fallback` 数组移除该项 + 气泡 DOM 移除。**两条腿承重不同,不可互相替代**:
  - **闭包标志(承重腿,防幽灵发送)**:`onStreamEnd` 的 `setTimeout(0)` 闭包已持有 `first` 引用,**检查点必须在闭包顶部、先于 `unshift` 重试支路**(`first.retracted` 为 true → 直接 return,不回插数组、不发送、不 markUnsent —— 气泡已移除)。只把检查放 send 之前会让已撤回项被 unshift 回数组滞留到下一次 complete。
  - **数组移除(承重腿,防死气泡复活)**:若只置标志不移数组,`onStreamEnd` 的 stop 路径会对已撤回项 `markUnsent(f.bubble)` —— detached 节点改状态不可见,但 `onConversationSwitch`/stop 遍历仍把它当存活项处理,且 stop 分支 `this._fallback = []` 之前的遍历、R28 stale 遍历的 `isConnected` 守卫组合下行为依赖 DOM 状态而非意图;数组移除让"用户已撤回"在数据结构层即刻成立,不依赖气泡是否挂载。
- `unsent` → 纯本地:气泡 DOM 移除(各项终态队列引用早已清空,无数组操作)。

**不碰的面**:`_setComposerMode` 单口、工具栏/侧栏锁、askUser 卡片、`_stopSuppressedFallback` 语义、R28/R34 会话快照绑定逻辑 —— 全部原样。撤回是气泡动作,与输入区状态机正交。

### 4.3 CSS

`queued-retract-btn` 复用现有视觉 token(a11y 守卫:禁 #6366f1/#94a3b8 等废弃色);小尺寸、不改变气泡布局骨架(存量断言锚 `.queued-body`/`.queued-note`/count,兄弟按钮不破坏)。

## 5. 波及面矩阵(实施时逐项核销)

| 项 | 面 | 处置 | 验证 |
|---|---|---|---|
| M1 | SteeringInbox offer/drain/close/reopen/terminate | retract 复用身份 remove,零新标志;A2 措辞扩展 | 单测 + 竞态 seam(§6) |
| M2 | loop advisor 内层 drain / 外层续跑 | **零改动**(原子 remove 天然组合) | 存量 SteeringInjectionTest / NoSteeringFrameSequenceContractTest 绿 |
| M3 | LoomMemoryAdvisor / 审计表 / ChatHistoryAssembler / ConversationFlowService | **零改动**(撤回无注入 ⇒ 无 USER 行/审计行) | 存量回归绿 |
| M4 | steer 端点 / registry.steer | 零改动;DELETE 镜像其归属校验 | 端点单测(403/no_active_run/两 reason) |
| M5 | 前端三态机 / 锁 / askUser | 零改动(正交) | 存量 SteeringInputBrowserIT 12 例 + AskUserDismissBrowserIT 6 例绿 |
| M6 | `_fallback` 补发链路 | 数组移除(防死项滞留)+ 闭包**顶部** retracted 检查(防幽灵发送,先于 unshift 支路)—— 两腿承重不同 | 新 BrowserIT 双用例 + 变异各打各腿(§6.3) |
| M7 | onAck / _promoteQueued / _resolveQueuedAwaits | 转正移除按钮;幂等守卫不变;retracted 气泡已移除,选择器天然不命中 | 新 BrowserIT + 存量 |
| M8 | 复制载荷 / md 导出 / 历史回放 | 零改动(灰态无复制动作;撤回无 DB 痕迹) | 存量 HistoryReplayBrowserIT 6 例绿 |
| M9 | 截图基线 | 基线捕获 idle 态,灰态不在其中;实施时跑 VisualBaselineBrowserIT 验证,若受影响本机重建 | VisualBaselineBrowserIT |
| M10 | A1 帧序列 | 撤回零新 SSE 帧 | NoSteeringFrameSequenceContractTest 绿 |
| M11 | 多标签页 / stop / 会话切换 / 护栏收口 | 各 tab 只撤回自己的气泡;terminate/retract 竞态由原子 remove 裁决;切换/收口后气泡已销毁或转 unsent | 竞态单测 + 存量 stop/切换用例绿 |
| M12 | 文档 | CLAUDE.md steering 节增撤回描述 + A2 三终态措辞;SteeringMessage javadoc 订正 | T-doc 核销 |

### 5.1 核销结果(2026-11-24 实施完成,5 task)

| 项 | 处置 task | 验证(测试名 → 结果) |
|---|---|---|
| M1 | T1 | `SteeringInboxTest` 13 例(含 retract↔drain/terminate 竞态 seam)→ 绿;变异(值相等 remove)→ 红 |
| M2 | 零改动 | `SteeringInjectionTest` / `NoSteeringFrameSequenceContractTest` → 绿(A1 帧序列不变) |
| M3 | 零改动 | 默认全量套件 738/738 绿(memory/审计/回放/流水无回归) |
| M4 | T2 | `SteerEndpointTest` 12 例(403/no_active_run/双 reason/竞态)→ 绿;变异(删归属校验)→ 403 用例红 |
| M5 | 零改动 | `SteeringInputBrowserIT` 12 + `AskUserDismissBrowserIT` 6 → 绿 |
| M6 | T4 | `RetractSteeringBrowserIT` ⑥⑦⑧ → 绿;三腿变异各打各腿(闭包顶部检查/数组移除/unshift 位置)→ 对应红 |
| M7 | T3/T4 | `RetractSteeringBrowserIT` ②③(转正移除按钮/NOT_INJECTED 终态)+ `EventBubbleBrowserIT` 5 → 绿 |
| M8 | 零改动 | `HistoryReplayBrowserIT` 6 → 绿(撤回无 DB 痕迹,活体 historyLeak=false 实证) |
| M9 | T5 | `VisualBaselineBrowserIT` 10 → 绿(✕ 按钮不入 idle 基线,无需重建) |
| M10 | T1-T4 | `NoSteeringFrameSequenceContractTest` → 绿(零新 SSE 帧) |
| M11 | T1/T4 | 竞态单测 + stop/切换存量用例 → 绿 |
| M12 | T5 | CLAUDE.md steering 节 + 本核销节 + SteeringMessage javadoc 订正 → 完成 |

**活体 Chrome E2E 冒烟记录(2026-11-24,真实服务 8080 + MiniMax-M3.1-Flash,playwright MCP)**:
- 场景1 queued 撤回:conv `c0581425` / mid `28bbb5ff`,✕ → 气泡 DOM 移除 + toast「已撤回」,run 继续;流结束后 `historyLeak=false` / `domLeak=false`(撤回消息绝不进模型,无 DB 痕迹)。
- 场景2 queued 不撤回注入:mid `9bac8be4`,steerAck INJECTED → 转正(injected)+ ✕ 随 note 移除(G3),AI 末段回应「收到插话」。
- 场景3 fallback 撤回:全新 conv `974f563a` + 唯一文本,真实 run 中经 `_queueFallback` 造 fallback 气泡(queued:false 同路径)→ ✕ → 气泡移除 + `_fallback.length=0`,流结束后 `ghostInDom=false` / `ghostInHistory=false`(无幽灵补发)。自然 queued:false 收口窗 5 轮未捕捉(MiniMax 收尾窗极窄),改确定性触发,撤回/防幽灵走真实代码路径。
- 场景4:登录后 console 0 error;logo.png 200(无 favicon 404);中文无 mojibake。(3 个 401 error 为登录前 i18n/tool-call-renderer/canvas-board 脚本鉴权跳转,预存在,非本特性。)

**实施期裁定(ledger Ruling 摘要)**:T2-R3 环境修复(上会话中断 build 留损坏 jar/class,clean install 重建);T4-R2 气泡 expando `_fallbackItem` 补 brief find-only 在 0ms 窗的缺口;T4-R3 ⑧ 时序订正(先 onStreamEnd 过附件闸门 shift 再 push pendingImages);T5-R1 app.js 行尾 LF 归一(变异脚本 Windows text-mode 写转 CRLF 致契约断言失配,非代码缺陷)。

**Deferred finding(交用户裁定,非本特性缺陷)**:`HttpToolBrowserIT.unauthorizedUserGetsNoHttpTool` 在 provider 切 MiniMax 后红 —— 实证该 run 只调 universal 工具(getSkill/listAllowedDirectories/searchFiles/directoryTree),http 工具调用数=0(RBAC  intact,tool_http 正确未注入);根因是该预存在 real-LLM 测试断言"零 TOOL_CALL"过严,MiniMax 比 Qwen 更 agentic 会调 universal 工具试探。与撤回无关,不阻塞本特性。

## 6. 测试计划(TDD,先红后绿)

1. **SteeringInboxTest 增补**:retract 成功/不在队列/已被 drain;depth 释放计数;retract↔drain 竞态 seam(仿 `beforeEnqueue` 模式加 `beforePoll` 测试 seam,生产 no-op,确定性定格);retract↔terminate 竞态两分支;孪生 messageId 防御(生产 UUID 不可能,接口层仍按身份移除不误撤)。
2. **端点单测**(mock registry + userConversation):跨用户 403;no_active_run;retracted:true;not_in_queue。
3. **RetractSteeringBrowserIT**(新,awaitRoute 切片泵送遵守 R26):
   - queued ✕ → DELETE → 气泡移除 + toast;
   - 撤回竞态:route 延迟 DELETE 响应期间先投递 INJECTED ack → 气泡转正,撤回响应到达不破坏终态;
   - **幽灵补发(打闭包标志腿)**:fallback ✕ 后**立即** complete(项已 shift 进 setTimeout 闭包)→ `chat.send` 计数为 0;变异:去掉闭包顶部 retracted 检查 ⇒ 红。**注意**:此用例只承重闭包标志 —— 数组移除腿被去掉时本用例仍绿(闭包持同一引用,标志兜住),不得拿它当数组移除的变异门(评审订正,防假绿)。
   - **死项滞留(打数组移除腿)**:fallback ✕ 后断言 `steering._fallback` 不含该项(length/内容);再走 stop 路径断言不对已撤回气泡产生任何状态写(以数组不含该项为锚);变异:去掉数组移除 ⇒ 红。
   - **unshift 重试支路**:fallback ✕ 时恰有 pendingImages 未落 → 闭包不得把已撤回项 unshift 回数组(检查点在顶部);变异:检查点移到 send 前 ⇒ 红。
   - unsent ✕ → DOM 移除;
   - XSS:含 `<img onerror>` 的插话撤回全程 inert;
   - 撤回失败(not_in_queue)→ 气泡保留,NOT_INJECTED 帧到达后标未发送。
4. **变异自证**(项目约定):去掉原子 remove 改值相等 remove / 去掉闭包 retracted 检查 / 去掉归属校验 ⇒ 对应用例红。
5. **全量回归门**:默认 test 套件 + 显式 `-Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false`(跑前清 `spring-ai-loom-agent-test/target/{test-ds,test-users,e2e-files}`,不触碰 ~/.loom);库/autoconfigure 改动后先 `mvn install -pl <module> -Dgpg.skip=true -DskipTests`。
6. **活体 Chrome E2E**(用户硬性要求,继承 steering spec):真实服务 + 真实 Chrome 冒烟撤回 queued/fallback 两路 + 竞态提示。

## 7. Builder 裁量项(无需回问用户)

- ✕ 按钮具体样式/位置(复用 token,a11y 不漂移)。
- retract 扫描实现细节(迭代器 vs toArray 快照;弱一致迭代足够)。
- toast 文案微调(语义不变:成功"已撤回"/失败"未能撤回,消息可能已注入")。
- `beforePoll` 测试 seam 的具体形态(仿 beforeEnqueue 同包覆写模式)。
- commit 拆分粒度(建议:后端 inbox+registry+端点 / 前端按钮+三分支+幽灵补发保险 / 测试+文档,各带回归门)。

## 8. 风险与边界(已分析,均有处置)

| 边界 | 处置 |
|---|---|
| 撤回点击撞 drain(毫秒窗) | 服务端原子 remove 裁决;前端不预判,ack/汇聚点兜底 |
| fallback 已 shift 进 setTimeout 闭包(0ms 窗) | 闭包**顶部** retracted 检查(先于 unshift 支路) |
| queued 气泡渲染晚于会话切换(R20 晚响应) | 气泡 dataset.convId 渲染时刻快照,DELETE 朝快照 conv 发(additive,不动 R28/R34 回退逻辑) |
| 撤回撞 stop/terminate | 原子 remove 互斥;terminate 赢 → NOT_INJECTED 可见,撤回赢 → retracted:true 可见 |
| 撤回撞 closing→reopen 续跑窗口 | 收口已 drain 清空 → not_in_queue → 前端交给续跑 ack 裁决 |
| offer 复查 remove 失败语义扩展 | A2 javadoc 同步订正,两原因均可见处置 |
| 多 tab 同会话 | 灰态本地性,各撤各的;端点幂等 |
| 深度计数 | 每节点恰好减一次(poll XOR remove),预约占额语义不变 |