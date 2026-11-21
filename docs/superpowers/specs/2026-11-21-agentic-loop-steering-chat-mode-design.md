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

### 非目标(本期不做,留待后续)

- N1 自主续跑判定(judge 调用 / nudge 提示让模型"继续干"):本期续跑**只由 inbox 驱动**。模型自身的工具循环(内层)已经是 agentic loop,外层不额外替模型决定"任务没完"。
- N2 ↑ 拉回编辑排队消息、Ctrl+Enter 立即发送(CC 有,本期不做)。
- N3 附件 / 技能选择 / 工具勾选的 mid-run 变更(第一期 steering 只收纯文本)。
- N4 会话切换解锁 / run 后台化 + 重连补帧(侧栏锁保留)。
- N5 askUser 卡片迁移到 composer 停靠(方案 B,见 §4.2)。
- N6 多标签页 / 多端同步同一 run。

### 反目标(做成这样算失败)

- A1 无 steering 输入时行为与今天不一致(本特性必须是纯增量:inbox 空 → 单迭代,帧序列与现状完全相同)。
- A2 用户输入静默丢失(CC 已知 queued-input-dropped 坑;任何未注入的输入必须有可见终态)。
- A3 "聊聊这个"走 cancel/超时路径返回"用户未作答"(模型会误以为被拒绝;必须走 answer 通道合法返回)。
- A4 在 assistant tool_call 与对应 tool_result 之间插入 user 消息(provider API 契约违反 + ChatMemory 污染)。
- A5 子任务 / 定时任务获得 steering 或续跑能力(递归护栏哲学:疑问写进执行结果,由主任务决定)。
- A6 刷新页面后气泡分组与 live 视图不同。

## 3. 现状与约束(代码事实,2026-11-21 核实)

| 事实 | 位置 | 对设计的约束 |
|---|---|---|
| Spring AI 2.0.2 `ToolCallingAdvisor` 实现内层工具循环,`adviseCall` = `doInitializeLoop → doBeforeCall → chain.copy(this).nextCall → doAfterCall → UsageAccumulator 累加 → isToolCallResponse 判定 → doGetNextInstructionsForToolCall 拼下一轮 / doFinalizeLoop 收口`;Stream 侧有对称 `*Stream` 钩子;全部 `protected`(javap 核实) | `spring-ai-client-chat-2.0.2.jar` | 官方扩展点 = **继承并覆写钩子**,不是从零手写循环 |
| `MessageChatMemoryAdvisor` order = HIGHEST_PRECEDENCE+200,必须位于 `ToolCallingAdvisor`(+300)上游,否则 auto-register 检测下游 MemoryAdvisor → `conversationHistoryEnabled=false` → 多轮 tool_use 历史丢失 | `LoomAgentConfiguration` javadoc + `Issue1ChatMemoryAdvisorRegressionTest` | 自定义 advisor **保持 +300 order**,memory 仍在上游;此回归锁必须继续绿 |
| SSE 侧信道:`SseEmitterRegistry` 按 `(username, conversationId)` 注册 `Entry(emitter, disposable, startMs, onStop)`;askUser 卡片帧 / 子任务生命周期帧都经它推入同一条 SSE;`stop(username, convId)` 已存在 | `stream/SseEmitterRegistry.java`、`DefaultAskUserTool:124`、`DefaultSubTaskExecutor:171` | steering ack / 迭代事件帧复用同一通道;run 注册表 = SseEmitterRegistry,不另造 |
| askUser 阻塞等待:`AskUserRegistry` pending Map + `CompletableFuture.complete` 先到先得;`POST /ask/{qid}/answer`;超时默认 300s 返回"用户未作答";前端终态 已答✓/已超时/已取消/已失效,freeze 折叠摘要 | `askuser/AskUserRegistry.java`、`DefaultAskUserTool`、`app.js askUserCards` | 双通道 = answer 端点加 mode 字段 + 第五种终态;不动阻塞/超时骨架 |
| 前端一问一答锁:`disableSend()`(textarea+按钮)、`setToolbarLocked()`(知识空间/MCP/技能/文件/新对话 + 侧栏切换锁) | `app.js:1486/1522` | 解锁范围要显式分层(§5.5) |
| 气泡内分段机制已存在:askUser 后 `ui._currentAnswerEl` 重定向到 `askuser-bot-content-{qid}`,同一气泡多个流式写作目标 | `app.js:1968/1986` | 迭代分段复用该机制,不新造 |
| reasoning 流式 delta:SseController `lastReasoningLen` 状态机,`reasoningContent` metadata 单响应内累积 | `SseController`、`DefaultChat.REASONING_CONTENT_KEY` | 跨迭代是新 ChatResponse,累积长度回缩 → delta 状态机必须按迭代复位(§5.4) |
| token 落库:`ChatUsageRecordingAdvisor`(Call+Stream)用 `ChatClientMessageAggregator` 聚合后落库;空 metadata `getUsage()` 返回 `DefaultUsage(0,0,0)` 非 null,按值跳过 | `token/ChatUsageRecordingAdvisor.java` | 多迭代拼接流的聚合行为需 spike 验证(S3) |
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

新类,主库 `chat` 包。order 保持 `ToolCallingAdvisor.DEFAULT_ORDER`(+300)。装配点 = `DefaultChat` 主对话路径(替换 auto-register 的默认实例,方式由 S1 确定);**不装**进 `DefaultSubTaskExecutor` / schedule 触发路径。

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

### 5.4 SSE 帧增量

复用 `ChatResponseRecord` 扩展模式(先例:`subTaskEvent`/`askUser` 字段):

| 新帧 | 载荷 | 时机 |
|---|---|---|
| `steerAck` | `{messageId}` | inbox 消息被注入时 |
| `iteration` | `{index}` | 外层续跑开始时 |

- **reasoning delta 复位**:SseController 收到 `iteration` 帧时重置 `lastReasoningLen`(或等价地:检测累积长度回缩自动复位——builder 择一,倾向前者,显式信号不靠启发式)。前端同步开新思考折叠分段。
- **usage 落库**:S3 验证 `ChatUsageRecordingAdvisor` 对拼接流的聚合行为;若聚合只覆盖末迭代,落库点移入 loop advisor 收口处用 `UsageAccumulator` 全量(每轮 `loom_chat_usage` 语义保持"每 run 一行"或改"每迭代一行",builder 按 S3 结果定,回归锁跟着改)。

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
- **scrollToBottom**:沿用 isAtBottom 守卫 + 浮动按钮(2026-09-21 #2),新气泡/分段不破坏。

### 5.6 持久化与历史一致性(G5)

- **注入的 steering UserMessage 必须落 `SPRING_AI_CHAT_MEMORY`**(USER 行,注入时刻的时间戳/sequence)。写入者由 S2 确定:`ToolCallingAdvisor.conversationHistoryEnabled=true` 的 history 管理若已覆盖 instructions 追加行,则不显式 add(防双写);否则 loop advisor 注入时显式 `ChatMemory.add`。
- **历史气泡分组规则**:`renderMessages` 按 **USER 行切分**——每条 USER 行 = 用户气泡 + 后续 AI 气泡的起点;两条 USER 行之间的所有 ASSISTANT/TOOL_CALL/reasoning 归同一 AI 气泡。steering 注入行天然是 USER 行 ⇒ live 与回放分组一致,**无需新列/新表**。
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

### 5.8 安全与隔离

- steer 端点走 AuthenticationFilter + 会话归属校验(镜像 stop);inbox 按 `(username, conversationId)` 严格隔离,跨用户/跨会话不可见(与 subtask 隔离同款哲学)。
- steering 文本与 CHAT 文本均为用户可控输入:前端渲染经 `esc()`/textContent(现有惯例),后端进 prompt 前不执行任何解析;8KB 上限。
- 不新增任何工具可见性面(loop advisor 不是工具,不进 RBAC;steering 是所有登录用户的对话能力,与 universal 工具同级)。

## 6. 回归门与变异自证

1. **存量全绿**:默认 `mvn test -pl spring-ai-loom-agent-test`(494+)+ 显式 `-Dtest='*IT'`(228+,清库三目录)全绿;`Issue1ChatMemoryAdvisorRegressionTest` 必须继续绿(memory-order 不变式)。
2. **A1 契约锁(新增)**:inbox 空时单迭代、SSE 帧序列与改造前一致(帧类型快照断言)。
3. **新契约/单元测试**:
   - steering drain 位置:注入发生在 tool_result 之后、下一次模型调用之前(消息序列断言);
   - finalize 竞态:收口与 steer 并发 → 要么 queued 要么显式 false,无第三态;
   - CHAT 模式工具返回文本含用户原话、非"用户未作答"(扩展 `AskUserGuidanceContractTest`);
   - 护栏:max-continuations / run-timeout 触发收口;
   - 子任务 advisor 列表不含 loop advisor。
4. **变异自证**(外部审计面板不可用,沿用变异惯例):
   - 去掉内层 drain → steering 注入测试红;
   - CHAT 模式改走 cancel 路径 → 契约测试红;
   - finalize 不查 inbox → 续跑测试红;
   - 历史分组不按 USER 行切 → 回放一致性测试红。
5. **BrowserIT 新增**:灰态气泡→ack 转正、插话开新气泡、askUser"聊聊这个"全流程、提示条定位;composer 改版的截图基线本机 `-DupdateBaselines=true` 重建。

## 7. Spike 清单(实施前置,先于一切产品代码)

| # | 验证目标 | 失败退路 |
|---|---|---|
| S1 | 自定义 `ToolCallingAdvisor` 子类经 `.advisors()` 装配后,`DefaultChatClient.autoRegisterToolCallingAdvisor` 是否抑制默认实例;`conversationHistoryEnabled` 检测(memory 上游)是否仍成立 | 退方案 (b):独立外层 advisor(+250),内层注入改用自定义 ToolCallingManager 包装 |
| S2 | `doGetNextInstructionsForToolCall` 追加的 UserMessage 是否被 advisor 内部 history 机制持久化;显式 `ChatMemory.add` 是否双写 | 确定唯一写入者,契约锁锁死 |
| S3 | `doFinalizeLoopStream` Flux.concat 续跑下:SseController delta 状态机、`ChatUsageRecordingAdvisor` 聚合、`ChatClientMessageAggregator` 的实际行为 | usage 落库点移入 loop advisor 收口处(UsageAccumulator 全量) |

Spike 代码即抛弃,结论写回本文档 §5 对应小节后再排实施计划。

## 8. Builder 裁量项(无需回问用户)

- inbox 载体具体类/挂载位置(倾向 SseEmitterRegistry.Entry);竞态互斥的具体实现(锁 vs CAS)。
- `iteration`/`steerAck` 帧字段命名与 `ChatResponseRecord` 兼容构造器形态。
- 提示条/分隔线/灰态气泡的具体样式(复用现有视觉 token,a11y 色板不得漂移,`A11yTokenDriftContractTest` 守卫)。
- CHAT 态"返回选项"的交互细节。
- reasoning 复位用显式帧信号还是长度回缩检测(倾向显式)。
- usage 每 run 一行 vs 每迭代一行(按 S3 结果)。
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

## 10. 参考

- [Claude Code SDK — Handle approvals and user input](https://code.claude.com/docs/en/agent-sdk/user-input):`answers` vs `response` 双通道、"The user responded: …"、Other 自由文本传文本本身
- [Claude Code — How Claude Code works](https://code.claude.com/docs/en/how-claude-code-works):interrupt & steer,工具调用结束同 turn 读取
- [Claude Code — Interactive mode: Queue messages while Claude works](https://code.claude.com/docs/en/interactive-mode):灰态 queued、turn 结束自动发送、↑ 编辑
- [claude-code#33511](https://github.com/anthropics/claude-code/issues/33511):"Chat about this" 错发 is_error 的死键 bug(A3 反面教材)
- [claude-code#27348](https://github.com/anthropics/claude-code/issues/27348):preview 模式自定义输入缺陷
- [MCP Elicitation 规范](https://modelcontextprotocol.io/specification/2025-06-18/client/elicitation):accept/decline/cancel 三态,客户端必须提供拒绝/取消出口
- Spring AI 2.0.2 `spring-ai-client-chat` jar javap:`ToolCallingAdvisor` protected 钩子全集、`CallAdvisorChain.copy(Advisor)`、`UsageAccumulator`
- 本仓:`Issue1ChatMemoryAdvisorRegressionTest`、`AskUserGuidanceContractTest`、`SseEmitterRegistry`、`app.js` §5/§7