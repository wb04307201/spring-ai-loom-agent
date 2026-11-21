# Agentic Loop 跨轮续跑 + 对话模式改造 设计文档

- 日期:2026-11-21
- 分支:`feat/agentic-loop-steering`(自 `dev` 切出)
- 状态:待审批
- 前置讨论:2026-11-21 会话(Recursive Advisors 可行性 → 前端对话模式 → askUser 输入冲突 → Claude Code 官方机制调研)

## 1. 背景与意图(Why)

当前 LoomAgent 的对话是严格一问一答:用户发送 → 前端锁输入(`disableSend` + `setToolbarLocked`,连侧栏会话切换都锁)→ AI 单条流式回复 → 解锁。模型侧的工具循环(Spring AI `ToolCallingAdvisor`)已经存在,但**用户无法在 AI 运行过程中补充信息**——长任务(部署、多文件重构、批量分析)跑偏时,用户只能干等或硬停止重来。

目标是对齐 Claude Code 的交互模型:**AI 持续自主干活,用户随时可以插话纠偏,插话在当前工具调用结束后的同一 turn 内生效**。同时解决 askUser 提问期间"用户想说话但没有出口"的死锁感。

官方语义依据(2026-11-21 调研,见 §10 参考):

- Claude Code 运行中回车 → 消息灰色 queued,**当前工具调用一结束、同一 turn 内**传给模型;turn 结束时仍有排队消息 → 自动发出,不需再按键。
- `AskUserQuestion` 阻塞期间键盘被选择器接管;自由文本有两条协议级通道:**Other(自定义答案,`answers[question]`)** 与 **Chat about this(放弃问卷,独立 `response` 字段,模型收到 "The user responded: …")**。后者是阻塞工具的合法返回形态,不是取消/报错(CC bug #33511 把它错发成 `is_error:true` 导致死键,反面教材)。

## 2. 目标 / 非目标 / 反目标

### 目标

- G1 运行中自由文本输入(steering):入 inbox,迭代边界注入同一 run,前端灰态可见。
- G2 跨轮续跑:run 收尾时 inbox 非空 → 自动续跑,用户的话不掉缝里。
- G3 askUser 双通道:自定义答案(已有)+ "聊聊这个"逃生通道(新增),提问期间输入框有明确出口。
- G4 事件驱动气泡:用户插话处强制开新 AI 气泡;纯迭代续跑留在同一气泡内。
- G5 历史回放与 live 视图气泡分组一致。
- G6 护栏:续跑次数上限、run 墙钟预算、stop 信号在每个边界生效。
- G7 admin 流水时间线在 steering 注入后仍正确(修复 B2 时间戳重写对中段 USER 行的误伤)。
- G8 断连/刷新不留 orphan run(订阅 dispose + inbox 清理)。
- G9 CHAT 态全生命周期出口完备(超时/stop/404/断连均复位状态,文本不静默丢)。

### 非目标(本期不做,留待后续)

- N1 自主续跑判定(judge 调用 / nudge 提示让模型"继续干"):本期续跑**只由 inbox 驱动**。模型自身的工具循环(内层)已经是 agentic loop,外层不额外替模型决定"任务没完"。
- N2 ↑ 拉回编辑排队消息、Ctrl+Enter 立即发送(CC 有,本期不做)。
- N3 附件 / 技能选择 / 工具勾选的 mid-run 变更(第一期 steering 只收纯文本)。
- N4 会话切换解锁 / run 后台化 + 重连补帧(侧栏锁保留)。
- N5 askUser 卡片迁移到 composer 停靠(方案 B,见 §4.2)。
- N6 多标签页 / 多端同步同一 run。
- N7 `convStatePanel` / 子任务面板 / 定时面板的交互增强(长 run 期间现有轮询机制不变,仅确认不回归)。
- N8 对话 md 导出的多气泡格式优化(现有 DOM 顺序导出天然兼容,不做专门改造)。

### 反目标(做成这样算失败)

- A1 无 steering 输入时行为与今天不一致(本特性必须是纯增量:inbox 空 → 单迭代,帧序列与现状完全相同)。
- A2 用户输入静默丢失(CC 已知 queued-input-dropped 坑;任何未注入的输入必须有可见终态)。
- A3 "聊聊这个"走 cancel/超时路径返回"用户未作答"(模型会误以为被拒绝;必须走 answer 通道合法返回)。
- A4 在 assistant tool_call 与对应 tool_result 之间插入 user 消息(provider API 契约违反 + ChatMemory 污染)。
- A5 子任务 / 定时任务获得 steering 或续跑能力(递归护栏哲学:疑问写进执行结果,由主任务决定)。
- A6 刷新页面后气泡分组与 live 视图不同。
- A7 admin 流水时间线倒序(steering USER 行被 B2 重写拉回会话创建时刻,出现在本轮工具调用之前)。
- A8 断连/刷新后 orphan run 继续写 memory/usage(现有 SSE lifecycle 不 dispose 订阅,长 run 放大此风险)。
- A9 CHAT 态用户文本静默丢失(提交到已失效 qid 收 404 却无提示;或历史 result 2048 截断丢话)。

## 3. 现状与约束(代码事实,2026-11-21 核实)

| 事实 | 位置 | 对设计的约束 |
|---|---|---|
| Spring AI 2.0.2 `ToolCallingAdvisor` 实现内层工具循环,`adviseCall` = `doInitializeLoop → doBeforeCall → chain.copy(this).nextCall → doAfterCall → UsageAccumulator 累加 → isToolCallResponse 判定 → doGetNextInstructionsForToolCall 拼下一轮 / doFinalizeLoop 收口`;Stream 侧有对称 `*Stream` 钩子;全部 `protected`(javap 核实) | `spring-ai-client-chat-2.0.2.jar` | 官方扩展点 = **继承并覆写钩子**,不是从零手写循环 |
| `MessageChatMemoryAdvisor` order = HIGHEST_PRECEDENCE+200,必须位于 `ToolCallingAdvisor`(+300)上游,否则 auto-register 检测下游 MemoryAdvisor → `conversationHistoryEnabled=false` → 多轮 tool_use 历史丢失 | `LoomAgentConfiguration` javadoc + `Issue1ChatMemoryAdvisorRegressionTest` | 自定义 advisor **保持 +300 order**,memory 仍在上游;此回归锁必须继续绿 |
| SSE 侧信道:`SseEmitterRegistry` 按 `(username, conversationId)` 注册 `Entry(emitter, disposable, startMs, onStop)`;askUser 卡片帧 / 子任务生命周期帧都经它推入同一条 SSE;`stop(username, convId)` 已存在 | `stream/SseEmitterRegistry.java`、`DefaultAskUserTool:124`、`DefaultSubTaskExecutor:171` | steering ack / 迭代事件帧复用同一通道;run 注册表 = SseEmitterRegistry,不另造 |
| askUser 阻塞等待:`AskUserRegistry` pending Map + `CompletableFuture.complete` 先到先得;`POST /ask/{qid}/answer`;超时默认 300s 返回"用户未作答";前端终态 已答✓/已超时/已取消/已失效,freeze 折叠摘要 | `askuser/AskUserRegistry.java`、`DefaultAskUserTool`、`app.js askUserCards` | 双通道 = answer 端点加 mode 字段 + 第五种终态;不动阻塞/超时骨架 |
| 前端一问一答锁:`disableSend()`(textarea+按钮)、`setToolbarLocked()`(知识空间/MCP/技能/文件/新对话 + 侧栏切换锁) | `app.js:1486/1522` | 解锁范围要显式分层(§5.5) |
| 气泡内分段机制已存在:askUser 后 `ui._currentAnswerEl` 重定向到 `askuser-bot-content-{qid}`,同一气泡多个流式写作目标 | `app.js:1968/1986` | 迭代分段复用该机制,不新造 |
| reasoning 流式 delta + 思考落库:实际位于 **`LoomAgentConfiguration`(autoconfigure 模块)WebConfiguration 内联 SSE lambda(~630–760 行)**,非独立 SseController 类;`reasoningAccum` 跨整条流累积,onComplete 一次性 `saveReasoning` 落一行;`lastReasoningLen` 有长度回缩容错(全量重发) | `LoomAgentConfiguration:691–759`、`ChatUsageService.saveReasoning`(seq=max+1)、`DefaultChat.REASONING_CONTENT_KEY` | 多迭代必须按外层迭代 flush+复位(§5.4);改动在 autoconfigure 模块 ⇒ 需 reinstall 该模块 |
| token 落库:`ChatUsageRecordingAdvisor` **order=100**,在 `ToolCallingAdvisor`(HIGHEST_PRECEDENCE+300)**下游** ⇒ 每模型轮被 chain.copy 重入、聚合落一行;`ChatUsageService.record` 自带全零守卫;SSE lambda 另有逐帧 record 路径(chunk usage 全 0 → no-op) | `DefaultChat:219–220`、`token/ChatUsageRecordingAdvisor.java`、`ChatUsageService:46–54` | 外层续跑天然继承逐轮落库;月度统计/callCount 口径不变 |
| **admin 流水 B2 时间戳重写缺陷**:所有 USER 行 ts 被重写为 `conversation.createdAt + i*1ms`(按 list 顺序递增),最后一条 ASSISTANT 重写为 `updatedAt-1ms`,然后按 ts 重排 | `ConversationFlowService:116–147` | steering USER 行(会话中段真实时刻)会被拉回会话创建时刻 ⇒ **时间线倒序,必须修**(§5.9) |
| 前端流开始/结束契约被 IT 锁定:`send-btn.disabled===true` + `stop-btn 可见` = 流开始;`!disabled && '发送消息'` = 结束 | `ChatSmokeBrowserIT:51–64`、`HttpToolBrowserIT:152–159` | RUNNING 态按钮解锁 ⇒ 两个 IT 开始信号需同步改(§5.5 裁定) |
| `state.isStreaming` 有 8 个消费点(createNew/startRename/switchTo/stopStream/abortStream/send 守卫等) | `app.js:1480–2389` | 语义必须保持"有活跃流",输入态另立 inputMode(§5.5) |
| 历史回放:`GET /conversation/{id}` 平铺 Message + `ToolCallHistory.inject` 注入 TOOL_CALL 条目;reasoning 按时间戳就近配对(±10s);`SPRING_AI_CHAT_MEMORY` 无 metadata 列 | `ToolCallHistory`、`ChatReasoningHistory` | 气泡分组只能靠 USER 行边界 + 时间序,不能靠 metadata(§5.6) |
| 子任务:`DefaultSubTaskExecutor` 独立池 `ChatClient.call`,schema 级排除 ISubTaskTool/IScheduleTool/IAskUserTool,memory 命名空间 `{conv}--sub--{id}` | `subtask/DefaultSubTaskExecutor.java` | loop advisor 只装主对话路径,子任务不装(A5) |

## 4. 方案对比与裁定

### 4.1 循环载体

| 方案 | 说明 | 裁定 |
|---|---|---|
| (a) ChatClient 外手写 while 循环 | 自己重做 memory/usage/observation/SSE 集成 | ✗ 重复造轮子,与 Spring AI 生命周期脱节 |
| (b) 独立外层递归 advisor(+250,上游于 ToolCallingAdvisor) | `chain.copy(this).nextCall` 重入 | ✗ 重入点只能覆盖**外层迭代边界**;CC 语义要求工具调用一结束就注入(内层边界),两层 advisor 职责割裂、order 夹缝脆弱 |
| **(c) 继承 `ToolCallingAdvisor`,覆写 protected 钩子** | 内层注入点 = `doGetNextInstructionsForToolCall(+Stream)`;外层续跑 = `doFinalizeLoop(+Stream)`;单 advisor、order 不变(+300) | ✓ **选定**。官方扩展点,内外边界一处管理,memory-order 不变式天然保持 |

风险:auto-register 是否被自定义子类抑制、`conversationHistoryEnabled` 检测是否仍成立 → **Spike S1 前置验证**。

### 4.2 askUser 提问期间的输入

| 方案 | 说明 | 裁定 |
|---|---|---|
| A 锁输入框(死锁) | pending 时 disableSend | ✗ 300s 干等,澄清时刻恰恰最想说话 |
| B 卡片停靠 composer | ChatGPT 式 elicitation | ✗ 推翻 2026-09-21 #3"卡片与回复共用气泡"裁定;live/历史两套渲染路径;并发卡片需队列 |
| C 输入框状态路由(pending 时回车=作答) | 隐式路由 | ✗ CC 本尊不这么做;用户不知道自己的话被怎么处理,歧义靠猜 |
| **D 锁 + 卡片显式双通道出口** | 输入框锁,但卡片恒有"其他(自定义答案)"与"聊聊这个";后者冻结卡片为"转入对话"终态、解锁输入框、文本经 answer 通道 mode=CHAT 回传 | ✓ **选定**。与 CC 协议同构(`answers` vs `response`),消歧靠显式动作不靠状态机 |

### 4.3 气泡拆分

| 方案 | 裁定 |
|---|---|
| 每模型轮一个新气泡 | ✗ 中间独白碎片化刷屏,最终答案不可辨;推翻工具 chip/askUser 内嵌契约 |
| 整个 run 恒一个气泡 | ✗ steering 用户消息插入时间线后,后续输出不能追加到用户新消息上方的旧气泡 |
| **事件驱动:用户插话 → 封口旧气泡 + 用户气泡 + 新 AI 气泡;纯迭代续跑 → 同气泡分段** | ✓ **选定**(讨论中我方提议,用户未反对,随本文档一并审批) |

### 4.4 续跑触发

**inbox 驱动(选定,我的裁定)**:`doFinalizeLoop` 时 inbox 非空 → 续跑;空 → 结束。不做自主 judge/N1。理由:与 CC 的 queued-messages 语义一致、无额外 LLM 调用成本、无弱模型空转风险(Qwen self-talk 前科)、完成判定不需要新机制。

## 5. 设计正文

### 5.1 后端:`LoomAgenticLoopAdvisor extends ToolCallingAdvisor`

新类,主库 `chat` 包。order 保持 `ToolCallingAdvisor.DEFAULT_ORDER`(+300)。装配点 = `DefaultChat` 主对话路径,**必须经 `requestSpec.advisors(...)` 逐请求挂载,不得注册为 ChatClient 默认 advisor 或共享 bean**——`DefaultSubTaskExecutor` / schedule 触发路径复用同一 ChatClient,默认挂载会让子任务继承 loop advisor,违反 A5(S1 验证 auto-register 抑制时一并确认此边界)。替换 auto-register 默认实例的方式由 S1 确定。

**SSE 端点实际位置(核实订正)**:流式端点逻辑不是独立 `SseController` 类,而是 `LoomAgentConfiguration`(autoconfigure 模块)WebConfiguration 内 ~630–760 行的内联 lambda(`emitterRegistry.register` + `chatResponseFlux.subscribe`)。本文档所有"SseController"字样均指该 lambda。改动落在 autoconfigure 模块 ⇒ 测试模块验证前必须 `mvn install -pl spring-ai-loom-agent-spring-boot-autoconfigure`(与库模块 reinstall 同款坑,CLAUDE.md 已有先例)。

**共享运行上下文(新增机制)**:lambda 内的 `lastReasoningLen`/`reasoningAccum` 是局部变量,advisor 够不到。引入 per-run `StreamRunContext`(持 inbox 引用、迭代计数、当前迭代 reasoning 缓冲、delta 状态),由 SSE 端点创建,经 advisor param / toolContext 传给 loop advisor;advisor 在迭代边界回调 context 做 flush/复位。这是 §5.4 reasoning 按迭代落库与 delta 复位的载体。

覆写点:

1. **`doGetNextInstructionsForToolCall` / `...Stream`(内层注入点)**:工具结果就位后、下一轮模型调用前,drain 本 run inbox → 每条 steering 文本构造 `UserMessage` append 进 instructions,并:
   - 经 `SseEmitterRegistry.Entry.emitter` 推 `steerAck` 帧(前端灰态转正);
   - 持久化责任见 §5.6(S2 确定由 advisor history 机制覆盖还是显式 `ChatMemory.add`,防双写)。
2. **`doFinalizeLoop` / `...Stream`(外层续跑点)**:内层循环结束(模型给出无 tool_call 的最终文本)后:
   - 原子地检查 inbox(与 run 注销互斥,见 §5.2 竞态);非空 → drain 注入 + `chain.copy(this).nextCall/nextStream` 续跑,迭代计数 +1;
   - 空 → 正常收口。
   - 每次续跑前检查护栏(§5.7)与 stop 信号;任一触发 → 收口,inbox 残留按 §5.2 stop 语义处置。
   - 续跑开始时推 `iteration` 帧(迭代序号),供前端分段与 reasoning 复位。
3. 流式拼接:`doFinalizeLoopStream` 返回 `Flux.concat(本轮流, 续跑流)`——SSE 单连接不变,SseController 无感知。

**不变式(A1)**:inbox 恒空时,所有覆写退化为 super 行为,帧序列与今天完全一致。

### 5.2 Steering 协议

- **端点**:`POST /spring/ai/loom/stream/{conversationId}/steer`(镜像现有 stop 端点),body `{message: string}`,经 AuthenticationFilter,归属校验 = 会话属于当前 username(镜像 stop 的校验)。文本上限 8KB,超限 400。
- **inbox**:per-`(username, conversationId)` 的并发队列,run 生命周期内存在。载体建议挂在 `SseEmitterRegistry.Entry` 或平行的 `SteeringInboxRegistry`(builder 定,倾向前者:run 注册表与 inbox 同生命周期,少一个注销竞态源)。
- **响应**:`{queued:true, messageId}`(有活跃 run);无活跃 run → `{queued:false}`,前端退化为普通 `chat.send()`。
- **收尾竞态(G2)**:「finalize 检查 inbox」与「run 注销」必须互斥(同一把 per-conversation 锁或 CAS closing 标志)。steer 端点在锁内判断 run 活跃性:活跃 → 入队;正在收口 → 返回 `queued:false`,前端自动转普通发送。**任何路径不得静默吞消息(A2)。**
- **stop 语义(我的裁定,与 CC 有意分歧)**:stop → run 取消,inbox 未注入消息**丢弃但可见**——对应灰态气泡标记"未发送(已停止)",toast 提示条数。不做 CC 式"停止后自动发出"(前端自动重发链路复杂,且用户按停止的意图通常含"别继续了");可见终态满足 A2。
- **注入语义**:当前正在执行的工具**不中断**(A4 硬约束);steering 文本作为 UserMessage 进入下一轮模型输入。system prompt 增补一句引导:"运行中用户补充的消息具有最高优先级,先回应补充再继续原任务"(措辞进契约测试)。

### 5.3 askUser 双通道(G3)

- **answer 端点扩展**:`POST /ask/{qid}/answer` body 增加 `mode` 字段:`ANSWER`(缺省,现状不变)/ `CHAT`。`CHAT` 时 `answer` 字段承载用户自由文本,`CompletableFuture.complete` 的返回值带标记,`DefaultAskUserTool` 返回文本形如:`用户未直接作答,而是说:{原文}`(完整带原话,A3;措辞进 `AskUserGuidanceContractTest` 同款契约锁)。
- **卡片 UI**:选项列表后恒有"其他(自定义答案)"(已有 `allowCustomInput` 通道,= CC Other)+ 新增按钮 **"💬 聊聊这个"**。点击 → 卡片 freeze 为第五种终态 **"💬 转入对话"**(摘要行同款折叠),输入框解锁进入 CHAT 态(§5.5),placeholder"说说你的想法,回车发送"。
- **问题保活**:CHAT 态下 pending question 不注销(工具仍阻塞等待);摘要行提供"返回选项"恢复卡片(复用 `restorePending` 先例),用户可反悔改选选项。提交 CHAT 文本或选项答案后,问题终态落定。
- **多卡 pending**:输入框保持锁直到全部落定;提示条显示"N 个问题待回答";"聊聊这个"作用于最早一张。
- **历史渲染**:`tool-call-renderer.js` 只读卡片增加"转入对话"徽章 + CHAT 文本展示;`ToolCallHistory.inject` 的 arguments/result 形状不变(mode 在 arguments 里自然带出)。
- **超时/stop 路径完全不动**:300s 超时仍返回"用户未作答"。
- **CHAT 态的超时/stop/断连出口(新增,必须覆盖)**:
  - 用户在 CHAT 态打字期间 300s 倒计时**继续跑**;超时触发 → `AskUserRegistry` 哨兵 complete → 卡片 freeze"已超时" → 前端**必须退出 CHAT 态回 RUNNING**(若流仍活)或 IDLE(流已结束),不得停留在"回复 AI 的问题"badge 对着已死卡片。
  - CHAT 态提交到已超时/已取消的 qid → answer 端点 404 → 前端 toast"问题已失效" + 状态复位,**不得**静默吞文本(A2)。
  - stop / 断连 → `cancelAllActive` 现有路径释放 future;前端 CHAT 态同步复位。
  - `AskUserRegistry.complete` 先到先得语义不变:CHAT 提交与选项点击竞态由现有 CAS 兜底,后到者收 409/404 + 前端复位。
- **工具返回文本截断豁免(新增)**:`ToolCallHistory.inject` 的 `result` 上限 2048 字符;CHAT 模式用户原话可达 8KB ⇒ 历史卡片会截断丢话。裁定:CHAT 模式的 result 文本**豁免截断**(与 `askUser.optionsJson` 豁免同款理由:用户输入是卡片语义的唯一来源),或 steering/CHAT 文本上限收紧到 2048——**倾向前者**(豁免),builder 按 S2 持久化结果确认。

### 5.4 SSE 帧增量

复用 `ChatResponseRecord` 扩展模式(先例:`subTaskEvent`/`askUser` 字段):

| 新帧 | 载荷 | 时机 |
|---|---|---|
| `steerAck` | `{messageId}` | inbox 消息被注入时 |
| `iteration` | `{index}` | 外层续跑开始时 |

- **reasoning delta 复位 + 按迭代落库(核实后订正)**:现状是 lambda 内 `reasoningAccum` 跨**整条流**累积,`onComplete` 时 `saveReasoning` **一次性落一行**(seq=max+1,created_at=流结束时刻)。多迭代 run 若沿用,会把所有迭代的思考拼成一行,而 `ChatReasoningHistory.pair`(±10s 双向唯一)只能把它配给一条 ASSISTANT 行 ⇒ 其余迭代的思考在历史/ admin 流水里丢失。**裁定:按外层迭代落库**——`StreamRunContext` 持当前迭代 reasoning 缓冲,loop advisor 在迭代结束边界回调 flush(`saveReasoning` 一行,ts=迭代结束时刻)并复位 `lastReasoningLen`;流结束 flush 残余。**粒度是外层迭代,不是内层工具轮**——无 steering 的单迭代 run(含多工具轮)行为与今天完全一致(A1),`ChatReasoningHistoryTest` 9 测试必须继续绿。前端每迭代开新思考折叠分段。
- **content 语义不动**:前端 `answerText += data.content` 是 append;今天多工具轮已经跨多个模型响应 append 进同一气泡,外层迭代只是更多同类轮次,无新风险。lambda 688 行"text 是全量"注释与实测 append 行为不符(历史遗留注释),**本期不改 content 发送语义**,避免牵连 ChatMemory/回放。
- **usage 落库(核实后订正)**:`ChatUsageRecordingAdvisor` order=**100**,而 `ToolCallingAdvisor` order=HIGHEST_PRECEDENCE+300(极小)⇒ usage advisor 实际在 loop advisor **下游**,`chain.copy(this)` 重入会逐轮重跑它 ⇒ 每模型轮聚合落一行,与今天多工具轮行为同构,外层续跑天然继承。S3 验证重入确实覆盖 usage advisor 且无与 `UsageAccumulator` 的双计;`loom_chat_usage` "每模型轮一行"语义不变,月度统计/`callCount` 口径不变。lambda 729–735 的逐帧 record 路径有 `ChatUsageService.record` 的全零守卫,chunk 帧 usage 全 0 → no-op,不动。

### 5.5 前端改造(`app.js` / `style.css` / `tool-call-renderer.js`)

**输入区状态机**(替换现有 isStreaming 二态):

```
IDLE      : 现状。回车 = chat.send()
RUNNING   : (有活跃流,无 pending askUser)
            textarea 解锁,按钮文案"发送(下一轮)",badge"运行中·补充将在下一轮生效"
            回车 = POST steer → 渲染灰态用户气泡(pending)→ steerAck 转正
            无活跃 run 的 queued:false → 自动转 chat.send()
ASK_PENDING: (有 pending askUser 卡片)
            textarea 锁定,placeholder"回答上方卡片,或点'聊聊这个'自由输入"
            composer 上方提示条:"AI 正在等你回答 · 剩余 m:ss · ↓定位"(点击滚动到卡片;多卡显示数量)
CHAT      : (用户点了"聊聊这个",问题保活)
            textarea 解锁,badge"回复 AI 的问题",回车 = /ask/{qid}/answer mode=CHAT
            提交后回 RUNNING;"返回选项"回 ASK_PENDING
```

- **灰态气泡**:queued 消息立即渲染为灰色用户气泡(与 CC 一致),`steerAck` 转正;stop 时标"未发送(已停止)"。**灰态气泡是 A2 的 UI 兜底:任何时刻用户都能看到自己的话处于什么状态。**
- **事件驱动气泡(G4)**:`steerAck` 到达 → 当前 AI 气泡封口(操作栏落地)→ 灰态用户气泡转正 → 开新 AI 气泡(复用 `renderBotMessage` + `_currentAnswerEl` 重定向)。纯迭代续跑(`iteration` 帧)→ 同气泡内轻量分隔线 + 新写作分段。
- **锁的分层解锁**:textarea/发送按钮按状态机;工具栏(知识空间/MCP/技能/文件/新对话)与侧栏切换锁 **P1 保留**(N3/N4);`+` 附件与 ✎ 画板运行中保持锁定。
- **`state.isStreaming` 语义保持"有活跃流"**(全仓 8 个消费点:createNew/startRename/switchTo 守卫、stopStream、abortStream、send 空文本守卫等),新增 `inputMode` 状态(IDLE/RUNNING/ASK_PENDING/CHAT)只管输入区,**不得**让 RUNNING 态下 isStreaming=false(否则侧栏锁守卫全部失效)。
- **RUNNING 态按钮契约(与存量 IT 的兼容裁定)**:`#send-btn` **不 disabled**、文案"发送消息"不变,改为旁挂小 badge 提示"运行中·回车补充下一轮";`#stop-btn` 显示逻辑不变。理由:`ChatSmokeBrowserIT`/`HttpToolBrowserIT` 的流开始信号断言 `send-btn.disabled===true`,若按钮语义变更需同步改 IT——**裁定为同步改这两个 IT 的开始信号**(改以 `stop-btn 可见 + isStreaming` 判定),按钮可用性变更是特性本意,不迁就旧断言;结束信号(`!disabled && textContent==='发送消息'`)保持可过。
- **发送空文本守卫**:`send()` 现有 `!text && !state.isStreaming` 分支在 RUNNING 态含义变化,输入区状态机接管后此守卫仅 IDLE 态生效。
- **scrollToBottom**:沿用 isAtBottom 守卫 + 浮动按钮(2026-09-21 #2),新气泡/分段不破坏。

### 5.6 持久化与历史一致性(G5)

- **注入的 steering UserMessage 必须落 `SPRING_AI_CHAT_MEMORY`**(USER 行,注入时刻的时间戳/sequence)。写入者由 S2 确定:`ToolCallingAdvisor.conversationHistoryEnabled=true` 的 history 管理若已覆盖 instructions 追加行,则不显式 add(防双写);否则 loop advisor 注入时显式 `ChatMemory.add`。
- **历史气泡分组规则**:`renderMessages` 按 **USER 行切分**——每条 USER 行 = 用户气泡 + 后续 AI 气泡的起点;两条 USER 行之间的所有 ASSISTANT/TOOL_CALL/reasoning 归同一 AI 气泡。steering 注入行天然是 USER 行 ⇒ live 与回放分组一致,**主 UI 回放无需新列/新表**(admin 流水的 B2 倒序修复另需 `loom_chat_steering` 审计表,见 §5.9,两者不冲突:主 UI 按 memory sequence 顺序,不走 ConversationFlowService 的 ts 重写)。
- **迭代分隔线 P1 不进历史**(纯视觉装饰,回放不还原;气泡分组一致即满足 G5/A6)。
- reasoning 配对(±10s 就近)与 TOOL_CALL 注入逻辑不动;多迭代产生更多轮 reasoning 行,`loom_chat_reasoning` append 语义天然兼容。

### 5.7 护栏与配置(G6)

`spring.ai.loom.agent.loop`:

| 键 | 默认 | 语义 |
|---|---|---|
| `max-continuations` | 10 | 单 run 外层续跑次数上限;到达 → 收口并推提示帧"已达续跑上限",inbox 残留按 stop 语义可见处置 |
| `run-timeout-seconds` | 1800 | run 墙钟预算(区别于 `chat.timeout-seconds`=600 的单次 provider 调用超时);到点 → 当前迭代完成后收口 |

- 无 `enabled` 开关(与 M3 以来废弃 enabled 旗标的惯例一致;inbox 空即 no-op)。
- stop 信号(`SseEmitterRegistry.stop` 现有 onStop 机制)在每个内层注入点与外层续跑点检查。
- 子任务/定时任务路径不装 loop advisor(A5);`DefaultSubTaskExecutor` 的 advisor 列表需显式确认不含它(回归锁)。
- **run 墙钟与单次调用超时的关系**:`chat.timeout-seconds`(600s)是单次 provider 调用超时,不变;`run-timeout-seconds`(1800s)是外层 run 预算,在迭代边界检查(当前迭代完成后收口,不硬掐正在进行的模型调用)。两者独立,`ChatProviderTimeoutWiringTest` 必须继续绿。

### 5.8 安全与隔离

- steer 端点走 AuthenticationFilter + 会话归属校验(镜像 stop);inbox 按 `(username, conversationId)` 严格隔离,跨用户/跨会话不可见(与 subtask 隔离同款哲学)。
- steering 文本与 CHAT 文本均为用户可控输入:前端渲染经 `esc()`/textContent(现有惯例),后端进 prompt 前不执行任何解析;8KB 上限。
- 不新增任何工具可见性面(loop advisor 不是工具,不进 RBAC;steering 是所有登录用户的对话能力,与 universal 工具同级)。
- **inbox 深度上限**(新增):per-run 排队上限 20 条,超限 steer 端点返回 429 + 前端 toast"补充消息过多,请等待 AI 处理"。防 spam 撑爆内存/上下文。
- **断连/刷新处置**(新增,核实 `LoomAgentConfiguration:657–672`):`emitter.onTimeout/onCompletion/onError` 现有路径只 `askUserRegistry.cancelAll + autoCleanup`,**不 dispose 订阅** ⇒ 刷新页面后后端 run 可能继续 orphan 跑。本期必须补:断连 → dispose 订阅 + 清 inbox + 灰态气泡随页面消失(前端已丢)。长 run 使刷新概率上升,orphan run 会继续写 memory/usage ⇒ 不可接受。`autoCleanup` 是否已 dispose 由 S5 核实,未则补。

### 5.9 admin 流水时间线修复(G5 延伸,核实后新增)

**缺陷**:`ConversationFlowService:116–147`(B2 修复)把**所有** USER 行 ts 重写为 `conversation.createdAt + i*1ms`,最后一条 ASSISTANT 重写为 `updatedAt-1ms`,再按 ts 重排。原意是修"memory timestamp = 流结束时刻 ⇒ USER 反而晚于本轮 tool_call"的倒序。但 steering USER 行的真实注入时刻在会话中段,被一律拉回 createdAt ⇒ **admin 流水里用户插话出现在所有工具调用之前,时间线彻底倒序**。

**裁定:新增 loom-owned 审计表 `loom_chat_steering`**(确定性标记,沿用项目"精确配对取代时间窗猜测"先例,如 `subtask_id`):

```sql
CREATE TABLE loom_chat_steering (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id VARCHAR(255) NOT NULL,
  username VARCHAR(64) NOT NULL,
  message_text CLOB NOT NULL,
  injected_at TIMESTAMP NOT NULL,
  seq INT NOT NULL          -- 本 run 内注入序号
);
```

- 落点:V1.0 末尾新增段(项目只跑全新库)+ `ensureSchema` 幂等兜底(先例:`JdbcLoomScheduleExecutionRepository.ensureSchema`)。**不动 `SPRING_AI_CHAT_MEMORY`**(Spring AI 共享表,列集合两边必须一致的既有坑)。
- loop advisor 注入 steering UserMessage 时同步写一行审计(`injected_at` = 注入时刻)。
- `ConversationFlowService` B2 重写**豁免**审计表匹配的 USER 行(按 conversation_id + content + injected_at 就近匹配),保留其真实 ts;仅对 turn-initial USER 行沿用 createdAt 锚定。最后 ASSISTANT 的 updatedAt 锚定不动。
- **附带收益**:admin 流水可渲染 steering 事件徽章(「用户插话」),审计可追溯;`computeStats` 可加 steeringCount(可选,builder 定)。
- 主 UI `renderMessages` 不受 B2 影响(它按 memory sequence 顺序 + TOOL_CALL createdAt 注入,不走 ConversationFlowService),§5.6 分组规则不变。
- 回归锁:新增 `ConversationFlowSteeringTimelineTest`(多轮 + 中段插话 → USER 事件 ts 保持注入时刻、不倒序);变异:去掉豁免 ⇒ 红。S4 前置验证匹配规则在 finalize-续跑注入(前序是 ASSISTANT 而非 TOOL)场景下仍正确。

## 6. 回归门与变异自证

1. **存量全绿**:默认 `mvn test -pl spring-ai-loom-agent-test`(494+)+ 显式 `-Dtest='*IT'`(228+,清库三目录)全绿;`Issue1ChatMemoryAdvisorRegressionTest` 必须继续绿(memory-order 不变式)。
2. **A1 契约锁(新增)**:inbox 空时单迭代、SSE 帧序列与改造前一致(帧类型快照断言)。
3. **新契约/单元测试**:
   - steering drain 位置:注入发生在 tool_result 之后、下一次模型调用之前(消息序列断言);
   - finalize 竞态:收口与 steer 并发 → 要么 queued 要么显式 false,无第三态;
   - CHAT 模式工具返回文本含用户原话、非"用户未作答"(扩展 `AskUserGuidanceContractTest`);
   - CHAT 态超时/stop/404 → 前端状态复位 + 文本不静默丢(A9);
   - CHAT result 8KB 历史完整(截断豁免,B6);
   - 护栏:max-continuations / run-timeout 触发收口;
   - 子任务 advisor 列表不含 loop advisor(E1);
   - `isStreaming` 守卫:RUNNING 态 createNew/switchTo/startRename 仍被拦(A2);
   - 断连 dispose:orphan run 不继续写 memory/usage(A8,S5);
   - reasoning 按外层迭代落库:多迭代 run 每迭代一行、无 steering 单迭代 run 仍一行(C5);
   - usage 无重复行:多迭代 run 的 `loom_chat_usage` 行数 = 模型轮数,无 UsageAccumulator 双计(C6);
   - admin 流水 steering 时间线:中段插话 USER 事件 ts 保持注入时刻、不倒序(D1,`ConversationFlowSteeringTimelineTest`);
   - `loom_chat_steering` round-trip + ensureSchema 幂等(C15)。
4. **变异自证**(外部审计面板不可用,沿用变异惯例):
   - 去掉内层 drain → steering 注入测试红;
   - CHAT 模式改走 cancel 路径 → 契约测试红;
   - finalize 不查 inbox → 续跑测试红;
   - 历史分组不按 USER 行切 → 回放一致性测试红;
   - B2 豁免去掉 → admin 流水时间线测试红(D1);
   - reasoning 改回整条流一行 → 多迭代配对测试红(C5);
   - loop advisor 改默认 bean 挂载 → 子任务排除测试红(E1)。
5. **存量 IT 信号同步改(非回归,是契约演进)**:`ChatSmokeBrowserIT` / `HttpToolBrowserIT` 流开始信号从 `send-btn.disabled===true` 改为 `stop-btn 可见 + isStreaming`(RUNNING 态按钮解锁是特性本意);结束信号不变。
6. **BrowserIT 新增**:灰态气泡→ack 转正、插话开新气泡、askUser"聊聊这个"全流程(含超时/stop/返回选项)、提示条定位、多迭代思考分段;composer/气泡/admin 流水改版的截图基线本机 `-DupdateBaselines=true` 重建(index + admin-conversation)。
7. **波及面矩阵逐项核销**:§7.5 A1–F5 每项在实施 PR 里标注处置与验证结果,矩阵作为完工 checklist,防遗漏。

## 7. Spike 清单(实施前置,先于一切产品代码)

| # | 验证目标 | 失败退路 |
|---|---|---|
| S1 | 自定义 `ToolCallingAdvisor` 子类经 `.advisors()` 装配后,`DefaultChatClient.autoRegisterToolCallingAdvisor` 是否抑制默认实例;`conversationHistoryEnabled` 检测(memory 上游)是否仍成立 | 退方案 (b):独立外层 advisor(+250),内层注入改用自定义 ToolCallingManager 包装 |
| S2 | `doGetNextInstructionsForToolCall` 追加的 UserMessage 是否被 advisor 内部 history 机制持久化;显式 `ChatMemory.add` 是否双写 | 确定唯一写入者,契约锁锁死 |
| S3 | `chain.copy(this)` 重入是否逐轮重跑下游 `ChatUsageRecordingAdvisor`(order=100)且无 `UsageAccumulator` 双计;`Flux.concat` 续跑下 SSE lambda 的 delta/accum 行为 | usage 落库点移入 loop advisor 收口处(UsageAccumulator 全量),record 加幂等键 |
| S4 | `loom_chat_steering` 审计匹配在两类注入场景下均正确:内层(前序 TOOL 行)+ finalize 续跑(前序 ASSISTANT 行);B2 豁免后 admin 流水时间线不倒序 | 匹配规则改按 memory sequence_id 对齐(需确认 sequence 在续跑轮的单调性) |
| S5 | `emitterRegistry.autoCleanup` 是否 dispose 订阅;断连后 orphan run 是否继续写 memory/usage | 断连路径补 dispose + inbox 清理(§5.8) |

Spike 代码即抛弃,结论写回本文档 §5 对应小节后再排实施计划。

## 7.5 全量波及面矩阵(2026-11-21 整体分析,防遗漏)

逐项核实代码后的完整影响清单。「处置」列指向本文档对应小节;「验证」列是该项的回归手段。

### A. 主聊天前端(app.js / style.css)

| # | 功能 | 影响 | 处置 | 验证 |
|---|---|---|---|---|
| A1 | 输入框/发送按钮锁 | RUNNING 态解锁,按钮契约变更 | §5.5 | ChatSmoke/HttpTool IT 开始信号同步改 |
| A2 | `state.isStreaming` 8 消费点 | 语义保持"有活跃流",inputMode 另立 | §5.5 | 单测:RUNNING 态 createNew/switchTo/startRename 仍被守卫 |
| A3 | 工具栏锁 + 侧栏切换/新建/重命名锁 | P1 保留 | N3/N4 | IndexInteractionsBrowserIT 不回归 |
| A4 | 气泡渲染/`_currentAnswerEl` 分段 | 事件驱动新气泡 + 迭代分段复用 | §5.5 | 新 BrowserIT |
| A5 | 思考折叠区 | 每迭代新分段;按迭代落库 | §5.4 | 新 BrowserIT + ChatReasoningHistoryTest 继续绿 |
| A6 | 子任务 chip / askUser 卡片 / 工具 chip 内嵌 | 机制不动,跨迭代归属同一气泡 | §5.5 | 存量 subtask/askUser BrowserIT |
| A7 | scrollToBottom isAtBottom 守卫 + 浮动按钮 | 新气泡/分段不破坏 | §5.5 | 存量 BrowserIT |
| A8 | 停止按钮 / stopStream | 语义不变;inbox 丢弃可见 | §5.2 | 新单测 + BrowserIT |
| A9 | 灰态排队气泡(新增) | queued→ack 转正→stop 标记 | §5.5 | 新 BrowserIT |
| A10 | 附件 `+` / 画板 ✎ / imageUpload 管线 | 运行中保持锁定;steer 纯文本不带 fileIds | N3 | FeatureGate/CanvasBoard IT 不回归 |
| A11 | 技能 picker / selectedSkillTag | 运行中锁定;`onSendSuccess` 仍在流结束触发 | §5.5 | 存量 IT |
| A12 | 会话自动重命名 maybeAutoRename | 仍由 turn-initial 消息触发,steering 不触发 | §5.5 | 单测 |
| A13 | convStatePanel(子任务/定时状态条) | 长 run 期间轮询不受影响;流结束 refresh 不变 | 无改动 | 存量 IT |
| A14 | 历史 renderMessages(USER 行分组) | 分组规则明确化;TOOL_CALL/reasoning 注入不动 | §5.6 | 回放一致性测试 |
| A15 | markdown 渲染/esc 转义/origin 复制下载 | steering 文本走同一 esc 管线;多气泡各自操作栏 | §5.5 | 存量 + 新 BrowserIT |
| A16 | 下载对话 md | 多 AI 气泡导出顺序按 DOM,天然兼容 | 无改动 | 手工冒烟 |

### B. askUser 子系统

| # | 功能 | 影响 | 处置 | 验证 |
|---|---|---|---|---|
| B1 | 卡片渲染/freeze/四终态 | 新增第五终态"转入对话" | §5.3 | 新 BrowserIT |
| B2 | answer 端点 / AskUserRegistry CAS | mode=CHAT 扩展;先到先得不变 | §5.3 | 单测:CHAT/选项竞态 |
| B3 | 300s 超时 / cancelAll / 断连释放 | CHAT 态必须同步复位;倒计时不暂停 | §5.3 | 新单测 + BrowserIT |
| B4 | 阻塞工具线程 / ChatMemory ON_COMPLETE | CHAT 返回合法文本,非"用户未作答" | §5.3/A3 | AskUserGuidanceContractTest 扩展 + 变异 |
| B5 | tool-call-renderer 只读卡片(admin 共用) | 新终态徽章 + CHAT 文本展示 | §5.3 | admin conversation BrowserIT |
| B6 | ToolCallHistory result 2048 截断 | CHAT 文本豁免截断 | §5.3 | 单测:8KB CHAT 文本历史完整 |
| B7 | 子任务/定时 schema 级排除 askUser | 不变 | A5 | 存量回归 |
| B8 | 多卡 pending | 输入锁到全部落定;提示条计数 | §5.3 | 新单测 |

### C. 后端流式 / advisor / 持久化

| # | 功能 | 影响 | 处置 | 验证 |
|---|---|---|---|---|
| C1 | ToolCallingAdvisor 内层循环 | 子类覆写;inbox 空退化 super | §5.1/A1 | A1 帧序列契约锁 + 变异 |
| C2 | MessageChatMemoryAdvisor order 不变式 | +300 保持,memory 上游 | §5.1 | Issue1ChatMemoryAdvisorRegressionTest 必须绿 |
| C3 | auto-register / conversationHistoryEnabled | S1 验证子类抑制与 history 检测 | §7 S1 | spike + 回归 |
| C4 | SSE lambda delta/accum(局部变量) | StreamRunContext 共享;按迭代 flush | §5.1/§5.4 | S3 + 新单测 |
| C5 | saveReasoning 一行/流 → 一行/外层迭代 | 无 steering 行为不变 | §5.4 | ChatReasoningHistoryTest 9 测试绿 + 新多迭代测试 |
| C6 | ChatUsageRecordingAdvisor(order=100,下游) | 逐轮重入落库,口径不变 | §5.4 | S3 + 单测无重复行 |
| C7 | lambda 逐帧 usage record(全零守卫) | no-op,不动 | §5.4 | 无改动 |
| C8 | SseEmitterRegistry / stop / autoCleanup | inbox 挂载;断连 dispose 补全 | §5.2/§5.8 | S5 + 新单测 |
| C9 | emitter onTimeout/onCompletion/onError | 补 dispose + inbox 清理;askUser cancelAll 不动 | §5.8 | S5 |
| C10 | ToolContext / ToolCallContextHolder | 跨迭代不变;LATEST 覆盖语义不受影响 | 无改动 | 存量工具日志 IT |
| C11 | LoggingToolCallback / JsonLenient | 不动 | 无改动 | JsonLenientTest 等存量 |
| C12 | DefaultChat onErrorResume → toErrorResponse | 续跑中某迭代失败 → 错误帧 + 收口;inbox 残留可见处置 | §5.2 | 新单测 |
| C13 | chat.timeout-seconds(600s 单调用) | 不变;run-timeout 1800s 是外层新键 | §5.7 | ChatProviderTimeoutWiringTest 绿 |
| C14 | ChatMemory 写入责任 | S2 防双写 | §5.6/§7 S2 | spike + 单测 |
| C15 | loom_chat_steering 新表 | V1.0 段 + ensureSchema | §5.9 | 清库启动 + round-trip 单测 |

### D. admin 控制台 / 统计

| # | 功能 | 影响 | 处置 | 验证 |
|---|---|---|---|---|
| D1 | conversation.html 流水 B2 时间戳重写 | **缺陷**:steering USER 被拉回 createdAt → 倒序;审计表豁免修复 | §5.9 | ConversationFlowSteeringTimelineTest + 变异 |
| D2 | 流水 USER/ASSISTANT/TOOL_CALL 渲染 | steering 行带「用户插话」徽章;分组按真实 ts | §5.9 | admin BrowserIT |
| D3 | SCHEDULE_FIRE/SUBTASK 配对 | 不动(subtask 无 loop);长 run 中 schedule 触发仍走子任务路径 | 无改动 | ConversationFlowScheduleFiresTest 等存量绿 |
| D4 | user.html 月度 token 柱图 | 口径不变(每模型轮一行);长 run token 增多是真实用量 | §5.4 | 存量 IT |
| D5 | stats.html 双 tab / callCount | byConversation count(*) 语义不变 | 无改动 | 存量 IT |
| D6 | computeStats 工具/子任务/错误计数 | 不动;steeringCount 可选新增 | §5.9 | builder 定 |
| D7 | admin 重置密码/角色授权/市场 | 零波及 | 无改动 | 存量 IT |
| D8 | 截图基线(admin-conversation 等) | 流水徽章/时间线变化 → 本机重建 | §6 | VisualBaselineBrowserIT -DupdateBaselines |

### E. 子任务 / 定时 / 其他工具面

| # | 功能 | 影响 | 处置 | 验证 |
|---|---|---|---|---|
| E1 | DefaultSubTaskExecutor 共享 ChatClient | loop advisor 必须逐请求挂载,不得默认/bean 化 | §5.1 | 单测:子任务 advisor 列表不含 loop |
| E2 | schedule 触发子任务 | 同 E1;无 steering/续跑 | A5 | 存量 schedule IT |
| E3 | 子任务 600s 超时 / max-concurrent | 不动 | 无改动 | 存量 |
| E4 | RBAC 工具过滤 / universal | loop advisor 非工具,不进能力面;steer 端点走 AuthenticationFilter + 归属校验 | §5.8 | RbacToolsBrowserIT 绿 |
| E5 | MCP 工具 / http 工具 | 不动;长 run 中工具调用次数增多属正常 | 无改动 | *Http* IT 绿 |
| E6 | 知识空间 / 文件管理 / 画板 | 运行中锁保留;恒可见按钮不与门控联动 | N3 | FeatureGateBrowserIT 绿 |

### F. 构建 / 测试基础设施

| # | 项 | 影响 | 处置 |
|---|---|---|---|
| F1 | autoconfigure 模块改动 | 测试模块经 m2 加载 ⇒ 必须 `mvn install -pl spring-ai-loom-agent-spring-boot-autoconfigure` | §5.1 已注明;CLAUDE.md 同款坑先例 |
| F2 | V1.0 schema 新增段 | 已有库必须清库重跑(项目既定升级策略) | §5.9 |
| F3 | 存量 BrowserIT 开始/结束信号 | ChatSmoke/HttpTool 开始信号改判 | §5.5 |
| F4 | 默认 test 不跑 *IT | IT 门仍需显式 -Dtest + 清三目录 | §6 |
| F5 | 截图基线 | composer/气泡/流水改版 → 本机重建 | §6 |

## 8. Builder 裁量项(无需回问用户)

- inbox 载体具体类/挂载位置(倾向 SseEmitterRegistry.Entry);竞态互斥的具体实现(锁 vs CAS)。
- `iteration`/`steerAck` 帧字段命名与 `ChatResponseRecord` 兼容构造器形态。
- 提示条/分隔线/灰态气泡的具体样式(复用现有视觉 token,a11y 色板不得漂移,`A11yTokenDriftContractTest` 守卫)。
- CHAT 态"返回选项"的交互细节。
- reasoning 复位用显式帧信号还是长度回缩检测(倾向显式)。
- usage 落库口径已核实为"每模型轮一行"(order=100 下游逐轮重入),S3 仅验证无双计;若 S3 推翻则落库点移入 loop advisor 收口处。
- `loom_chat_steering` 审计表字段/索引细节、ensureSchema 实现、admin 流水「用户插话」徽章样式、steeringCount 是否进 computeStats。
- StreamRunContext 的具体载体(advisor param vs toolContext vs registry 挂载)与迭代边界回调签名。
- 断连 dispose 的补全位置(autoCleanup 内 vs emitter lifecycle 回调内,S5 定)。
- i18n 文案措辞。

## 9. 已裁定事项追溯(讨论记录)

| 裁定 | 来源 |
|---|---|
| 内层工具循环已存在,增量 = 外层续跑 + steering | 用户提问 → javap 核实 2.0.2 `ToolCallingAdvisor` |
| 输入解锁 + 下一轮注入(steering 语义) | 用户明确提议 |
| 气泡按事件拆而非按轮拆 | 我方提议,用户未反对,随本文档审批 |
| askUser 锁输入 + 卡片双通道出口(方案 D) | 用户提议锁/停靠 → CC 官方协议调研后收敛 |
| 续跑仅 inbox 驱动,不做自主 judge | 我方裁定(§4.4),随本文档审批 |
| stop 丢弃 inbox 但可见(与 CC 分歧) | 我方裁定(§5.2),随本文档审批 |
| P1 纯文本、工具栏/侧栏锁保留 | 我方裁定(范围收敛),随本文档审批 |
| reasoning 按外层迭代落库(非整条流一行) | 整体分析核实 `saveReasoning` 现状后裁定(§5.4),防多迭代思考丢失 |
| 新增 `loom_chat_steering` 审计表修 admin 流水 B2 倒序 | 整体分析核实 `ConversationFlowService:116–147` 后裁定(§5.9),确定性标记优于时间窗启发式(项目先例) |
| RUNNING 态 send-btn 解锁 + 同步改 ChatSmoke/HttpTool IT 开始信号 | 整体分析核实 IT 断言后裁定(§5.5),按钮可用性是特性本意 |
| 断连/刷新必须 dispose 订阅 + 清 inbox | 整体分析核实 SSE lifecycle 不 dispose 后裁定(§5.8/A8) |
| CHAT result 豁免 2048 截断 | 整体分析核实 ToolCallHistory 截断后裁定(§5.3/A9) |
| loop advisor 逐请求挂载,不得默认/bean 化 | 整体分析核实子任务共享 ChatClient 后裁定(§5.1/E1) |
| usage advisor order=100 在下游,逐轮重入落库口径不变 | 整体分析 javap+代码核实(§5.4),S3 验证 |

## 10. 参考

- [Claude Code SDK — Handle approvals and user input](https://code.claude.com/docs/en/agent-sdk/user-input):`answers` vs `response` 双通道、"The user responded: …"、Other 自由文本传文本本身
- [Claude Code — How Claude Code works](https://code.claude.com/docs/en/how-claude-code-works):interrupt & steer,工具调用结束同 turn 读取
- [Claude Code — Interactive mode: Queue messages while Claude works](https://code.claude.com/docs/en/interactive-mode):灰态 queued、turn 结束自动发送、↑ 编辑
- [claude-code#33511](https://github.com/anthropics/claude-code/issues/33511):"Chat about this" 错发 is_error 的死键 bug(A3 反面教材)
- [claude-code#27348](https://github.com/anthropics/claude-code/issues/27348):preview 模式自定义输入缺陷
- [MCP Elicitation 规范](https://modelcontextprotocol.io/specification/2025-06-18/client/elicitation):accept/decline/cancel 三态,客户端必须提供拒绝/取消出口
- Spring AI 2.0.2 `spring-ai-client-chat` jar javap:`ToolCallingAdvisor` protected 钩子全集、`CallAdvisorChain.copy(Advisor)`、`UsageAccumulator`
- 本仓:`Issue1ChatMemoryAdvisorRegressionTest`、`AskUserGuidanceContractTest`、`SseEmitterRegistry`、`app.js` §5/§7