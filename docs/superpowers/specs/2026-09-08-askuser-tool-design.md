# AskUser 交互工具(#1)设计 spec

> 日期:2026-09-08
> 状态:已获批(分节设计 §A/§B/§C/§D + 子任务排除修订全部确认)
> 前序:`docs/superpowers/roadmap-2026-09-four-items.md` #1 节(调研结论);#4(审批流)、#2(文档清理)、#3(H2 向量存储)均已落地
> 后继:writing-plans → SDD 实施;完成后触发概览图重生成(工具组 8→9)

---

## 0. 目标与非目标

**目标**
1. 新增 LLM 可调用工具 `askUser`:聊天流内以**内嵌问题卡片**(单选/多选/自定义输入)向当前用户提问,**阻塞等待作答**(≤5 分钟),答案以 **tool_result 形态回到同一条流**,LLM 无缝继续 —— 最接近 Claude CLI AskUserQuestion 体验。
2. universal 工具(`@ToolGroup(defaultGranted=true)`),不进 RBAC / `role_tool` 表,零 CapabilityService 改动。
3. 超时/stop 路径**必须让 Flux 正常 complete 或走既有 CANCEL 语义**,保住 `LastChunkMessageChatMemoryAdvisor` 只在 ON_COMPLETE 写库的硬约束(不新增丢轮场景)。
4. **子任务/定时任务不能提问**:schema 级排除(子任务 LLM 根本看不到 askUser 工具),契约文档化为"子任务只做主任务规划好的执行并返回结果;疑问写进执行结果,由主任务决定是否向用户提问"。

**非目标(YAGNI,已裁决砍掉)**
- 多问题批量卡片(单次调用只问一个问题;LLM 需要多答案时连续多次调用)。
- 卡片持久化/刷新恢复:卡片仅活于当前流;刷新页面后历史里只有文本(工具结果在 ChatMemory 中是文本形态)。问题生命周期 ≤5 分钟,重启即失效可接受。
- DB 落库(Registry 纯内存)。
- 运行时守卫拒绝子任务调用(schema 级排除已足够;防御不存在的攻击面)。
- 永不超时 / 长超时(裁决:固定 5 分钟,见 D2)。
- 模态弹窗形态(裁决:聊天流内嵌卡片,见 D1)。

## 1. 已拍板决策

| # | 决策点 | 裁决 |
|---|--------|------|
| D1 | 卡片形态 | **聊天流内嵌卡片**:作为特殊 bot 消息渲染在流内,作答后就地变"已答 ✓";超时置灰"已超时";stop 显示"已取消" |
| D2 | 超时策略 | **固定 5 分钟**(`future.get(5, MINUTES)`,超时可注入供测试);超时返回"用户未在 5 分钟内作答"文本给 LLM,Flux 正常 complete,ChatMemory 本轮保住;超时值走配置属性(默认 300s) |
| D3 | 提问粒度 | **单问题**:一次工具调用 = 一个 question + 一组 options = 一个 questionId ↔ 一个 Future ↔ 一张卡片 |
| D4 | 卡片字段 | **基础字段 + header 标题 + options 数量上限(2-4) + background 背景说明**:见 §3 AskUserEvent |
| D5 | 核心路径 | **路径 1 阻塞同流**:SSE 推卡片事件 → CompletableFuture 入 Registry → `future.get(timeout)` 阻塞 → `POST /ask/{questionId}/answer` complete → 工具 return 用户选择 → 同流继续。(路径 2 两轮往返 / 路径 3 轮询式否决) |
| D6 | 子任务排除 | **schema 级排除**:`DefaultSubTaskExecutor` 的 self-tool 过滤器追加 `IAskUserTool`(与防递归过滤同点位);定时任务经子任务路径执行,自动继承排除。`ISubTaskTool.startSubTask` @Tool description + `ISubTaskExecutor` javadoc 写入委派契约文案(描述能力,不硬编码工具名) |
| D7 | stop 集成 | `SseEmitterRegistry.register` 的 onStop 回调链追加 `askUserRegistry.cancelAll(username, conversationId)` —— 以哨兵值 complete 挂起 Future **释放被阻塞的工具线程**(Flux dispose 本身不中断 `future.get()`,必须显式 complete) |
| D8 | answer 端点鉴权 | `UserContextHolder` 取 username 与 PendingQuestion.username 不符 → **统一 404 "not found"**(复制 `DefaultSubTaskTool` 防存在性泄露先例);Registry 已 remove(超时竞态)→ 404 "问题已失效" |
| D9 | 阻塞线程 | 默认在 Spring AI 同步 tool 执行线程阻塞(ForkJoinPool common —— subtask 已有同款无超时 `future.get()` 先例在跑);**若 Chrome 复验发现主流内容帧卡顿**,兜底方案:tool 执行处包 `boundedElastic`(写入 plan 作为条件步骤,不预防性引入) |

## 2. 现状(权威事实,2026-09-08 子 agent 逐行核实)

- **SSE 单帧类型**:`model/ChatResponseRecord.java` = `record ChatResponseRecord(String content, String reasoningContent)`,发送点唯一:`LoomAgentConfiguration.SseController`(嵌套 @RestController,L556)`emitter.send(new ChatResponseRecord(...), MediaType.APPLICATION_JSON)`(~L627)。
- **SseEmitterRegistry**(`stream/SseEmitterRegistry.java`):按 `username → (conversationId → Entry)` 键控;`register(username, conversationId, SseEmitter, Disposable, Runnable onStop)`(L27)、`get(username, conversationId)`(L33)、`stop(...)`(L44:跑 onStop → dispose → emitter.complete);`Entry` = `(SseEmitter emitter, Disposable disposable, long startMs, Runnable onStop)`(L125)。
- **stop 按钮路径**:前端 `stopStream()`(app.js L1733-1760)→ `POST /spring/ai/loom/stream/{conversationId}/stop`(SseController ~L679)→ `emitterRegistry.stop()` → onStop + dispose + complete。
- **ToolContext 注入**(`chat/DefaultChat.java` L193-208):`username` / `parentConversationId`(空则生成 UUID,L72-74)/ `enabledKnowledgeIds`(非空时)/ `baseUrl`;`requestSpec.toolContext(props)`。工具方法读法先例:`DefaultSubTaskTool.readContextString(toolContext, key)`(L34-38)。
- **ChatMemory 陷阱**:`memory/LastChunkMessageChatMemoryAdvisor.java` `doFinally` 显式只在 `SignalType.ON_COMPLETE` 写库(L97-104);CANCEL/ERROR 整轮丢失。
- **子任务阻塞先例**:`subtask/DefaultSubTaskExecutor.execute()` L148 `future.get()` **无超时**;cancel 走 SubTaskRegistry kill-hook(constructor Consumer + `registry.kill(username, subTaskId)`);`attachFuture` 已 legacy。SseController 在 `CompletableFuture.runAsync`(common ForkJoinPool)内订阅 Flux;DashScope 流回调在 reactor/netty 线程。
- **子任务 self-tool 过滤器**:`DefaultSubTaskExecutor` 构造 toolCallbacks 时排除 `ISubTaskTool`/`IScheduleTool`(防递归)—— D6 追加点位。
- **ToolCallContextHolder**:static ConcurrentHashMap + LATEST,**log-observation 专用**;AskUser 不用它,走 `ToolContext` 方法参数。
- **前端解析**:app.js `createParser`(L98-116)按 `data:` 行累积、空行触发 `handlers.onEvent({data})`;`api.streamChat`(L781-797)`JSON.parse(event.data)` → `onChunk(data)` **按字段分派**(`if (data.reasoningContent)` / `if (data.content)`,L1673-1690)→ 新增 `askUser` 字段分支天然向后兼容。`_showImportConflictDialog`(L5529)为模态先例(D1 已否决模态,仅作参考)。
- **@ToolGroup**(`tool/ToolGroup.java` L38-49):`String value(); String description() default ""; boolean defaultGranted() default false;`,`@Target(TYPE)` 放接口。`DefaultChat` 注入 `List<IEmbedTool>` 按 `"tool_" + ann.value()` ∈ visible groups 过滤(L154-166)。`CapabilityService.universalToolGroups()`(L225-231)扫 `defaultGranted=true` 并在 `visibleToolGroupsFor`/`allowedCapabilityIdsFor` 里 union —— **新增 universal 工具零 CapabilityService 改动**。既有 defaultGranted=true:IScheduleTool/ISubTaskTool/IFileTool/IKnowledgeTool/ITimeTool/ISkillTool。
- **REST 路由先例**:WebConfiguration 的 `loomAgentSubTaskRouter`(LoomAgentConfiguration L1006)/`loomAgentScheduleRouter`(L1184),`RouterFunctions.route()` builder;`AuthenticationFilter`(L73)每请求 `UserContextHolder.setCurrentUser(username)`。跨用户防泄露先例:`DefaultSubTaskTool` L129-138 跨 conversation 取消返回与"不存在"相同的 404 文案。
- **bean 注册先例**:ToolConfiguration `@Bean @ConditionalOnMissingBean(ITimeTool.class)`(L815-817)/ ISubTaskTool(L994)。
- **Spring AI**:`pom.xml:57` = 1.1.8;`internalToolExecutionEnabled` 全仓库未设置 → 默认 true,tool 执行是流内同步闭环。
- **`ResponseBodyEmitter.send` 内部 synchronized**:工具线程推 askUser 帧与 SseController 推内容帧并发安全。

## 3. 架构与组件

新组件在 `spring-ai-loom-agent` 模块新包 `cn.wubo.spring.ai.loom.agent.askuser`:

| 组件 | 新建/修改 | 职责 | 依赖 |
|---|---|---|---|
| `IAskUserTool`(接口,`@ToolGroup(value="askUser", description="ask user a clarifying question with option cards in the chat stream", defaultGranted=true)`)+ `DefaultAskUserTool` | 新建 | `@Tool askUser(...)`:ToolContext 取 username/parentConversationId → 入参校验 → Registry.register → emitter.send(askUser 帧)→ `future.get(timeout)` → return 答案文本/哨兵文本。超时经构造器注入(测试用短值) | AskUserRegistry、SseEmitterRegistry、LoomAgentProperties |
| `AskUserRegistry` | 新建 | `ConcurrentHashMap<questionId, PendingQuestion>`;PendingQuestion = `{AskUserEvent 元数据, username, conversationId, CompletableFuture<String> answer, createdAt, timeoutSeconds}`。API:`register(PendingQuestion)` / `answer(questionId, username, answerText)`(校验 owner;缺失/超时已清 → 404 语义返回值)/ `cancelAll(username, conversationId)`(哨兵 complete + 移除)/ `remove(questionId)`。**纯内存不落库** | — |
| `AskUserEvent`(record)+ `ChatResponseRecord` 加第 3 组件 | 新建/修改 | `AskUserEvent{String questionId, String question, String header, String background, List<AskUserOption> options, boolean multiSelect, boolean allowCustomInput, long timeoutSeconds}`;`AskUserOption{String label, String description}`。`ChatResponseRecord(String content, String reasoningContent, AskUserEvent askUser)` + 兼容 2-arg 构造器(旧发送点不破坏);构造点仅 SseController 一处,沿用"record 加组件属可接受破坏"先例(M4 T3) | Jackson(序列化) |
| answer 端点:`loomAgentAskRouter` | 新建 | `POST /spring/ai/loom/ask/{questionId}/answer`,body `{answer: string | string[]}`(数组=多选,服务端 join);username 从 `UserContextHolder` 取,与 PendingQuestion.username 不符 → **404 "not found"**(D8);Registry 无此 questionId → **404 "问题已失效"**;成功 → 200 `{ok:true}` + `future.complete(answerText)` | WebConfiguration 注册,镜像 subtask router |
| stop 集成 | 修改 | SseController stop 路径(或 register 的 onStop 组装处)追加 `askUserRegistry.cancelAll(username, conversationId)`(D7);哨兵值 = 特殊字符串(如 `"__CANCELLED__"`),DefaultAskUserTool 识别后 return "用户已停止本次对话,未作答" | AskUserRegistry |
| 子任务排除 | 修改 | `DefaultSubTaskExecutor` self-tool 过滤器追加 `IAskUserTool`(D6);`ISubTaskTool.startSubTask` @Tool description 补委派契约句:"子任务只做主任务规划好的任务执行并返回执行结果;执行中遇到的需要用户澄清的疑问,应写入返回结果,由主任务决定如何处理"(不点名 askUser 工具,遵守"模板不硬编码工具名"约定);`ISubTaskExecutor` javadoc 同步 | DefaultSubTaskExecutor |
| 前端 `app.js` + `style.css` | 修改 | `onChunk` 加 `if (data.askUser)` 分支 → 聊天流内嵌卡片:radio(单选)/checkbox(多选)/自填输入框(allowCustomInput)/提交按钮/本地倒计时(timeoutSeconds);提交 → POST answer → 卡片就地"已答 ✓"禁用;倒计时归零 → 置灰"已超时";stop/流结束未答 → "已取消"。**卡片不重建**(刷新后历史只有文本) | 既有 SSE parser 按字段分派 |
| 配置属性 | 修改 | `spring.ai.loom.agent.askuser.timeoutSeconds`(默认 300);`askuser.enabled` **不加**(M3 起工具 bean 总是创建,启停走 RBAC/universal 模型;askUser 是 universal,永远可用) | LoomAgentProperties |
| bean 注册 | 修改 | ToolConfiguration:`@Bean @ConditionalOnMissingBean(IAskUserTool.class)`(镜像 ISubTaskTool 先例) | LoomAgentConfiguration |

**工具组 8→9**:新增 askUser(universal)→ **概览图重生成**(本项完成后提醒用户,走 `.claude/skills/project-overview-image`)。CLAUDE.md 的 Universal 工具表 +1 行(理由:仅向当前流内的本人提问,答案回同一流,无越权风险;子任务已排除)。

## 4. 数据流(一次完整提问)

```
LLM 决定提问 → Spring AI 同步调用 askUser(...)
  → 入参校验失败(question 空白 / options 数量不在 2-4)
      → 直接 return 纠错提示文本给 LLM(不抛异常,LLM 自我修正后重试)
  → questionId = UUID;Registry.register(PendingQuestion)
  → SseEmitterRegistry.get(username, parentConversationId)
      → null(流已断)→ remove + return "无法向用户提问(会话流不可用)"
      → emitter.send(ChatResponseRecord(null, null, askEvent))   [ResponseBodyEmitter synchronized,并发安全]
  → future.get(timeoutSeconds, SECONDS) 阻塞 [D9:默认 tool 执行线程;复验卡顿则 boundedElastic 兜底]
  → 用户卡片点选 → POST /spring/ai/loom/ask/{qid}/answer
      → UserContextHolder.username 校验(D8)→ future.complete(answerText) → Registry.remove → 200
  → 工具 return "用户回答:xxx" → tool_result 回 LLM → 同流无缝继续生成
```

多选答案拼接:`"用户回答:label1; label2"`(服务端 join,顺序 = options 顺序);自填:`"用户回答(自定义):xxx"`。

## 5. 错误处理矩阵

| 场景 | 行为 |
|------|------|
| 超时(timeoutSeconds 到期) | `TimeoutException` → Registry.remove → return "用户未在 N 分钟内作答" → Flux 正常 complete → ChatMemory 本轮保住(ON_COMPLETE 硬约束);前端本地倒计时同步置灰"已超时" |
| 用户点 stop | onStop → `cancelAll` 以哨兵 complete → 工具线程释放 → return "用户已停止本次对话,未作答";本轮信号 = CANCEL,ChatMemory 不存(与现有 stop 语义一致,非新增丢失) |
| 竞态:超时瞬间用户提交 | POST 到达时 Registry 已 remove → 404 "问题已失效";前端显示"已超时"(可接受) |
| 竞态:stop 与提交同时 | cancelAll 与 answer 都走 `future.complete`(先到先得,CompletableFuture 语义);answer 若发现已 remove → 404 |
| question 空白 / options 数量不在 2-4 | 工具入参校验 → return 纠错文本(非异常);其余字段(header/background/description)不做长度上限校验(LLM 输出天然有界) |
| 跨用户提交答案 | 统一 404 "not found"(不泄露 questionId 存在性,D8) |
| emitter 不存在(流已断/已结束) | Registry.get null → remove + return "无法向用户提问(会话流不可用)" |
| 子任务/定时任务内的 LLM 想提问 | **看不到 askUser 工具**(D6 schema 级排除);无运行时守卫,无此错误路径 |
| LLM 连续多次 askUser | 串行天然支持(每次一个 questionId/一张卡片/一次阻塞);无并发卡片场景(单流内 tool 执行同步) |
| answer body 非法(JSON 坏 / answer 空) | 400 "invalid answer" |
| 应用重启时存在挂起问题 | Registry 纯内存,重启即清空;前端卡片提交 → 404 "问题已失效"(可接受,问题生命周期 ≤5min) |

## 6. 测试计划

- **单元(库模块)**:
  - `AskUserRegistryTest`:register/answer happy path、answer 未知 questionId、跨用户 answer 拒绝、cancelAll 哨兵 complete + 清空、answer 与 cancelAll 竞态(先 complete 者赢)。
  - `DefaultAskUserToolTest`(注入短 timeout,如 100ms):happy path(另一线程预先 answer)、超时路径返回"未作答"文本、校验分支(question 空 / options 1 个 / options 5 个)、emitter 缺失分支、哨兵取消分支。mock SseEmitterRegistry,断言 send 的 ChatResponseRecord.askUser 字段完整。
  - `DefaultSubTaskExecutor` 过滤器单测:断言 toolCallbacks 不含 IAskUserTool(镜像现有 self-tool 过滤断言)。
- **IT(test 模块)**:answer 端点接线 IT(真 router + 真 Registry bean:register → POST answer → 200 → future 完成;跨用户 → 404;未知 id → 404)。沿用清库起跑 IT gate。
- **ChatResponseRecord 兼容**:既有 2-arg 构造点(SseController 内容帧)编译与序列化回归(askUser=null 时 JSON 不含该字段或为 null,前端 onChunk 按字段分派不受影响)。
- **Chrome 手动复验**:真实 DashScope 流内诱导提问(prompt 让 LLM 调 askUser)→ 卡片渲染 → 点选提交 → LLM 同流继续;stop 中途取消;超时置灰。同时验证 D9(主流内容帧是否卡顿)。
- **回归门三段式**:库单元(151 基线)+ test 模块单元(382 基线)+ IT gate(123 基线,清库起跑)全绿。

## 7. 文档同步(T-last)

- `CLAUDE.md`:核心接口表 +1 行(IAskUserTool/DefaultAskUserTool);Universal 工具表 +1 行(tool_askuser,理由见 §3);ISubTaskExecutor 行补"排除 askUser"。
- `README.md` / `README.zh-CN.md`:功能特性段 + 工具列表。
- `docs/API.md` / `API.zh-CN.md`:新增 §ask 端点(answer POST,鉴权/404 语义)+ SSE 帧结构扩展(askUser 字段)。
- `docs/TOOLS.md` / `TOOLS.zh-CN.md`:askUser 工具说明(参数/卡片形态/超时/子任务排除契约)。
- `docs/superpowers/roadmap-2026-09-four-items.md`:#1 节 🔵→✅ + 落地记录(commit SHA、偏差、回归门数字);总排期表更新;"末"行概览图状态更新。
- **概览图重生成**:工具组 8→9 → `docs/project-overview-{en,zh}.png` 走 project-overview-image skill 重生成(EN_LAYOUT/ZH_LAYOUT 加 askUser 胶囊)——**需提醒用户**(DASHSCOPE_WORKSPACE_ID + DASHSCOPE_API_KEY)。

## 8. 收尾顺序(供 writing-plans)

T1(record/事件模型 + ChatResponseRecord 兼容扩展)→ T2(AskUserRegistry + 单测)→ T3(DefaultAskUserTool + 单测)→ T4(answer 路由 + stop 集成 + IT)→ T5(子任务排除 + 契约文档化)→ T6(前端卡片)→ T7(文档同步 + 回归门 + 概览图提醒)。T1/T2 可并行;T6 依赖 T4 端点契约;T7 终端。
