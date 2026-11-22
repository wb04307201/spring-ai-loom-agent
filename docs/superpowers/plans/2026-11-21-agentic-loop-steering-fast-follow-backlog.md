# Final-review triage: deferred minors + rulings + gates (extracted from ledger)

## MERGE GATES / load-bearing rulings
63:Ruling R1: T4 改 Issue1ChatMemoryAdvisorRegressionTest 删精确类名断言改不变式——这是 spec §5.10 明确裁定(D-1 选 A 的约束松弛点),非 plan 擅自降级测试。reviewer 若 flag"削弱回归锁"为 plan-mandated, adjudicate:spec 授权,不变式(order 上游+MemoryAdvisor 标记+防 LastChunk 回潮)仍锁住 Issue1 根因。代价若错:Issue1 根因未被锁住 → 但 order+标记断言已覆盖根因(auto-register 下游检测),精确类名只是代理。
64:Ruling R2: T11 改 ChatSmoke/HttpTool BrowserIT 开始信号(send-btn.disabled → stop-btn 可见+isStreaming)——spec §5.5 明确裁定为契约演进非回归。reviewer 若 flag"改测试迁就实现",adjudicate:按钮解锁是特性本意(G1),结束信号不变,spec 授权。代价若错:流开始检测失效 → 但 stop-btn+isStreaming 是等价开始信号。
65:Ruling R3: T5/S1 若 auto-register 不抑制默认 ToolCallingAdvisor(双 tool advisor)→ 退 spec §4.1 方案(b)独立外层 advisor。这是 spec 预设退路,非 plan 缺陷。implementer 遇此报 DONE_WITH_CONCERNS,controller 按退路 ruling。代价若错:方案(b)内层注入需自定义 ToolCallingManager 包装,工作量增。
66:Ruling R4: 活体 Chrome E2E(T15 Step3.5)用 chrome-devtools/playwright MCP 驱动真实 Chrome + mvn spring-boot:run 真实服务——用户硬性要求。MCP server 本会话部分连接失败(loom-file-mcp),但 chrome-devtools/playwright 可用;若不可用则用 BrowserIT headless 兜底并记录,不静默跳过活体冒烟。代价若错:headless 漏掉的活体缺陷(favicon/GBK/折叠)未抓 → 补人工冒烟。
77:Ruling R5: StreamRunContext emitter 回调用 Consumer<String>(ack messageId)/Consumer<Integer>(iteration index) 而非 Consumer<SteerAck>/Consumer<IterationEvent> —— SteerAck/IterationEvent 是 Task 2 产物,Task 1 须独立编译;Task 6 接线时适配真实帧。代价若错:Task 6 需改 emitter 签名适配,小改。
91:Ruling R6: 此 Important 必修,不 park —— A2(绝不静默丢消息)是 spec 绑定权威,高于 plan"不加大锁"伪码框架;reviewer 的入队后复查修法(queue.add 后 if closing && queue.remove(msg) → depth-- return false)无锁、不违背裁定、闭合窗口(remove 失败⇔已被 drain 取走可见处置;成功⇔调用方收 false 走 429 同款拒绝即可见通道)。plan-mandated 标签不豁免 spec 反目标。代价若错:无(修法两分支都落 A2 允许态)。
92:Ruling R7: ⚠️ stopSignal Runnable→BooleanSupplier 签名偏差 —— 授权正确(isStopped() 返回 boolean 的唯一读法,brief 文字自相矛盾),非缺陷,记录备查。
93:Ruling R8: brief Step3 伪码"入队后 depth++/drain 归零"按字面会超 20,implementer CAS 先抢额+drain 逐条 decrement 偏离正确;验收基准以测试+修正语义为准,非逐字伪码。记录校准。
142:Ruling R10: 此 Important 是 load-bearing 交接缺口(Task 6/7 依赖),按 fix-loop"real+load-bearing→最小解阻改动+carry into next task"处置,非 park。决定:**Task 6 接线前置**改 register 返回 Entry(源兼容,忽略返回值即 void 语义,现有调用点零改动),lambda 用返回的 Entry 引用做 3-arg autoCleanup,**禁止 get-after-register 接线模式**。Task 3 本身 Approved(实现域内正确),缺口在 Task 6 闭合。代价若错:无(register 返回 Entry 是纯增,旧调用点忽略返回值)。
147:Ruling R11: stop/cleanup 残留可见处置(closeAndRemaining + ack 终帧)是 Task 7 路由层职责(spec §5.2 stop 语义"丢弃但可见"),Task 3 brief 只要求 close 正确;carry 到 Task 7 dispatch 作硬要求,防 A2 蒸发。
167:Ruling R12: Task 5 顺带把 3 个存量子任务测试的 mock MessageChatMemoryAdvisor.class → MemoryAdvisor.class(被测对象=生产对象保真),小机械改,纳入 Task 5 dispatch。
175:Ruling R13 (S1 resolved): DefaultChatClient.autoRegisterToolCallingAdvisor:1225 anyMatch(instanceof ToolAdvisor)→return 抑制默认;validateSingleToolAdvisor:1240 >1 ToolAdvisor 抛 IllegalStateException。LoomAgenticLoopAdvisor extends ToolCallingAdvisor implements ToolAdvisor → 逐请求挂载抑制默认,主链恰 1 ToolAdvisor;memory +200<+300 非下游→conversationHistoryEnabled=true;子任务不加 loop→auto-register 默认 ToolCallingAdvisor(行为不变 A5)。S1 无需退路方案(b)。已喂 implementer 精确源行号。
181:Ruling R14 (carry→Task 6): 须验证 advisor param StreamRunContext 确实传播到 response context(after() 读 response.context().get(CONTEXT_KEY));若 Spring AI 不自动传播 param→response,Task 6 需显式确保 LoomMemoryAdvisor.after 能读到 ctx,否则 perIterationMode 跳过失效→双写。Task 6 dispatch 带此硬验证。
188:Ruling R15 (carry→Task 6): loop advisor builder 须注入 client 级 ObservationRegistry/ToolCallingManager 模板(DefaultChatClientBuilder 源 105-106 默认 toolCallingAdvisorBuilder 带 observationRegistry),防逐请求 new 降级 NOOP。Task 6 接线时从 chatClient builder 模板取或注入 ObservationRegistry bean。
203:Ruling R16 (carry→Task 7): stopSignal 消费 isStopped 时改看 disposeRequested(非 disposable.isDisposed,订阅建立前 d==null 误判); ack/iteration 帧 advisor 线程 send 须与内容帧并发安全(ResponseBodyEmitter.send synchronized,askUser 同模式); 续跑起点 chain.copy(this) 保 order=100 usage 逐轮。
207:Ruling R17: Important #1 必修(A1 绑定权威,非 park)。修法:generations 存在即写(去 isEmpty() 跳,保留 null-chatResponse/no-generations 跳=legacy 等价 after 写空列表→0 行);补 empty-text 回归用例。multi-generation N→1 合并是 spec §5.10"该迭代文本"by-design(每迭代一行),非缺陷,记录。代价若错:无(写空文本行=legacy 行为)。
235:Ruling R18 (correctness, source-verified by controller): brief/spec §5.1 字面"doFinalizeLoop → chain.copy(this).nextCall 续跑"**可证错误** —— DefaultAroundAdvisorChain.copyAdvisorsAfter:189 = subList(indexOf(after)+1,size) 排除自身,finalize 点 copy(this) 重入只跑下游(usage+model),丢工具循环+迭代收口写。implementer 改递归 adviseCall/adviseStream 重入(每轮内部仍用 copy(this),R16c/S3 逐轮 usage 满足)——**批准**。护栏 bounded recursion(maxContinuations=10)。spec §5.1 措辞订正记 T16 doc sync。reviewer 重点验:递归传原 chain(内层 copy 可用)、上游 memory advisor 不重跑、护栏先于递归、Flux.concat 惰性无栈爆。
240:Ruling R19 (carry→Task 11): steerAck 帧契约 = {messageId, status: INJECTED|NOT_INJECTED};INJECTED 灰态转正,NOT_INJECTED 标"未发送(已停止/收口)"可见终态(A2/R11)。
254:Ruling R20 (carry→Task 11): steer 前端契约边界 — 403 TOCTOU 窗口容错(视同 queued:false 回退或 graceful 提示,非硬 error);终帧竞态→流结束统一解灰态兜底(不依赖 NOT_INJECTED 帧必达);400/403/429 body 形状不一(400 缺 code)按 status 分支处理。
255:Ruling R21 (carry→Task 8): D-3 pauseClock/resumeClock 实接线落 Task 8 —— DefaultChat 把 StreamRunContext(在场时)放进 toolContext;DefaultAskUserTool future.get 前 pauseClock、finally resumeClock;测试锁 askUser 阻塞区间不计 activeElapsedMs。否则 D-3 只有消费无生产,runTimeout 仍计人等待。
289:Ruling R22 (carry→Task 10): injected_at TIMESTAMP 无时区 vs tool_call_log created_at WITH TIME ZONE —— Task 10 历史 interleave/B2 豁免匹配时须统一时区基准(都转 Instant/UTC 比较),防 ±时区偏移错配。
298:Ruling R23 (carry→Task 10 验收项): ILoomChatSteeringRepository 加 deleteByConversation(convId)(或 deleteByUserAndConversation 镜像 schedule_execution),接线 cleanupConversationResources(LoomAgentConfiguration 会话删除级联),测试锁删会话后审计行清零。
299:Ruling R22 修订: interleave 主对手是 chat_memory(同 TIMESTAMP 无时区,一致);仅 TOOL_CALL(loom_tool_call_log WITH TIME ZONE)cross-ref 时统一 Instant 比较。
313:Ruling R25 (pending review verdict): 若 reviewer 判 A1 过渡态违约 → Task 14 必须收敛工具条归位(硬验收);若 spec 授权中间态 → 记录 Task 14 收敛仍做。carry→Task 14: renderMessages 须处理 interleave 后 TOOL_CALL 先于 ASSISTANT 的归位(_lastBotBubble 语义),防工具条浮气泡上方。
328:**MERGE GATE (R25)**: Task 14 必须与 Task 10 同 release 落地,不得单发 Task 10(interleave 过渡态需前端收敛)。
342:Ruling R26 (carry→T12/13/14): BrowserIT 扣流手法必走 awaitRoute 切片泵送(Playwright Java route 投递语义),防裸 poll 假超时。
343:Ruling R27 (carry→T13): 新流结束旁路须经 enableSend 汇聚点(stop 抑制标志复位点),防粘滞泄漏。
347:Ruling R28: Important #1 必修(A2+会话隔离绑定不变式,不 park;reviewer 建议随 Task 12 处理,controller 选 Task 11 fix round 保持 task-scoped 干净)。修:入队快照 convId,onStreamEnd/切换仅补发同会话项,异会话项就地标 unsent。补 BrowserIT 用例(2 条回退+切会话→残余标 unsent 不跨会话投递)。
402:Ruling R29 (carry→T16): spec §5.3 dismiss placeholder 文案陈旧, 实现取 R27 收敛单口 RUNNING placeholder 正确, T16 文档同步订正 spec 措辞。
403:Ruling R30 (carry→T14): 历史回放须补咬 askUser 帧 dispatch 路由(app.js:2932)+ 真实形态回放, 补 Task 13 测试缝残留口。
409:Carry-ins: R25 MERGE GATE (Task 14 必须与 Task 10 同 release, interleave 过渡态工具条浮气泡上方须本 task 收敛——硬验收); R26 (awaitRoute); R30 (补咬 askUser dispatch 路由+真实回放); Task 10 历史端点已 additive ts+服务端 interleave; Task 12 minor⑤ 拆分×未答 askUser 竞态留观察。
412:### Task 14 — implementer DONE, review dispatched (R25 MERGE GATE 收敛)
425:Task 14: complete (commits 2669a18b..84626e91, review clean) — **R25 MERGE GATE 闭合**
429:Ruling R31: Task 15 是验证门(契约锁测试+全量回归+变异+活体 Chrome E2E),非功能实现。活体 E2E(Step3.5)须真实服务+真实 Chrome(chrome-devtools/playwright MCP),这些 MCP 在本 controller 会话可用,subagent 无可靠访问 → controller 直接驱动 E2E;契约锁测试+全量回归+变异委托 subagent。此偏离 subagent-driven 默认系工具访问约束所致,记录备查。
445:Ruling R32: G1 admin 插话徽章是 spec 附带收益, 核心 A7/G7 时间线已实证修复; 徽章渲染小 additive 归 T16 顺带补(矩阵 D2 核销), 不阻塞 Task 15。

## Deferred minors (triage which must fix before merge)
95:- Task 1: minor (deferred): active-clock 测试未锁"活跃计入"(恒返回0也绿),加 sleep(20) 断 >=15
96:- Task 1: minor (deferred): StreamRunContext javadoc null 保证指向错误类(offer 不校验 messageId,实际在路由层)
97:- Task 1: minor (deferred): 在途预约占额,队列19条时第20条可能被拒,路由层文案别写死"队列已满"
98:- Task 1: minor (deferred): noopMemory() 内联全限定 Message 三次,改 import(外观)
106:Task 1: minor (deferred): depth seam 窗口瞬时过计(偏保守拒绝,原报告遗留#3,fix 未改)
107:Task 1: minor (deferred): Node 每 offer 一次短命对象分配(≤20 depth 用户打字速率无关紧要)
123:Task 2: minor (deferred): maxContinuations javadoc 预告 Task 6 未实现行为,改引用措辞防第二真源漂移
124:Task 2: minor (deferred): 便捷构造器测试未断言 iteration/subTaskEvent 全 null(半够用,polish)
125:Task 2: minor (deferred): LoomAgentConfiguration:478 注释措辞略绕(缺行是缺陷/注释是防线),可读性小噪
144:- Task 3: minor (deferred): stop 用 close() 非 closeAndRemaining(),已 offer 成功排队消息无人 drain 违 A2"true⇒必被读走";cleanup(u) 未 close;steer 抓被覆写 Entry 孤儿 inbox。**Task 7 接线须带 closeAndRemaining + ack 终帧语境**(残留可见处置),否则用户已排队输入静默蒸发。→ carry to Task 7 dispatch (R11)
145:- Task 3: minor (deferred): :116 注释"与 remove 行为一致"不准确(裸 remove vs 值条件 remove);存量 isEmpty→remove ABA 窄窗未加重
146:- Task 3: minor (deferred): 测试 other.inbox().isNotNull() 恒真弱断言,真 A1 锁是 2-arg 调用点编译存活
163:Task 4: minor (deferred): 存量测试 mock final MessageChatMemoryAdvisor(DefaultSubTaskExecutorTest:27,39/DefaultSubTaskToolTimeoutTest:46,57/ParallelSubTaskTest:47,57)→ Task 5 批量改 mock MemoryAdvisor.class (R12 carry)
164:Task 4: minor (deferred): before() 去重命中分支无单测锁,建议补 before_skips_memory_prepend_when_already_in_prompt(防未来滑窗腐化)→ final review triage
165:Task 4: minor (deferred): LoomMemoryAdvisor tab 缩进(照抄上游),仓内 4 空格,纯风格
184:Task 5: minor (deferred): ToolCallingManager 逐请求 new 丢 client ObservationRegistry → tool 执行 Micrometer span 降级 NOOP(A1 循环行为不变,本仓审计走 LoggingToolCallback/loom_tool_call_log,影响仅可观测性)→ Task 6 注入 client 模板/ObservationRegistry (R15 carry)
185:Task 5: minor (deferred): 反射私有 advisors 字段合法但脆(非假绿:validate 先抛+模型计数翻倍+NoSuchFieldException 红);可选 helper 收敛+assertThatCode 包 validate
186:Task 5: trivial (deferred): advisorsOf helper 两测试逐字重复 → 提取共享测试工具
209:- Task 6: minor (deferred): drain hooks 注释 :200/:212 误标 Task 6(实 Task 7)→ Task 7 实现 drain 时顺带订正
210:- Task 6: minor (deferred): R15 report 称条件设 manager 实无条件(行为等价 NOOP 默认),report 措辞不准
211:- Task 6: minor (deferred): 缺 ctx+multi-tool-round+subscriber-fed-delta 真链组合测试(cache 持 FINAL round 非 tool round thinking;reasoning buffer flush 时完整)→ 建议 2-round stub 测试
212:- Task 6: minor (deferred): ReasoningPerIterationTest:484 verifyNoInteractions(usage) 空转哨兵(usage mock 未接 ctx)→ 删或重接
213:- Task 6: minor (deferred): usage guard :831-838 null token+positive token 传 record(long) unboxing NPE 窗(存量形态,guard 减暴露)→ 复用 null-safe locals
214:- Task 6: minor (deferred): lambda probe 对 usage-bearing-chunk provider 双计面(存量,DashScope 全零惰性)→ 注释或 advisor-present guard
215:- Task 6: minor (deferred): A1 nuance brief-sanctioned — 新写 final-round-only vs legacy 含 intermediate tool-round text(DashScope tool round 空 content 无漂移);new AssistantMessage(text) 丢 metadata/toolCalls(DB 等价,仅 in-memory ChatMemory 替换可见)→ 记录非改
216:- Task 6: minor (deferred): stopSignal pre-subscription isDisposed true(subRef null)→ Task 7 gate disposeRequested only (R16 已 carry)
244:Task 7: minor (deferred): ①steer 端点 ownership TOCTOU(新会话 register 先于 user_conversation 插入,窗口内 steer 得 403 非 queued:false,前端回退键 queued:false→error toast)→ final review triage 后端修(entry!=null&&!owned→queued:false 或 lambda 先插映射);Task 11 须容错(R20)
245:Task 7: minor (deferred): ②端点 entry 预检 vs registry.steer re-get 跨 Entry swap(429 预检可绕/漏,同 user+conv benign)→ steer(Entry,...) overload 候选
246:Task 7: minor (deferred): ③stop↔finalize 终帧竞态(finalize drain 后 stop.complete 先于 emitTerminalAcks.send→帧降级 log-only;A2 仍 hold 消息已读;Task 11 流结束兜底解灰态)→ javadoc caveat+Task 11 note(R20)
247:Task 7: minor (deferred): ④DisconnectDisposeIT 注释"spring-web 7.0.9"应为 spring-webmvc(模块名错版本对)
248:Task 7: minor (deferred): ⑤normal_completion_no_residual_frames 只断言 cleanup+emitter!=null,未锁零终帧(弱锁)→ spy emitter/earlySendAttempts
249:Task 7: minor (deferred): ⑥400 body 缺 code(403/429 有{error,code})契约不一致→Task 11 note(R20)
250:Task 7: minor (deferred): ⑦race seam 只覆盖 close-without-reopen,offer-vs-reopen interleaving 无确定性 seam 测试(beforeEnqueue 同 hook 可补)
251:Task 7: minor (deferred): ⑧5 文件缺 EOF newline(polish)
252:Task 7: minor (deferred): ⑨returnDirect/ToolCallLimit break 路径续跑 prompt 缺末轮 tool-result rows+stamp breach/tool text 为 plain ASSISTANT(仓内今日无 returnDirect/toolCallLimit 使用,dormant)→ Task 8/9 follow-up list
271:Task 8: minor (deferred): ①mode 大小写宽容(trim+toUpperCase)良性但零测试覆盖→补 " dismiss "→200 用例或收紧精确匹配,勿留实现宽容契约沉默
272:Task 8: minor (deferred): ②pause/resume 布尔幂等非嵌套×并行双 askUser(先到者 resume 重启时钟,少排除非泄漏;当前单卡阻塞风险低)→时钟契约注释"多卡并发语义待核",与 returnDirect dormant 同列
273:Task 8: minor (deferred): ③D-3 测试 sleep(50) 兜底 CI 抖动窗口(worker 饿>50ms 偶发假红)→容差断言或第二 latch
274:Task 8: minor (deferred): ④哨兵字面量用户答案撞车(既存 CANCELLED 同款权衡,概率可忽略,备案不改)
275:Task 8: minor (deferred): ⑤风格:全限定 StreamRunContext 三处+双 cast 可 import+pattern var 收紧;sent2 latch 捕获未 await 误导
293:Task 9: minor (deferred): injected_at TIMESTAMP 无时区与 chat_memory 一致(interleave 无需 normalize);仅 cross-ref tool_call_log WITH TIME ZONE 须留意;多 JVM 异时区理论 wrinkle(单节点 H2 today);无 run-id 列,跨 run 分组靠 injected_at 聚类 → Task 10 文档性 carry (R22 修订)
294:Task 9: minor (deferred): AUTO_INCREMENT vs 仓惯例 IDENTITY(spec verbatim compliant,H2-only portable,PG/MySQL 时进 ADR-T05.3 迁移清单)
296:Task 9: minor (deferred): LoomAgenticLoopAdvisor EOF newline
325:Task 10: minor (deferred): 用例4 未正向断言 out.get(0).timestamp==t1(排序已隐含);报告"真三段合围"措辞可接受
355:Task 11: minor (deferred): ⑩ cleanup 注释 overstated(称回 A 断言历史实际未回 A,行为断言有意义)
374:Task 12: minor (deferred): ①空气泡跳过规则零测试覆盖(删之 5/5 照绿;且泛化到 tool-only 前轮 iteration 边界吞分隔线,比自述意图宽)→补 split→iteration→content 用例+收窄条件对齐 chips
375:Task 12: minor (deferred): ②onAck 幂等哨兵 steerState 实际不可达(class 移除先命中 !el 分支),报告归因偏差→改注释(幂等实由 class 移除保证)
376:Task 12: minor (deferred): ③分段 id 链式增长(iterseg-<id>-it-1-it-2…)唯一但越来越长→截基座 id
377:Task 12: minor (deferred): ④测试①封口时刻断言弱于注释(waitIdle 后才查,非拆分瞬间)→降注释或中途 evaluate
378:Task 12: minor (deferred): ⑤拆分×未答 askUser 竞态(插话 ack 后作答续写落新气泡,与裁定一致但 pre-T12 落卡片气泡;窄竞态留观察)
397:Task 13: minor (deferred): ①renderer dismissed 判定 /[用户想先聊聊]/ 未锚定, 用户自定义答案含该字面误判 → /^…/ 零成本消歧
398:Task 13: minor (deferred): ②onFrame dispatch 行(app.js:2932 if data.askUser→render)无契约锁(IT 缝唯一绕开环节)→ 静态契约测试或 Task 14 真实回放补咬 (R30 carry→T14)
399:Task 13: minor (deferred): ③freeze hadPending 恒真无信息量 → 简化无条件 syncPendingState
400:Task 13: minor (deferred): ④提示条倒计时 ≤1s 陈旧窗口(最早卡 freeze 后新卡待下一 tick, 数值不倒退可接受)
420:Task 14: minor (deferred): ①conv4 replay 等待耦合🔧emoji 图标(图标改测试挂)→改 [data-tool-call-id] 存在性等待
421:Task 14: minor (deferred): ②startIterationSegment 返 null(anchor 游离回放不可达)静默覆盖第一段→if(!seg)兜底更稳,当前可接受
422:Task 14: minor (deferred): ③R30 dismissed 徽章回放锁在 AskUserDismissBrowserIT:516-570 非 HistoryReplayBrowserIT(覆盖无缺口,同文件要求可补)
423:Task 14: minor (deferred): ④curOrigin 单行 if 无花括号风格混用(擦除级)
448:Task 15: minor (deferred): ①NoSteeringFrameSequenceContractTest:293 超时失败路径无锁读 earlySendAttempts 可能 CME(只在本该红路径,失败信息变 CME)→frames() synchronized 快照
449:Task 15: minor (deferred): ②Set.of() 全限定风格不一致(polish)
450:Task 15: minor (deferred): ③guidance doesNotContain("知识空间") 依赖唯一来源,未来他段引入误伤→断言条件行前缀
478:Task 16: minor (deferred): ①CLAUDE.md loom_chat_steering bullet 括号后多余空格(cosmetic)
479:Task 16: minor (deferred): ②ConsoleAdminBrowserIT turn-initial 无徽章断言双重否定(count==1 已 pin 无洞,直接 querySelector==null 更清晰)
480:Task 16: minor (deferred): ③overview 不重生成裁定仅在未提交 report,CLAUDE.md overview 节可加一行 durable note(optional)
481:Task 16: minor (deferred): ④HttpToolBrowserIT 外网瞬红 standing flake-risk(jsonplaceholder), someday WireMock 迁移(非本 task 缺陷)
