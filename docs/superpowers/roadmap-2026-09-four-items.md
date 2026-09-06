# 路线图 — 2026-09 四项需求(调研结论 + 决策存档)

> **用途**:durable 存档。四项需求(AskUser 工具 / 文档清理 / H2 向量存储 / 控制台 CRUD+审批流)的调研结论与已拍板决策都记录在这里,防止 session 中断/电脑重启丢失上下文。每项做之前先读本文件对应小节。
>
> 状态图例:🔵 未开始 / 🟡 进行中 / ✅ 完成

---

## 总排期(已拍板)

| 序 | 项 | 状态 | 量级 |
|---|---|---|---|
| 1 | #4 控制台 CRUD + 复活审批流 | 🟡 进行中(brainstorming 已完成 3 段确认,spec 待写) | 中-大 |
| 2 | #2 文档清理(删 CHANGELOG + md 对齐) | 🔵 | 小 |
| 3 | #3 H2 向量存储(自研 H2VectorStore) | 🔵 | 大 |
| 4 | #1 AskUser 交互工具 | 🔵 | 大 |
| 末 | 概览图重生成(#1 工具组 8→9 + #3 JVector→H2 会让图失真;全部代码完成后一次性重生成,**需提醒用户**) | 🔵 | 小 |

#2 排在 #4 之后、#1/#3 之前的原因:#1/#3 会实质改 README/CLAUDE 技术栈描述,文档对齐放在代码改动后才有意义;但 #4 也改文档(admin 控制台职责),所以 #2 在 #4 落地后做,再随 #1/#3 增量更新。

---

## #4 控制台 CRUD + 复活审批流(🟡 进行中)

### 背景(调研结论,已逐行核实)

- 原 spec(`docs/superpowers/specs/2026-09-04-skill-knowledge-market-design.md` §9.1/ADR-002)设计了完整审批流,但实现走了捷径:`submit()` 直发 APPROVED(skill DefaultSkillMarketService L297/303、KB DefaultKnowledgeMarketService L312/320),`pull()` 去掉了 APPROVED 校验(skill L377、KB L369),前端审批按钮从未接线。审批流"名存实亡"。
- 后端大半已实现:`AbstractMarketAdminService.approve/reject(comment 必填)/setOfficial/setFeaturedRank/setCategory` 已有;v2 admin 路由 15 端点全齐(approve L2675/reject L2695 等);共享前端模态框 `MarketAdmin.form()`(market-admin.js L151-340)**已写好、全仓库零调用**;i18n 键 `market.admin.action.approve/reject/setOfficial/setFeatured` 已备好未用。
- schema 零缺口:`market_skill`/`loom_market_knowledge` 的 status/reviewed_at/reviewed_by/review_comment 列全在;现有 25 条技能全 APPROVED,无数据迁移。
- v1/v2 同路径双注册(POST/PUT/DELETE `/admin/market-*`、`/user/market-*`、`GET /market-*/{id}`、pull、withdraw):语义分叉(v1 PUT 吃 status/v2 忽略;KB v1 DELETE 级联/v2 裸删),谁赢看 bean 顺序 —— 定时炸弹。
- 公开列表已 `WHERE status='APPROVED'`,PENDING/REJECTED 天然对公众隐藏。

### 已拍板决策

1. **状态机**:PENDING→APPROVED(admin approve,记 reviewer/time);PENDING→REJECTED(admin reject,**comment 必填**否则 400);REJECTED→作者重投=**新建 PENDING 行,旧行留痕**;APPROVED 不回退 PENDING。
2. **admin 新增直发 APPROVED**(admin 是可信主路径,对齐原 spec L396)。
3. **UNIQUE 冲突解法 = B 归档表**:两张市场表都有 `UNIQUE(author/username, name)`(V1.0 L219/L430),与"重投新建行"物理冲突。解法:新建 `market_skill_archive` + `loom_market_knowledge_archive` 审计表;作者对 REJECTED 行重投时,旧行**挪进 archive 表**(含 reject 理由/审核人/时间),主表该行 UPSERT 回 PENDING。主表 UNIQUE 不动。archive 表只增不删,供追溯。
4. **路由收敛 = 退役 v1 重复注册,v2 唯一赢家,并修对 v2**:`create()→APPROVED`(admin 直发)、`DELETE→级联`(清 user_skill/role_skill 或 KB 对应指针,对齐 v1 adminDelete 语义)、`PUT 不吃 status`(状态只走 approve/reject)。沿用 FU-4 外科式退役范式(M4 T1 先例)。KB v1 admin router(L4580)整体退役。
5. **用户投稿 submit()→PENDING**;pull() 恢复 APPROVED 校验(非 APPROVED 403);withdraw 限 PENDING/REJECTED(对齐 spec L397)。
6. **范围 = 完整对齐原 spec**:含拒绝备注、用户侧"我的发布"状态文案(审核中/已通过/已拒绝+拒绝理由)、技能编辑弹窗补 category/official/featuredRank 字段(与 KB 对齐)、admin 新增按钮(接线 MarketAdmin.form() create 模式)、IT 同步改造(现有 IT 多处断言 submit→APPROVED,如 KnowledgeMarketIntegrationTest L197-202 等,必须改)。

### 前端改动点(调研确认)

- `admin/market-skills.html/js`:加新增按钮、审批按钮(approve/reject+comment 输入)、编辑弹窗补 category/official/rank 字段、页面文案去掉"不再新建"。
- `admin/knowledge-market.html/js`:加新增按钮、审批按钮、文案修正("待 admin 审批"文案与功能终于一致)。
- `admin/market-admin.js`:接线 `form()`(create 模式)+ 新增 approve/reject 共享方法。
- `app.js` 用户侧:投稿文案改"提交后等待管理员审批"(现在 L4963/5080/5116 说 PENDING 但实际直发,文案先行代码没跟上——这次代码追上文案);"我的发布"tab 显示 PENDING/APPROVED/REJECTED 状态 + REJECTED 显示 review_comment + 重投入口。
- `admin/console.js` PENDING chip:已有(M4 修过 total 精确计数),审批流复活后开始真正有数。

### 测试影响(调研确认)

- `KnowledgeMarketIntegrationTest` L197-202 等多处断言 submit→APPROVED → 改为断言 PENDING + approve 后 APPROVED。
- `AbstractMarketAdminServiceTest` L88-93 mock 的是 approve SQL,不受影响。
- `DefaultKnowledgeReviewServiceIT`/`DefaultSkillReviewServiceIT`/`MarketAcceptanceIT`(A1-A15 验收)需过一遍状态机断言。
- 新增:archive 表迁移 IT(重投→旧行进 archive→主表回 PENDING)、pull 403 IT、reject 无 comment 400 IT。

---

## #2 文档清理(🔵)

### 调研结论

- `CHANGELOG.md`(77 行)**只被内部流程文档引用**:CLAUDE.md(L45、L53 T0 行)、docs/superpowers/{specs,plans,decisions} 数个文件、.superpowers/sdd ledger。**README/README.zh-CN/docs/API* 用户文档零引用** → 删除对外无破坏。
- 删除后需清理:CLAUDE.md 两处引用 + 决定 spec/ADR/ledger 里的历史引用是否保留(建议:历史文档内的引用保留原样——它们记录的是当时事实;活文档 CLAUDE.md/README 必须清)。
- "其它 md 对齐项目最新状况":README/README.zh-CN/CLAUDE.md/docs/{API,CUSTOMIZATION,TOOLS,SUBTASK-SCHEDULER}×2 语言逐一核对。已知失真点:docs/API.md L800-805 + API.zh-CN.md L489-590 记载"无审批流/提交即上架/approve 端点已移除"——#4 落地后这些全部要反转;CUSTOMIZATION.md L393 "no approval flow" 同。
- `.superpowers/sdd/**` 是过程 ledger,不需要对齐(历史快照)。
- 概览图:删 CHANGELOG 本身不影响图(图内无版本/日期);但 #4 把 admin 控制台职责改变、#1/#3 改技术栈 → 图重生成放最后统一做。

### 待办清单(#4 完成后执行)

1. `git rm CHANGELOG.md`。
2. CLAUDE.md:删 T0 行 CHANGELOG 引用;补"本项目不维护 CHANGELOG,变更历史看 git log"一句;ISkillStorage 行"no approval flow"等失真描述随 #4 更新。
3. docs/API*.md:审批流端点表反转(approve/reject 存在、submit→PENDING、pull 校验)。
4. docs/CUSTOMIZATION*.md:同上。
5. README/README.zh-CN:核对市场描述段。
6. 检查 .claude/skills/project-overview-image 的 SKILL.md/README.md/generate.py 是否因 #4 需要改(admin 控制台 5 区块描述、工具组数量)——#4 不加新工具组,预计不用;#1 会。

---

## #3 H2 向量存储(🔵)

### 调研结论(子 agent 完整报告要点)

- **现状**:`JVectorStore`(vectorstore/JVectorStore.java,515 行)extends AbstractObservationVectorStore;纯内存 ConcurrentHashMap + JVector HNSW 图;持久化只有 `~/.loom/jvector-index/docs.json`(文本+metadata)+ `ids.json`;**向量不落盘**——每次启动 `loadFromDisk()` L136-143 对所有文档**重新调 embeddingModel.embed() 重算**(启动昂贵,文档多了会打爆 embedding 配额)。
- **维度**:动态取 `embeddingModel.dimensions()`;DashScope text-embedding-v4 默认 **1024 维**。
- **替换点极干净**:`RagConfiguration.jVectorStore` 是 `@ConditionalOnMissingBean(VectorStore.class)` fallback(LoomAgentConfiguration L703);下游 DefaultUpload/DefaultKnowledgeTool 全面向接口,零改动。
- **Spring AI 1.1.8 官方无 JdbcVectorStore、无 H2 支持**(Maven Central/GitHub 模块/官方文档三方核验 404)。官方 DB 路线只有 PgVector/MariaDB/Oracle(需原生 VECTOR 类型)。**纯 H2 必须自研**。
- **隔离现状**:chunk metadata 只有 `type`+`knowledgeId`(DefaultDocumentRead L30-31),**无 username**;全用户混一个实例一份 docs.json,靠 knowledgeId SpEL 后过滤(HNSW 搜索后内存过滤 L369-374)+ 应用层 `knowledge.listAccessible(username)` 权限校验。
- **DB↔向量桥梁**:`file_document` 表(file_id↔document_id);`loom_file_content` 已有 BLOB 存文件内容的先例。

### 推荐方案(待 brainstorm 确认,未拍板)

**方案 A(推荐):H2 当持久层 + 保留内存 HNSW 索引** —— 新表 `loom_vector_store(id, knowledge_id, username, content, metadata_json, embedding BLOB/*float[] 序列化*/, dim, created_at)`;启动时从 H2 加载向量重建 JVector HNSW 图(**消灭 re-embed**);写入时 DB+内存双写。检索路径不变(HNSW ANN + 后过滤)。= SimpleVectorStore 的持久化模式 + JVector 的 ANN 性能。多用户:顺手把 username 写进 metadata/列,隔离过滤增强。
- 备选 B:纯 SQL 暴力扫(全表加载 Java 算 cosine)——文档量大 O(N),不推荐。
- 备选 C:换 MariaDB(官方 starter 省事)——违背"内嵌 H2 零运维"定位,不推荐。
- 风险点:1024 维 × N 文档的 BLOB 体积;HNSW 重建时间(从 DB 读比 re-embed 快几个数量级,可接受);`~/.loom/jvector-index/` 目录退役 + 一次性迁移工具(旧 docs.json → H2,re-embed 最后一次)或直接全新库政策(项目本来就"只跑全新库")。
- 影响概览图:Row B 技术栈胶囊 **JVector → H2 Vector**(或 JVector+H2),README/CLAUDE 相应段落 → **重生成图,提醒用户**。

---

## #1 AskUser 交互工具(🔵)

### 调研结论(子 agent 完整报告要点)

- **核心难点**:Spring AI 1.1.8 tool calling 是一次 stream 内的**同步闭环**(internalToolExecutionEnabled=true,Reactor 线程上 `ToolCallback.call()` 同步执行,无官方 human-in-the-loop 中断点)。要"等浏览器点鼠标"必须自己搭桥。
- **上下文可达性**:工具方法可经 `ToolContext` 拿 username/parentConversationId/baseUrl(DefaultChat L194-207 注入);**SSE emitter 可间接拿到**——`SseEmitterRegistry`(stream/SseEmitterRegistry.java)按 username+conversationId 持有活跃 emitter,工具 bean 注入 registry 即可向当前流推自定义事件。
- **现成先例**:`DefaultSubTaskTool.startSubTask` 就是"工具内 `future.get()` 阻塞等长任务"+前端轮询的模式(SubTaskRegistry.attachFuture 支持外部 cancel);`ToolCallContextHolder` 证明跨 Reactor 线程传 context 的解法已有。
- **SSE 现状**:只有一种事件帧 `ChatResponseRecord{content,reasoningContent}`;前端 app.js `createParser`(L98-116)+`onChunk`(L1671-1689)只认 data 帧——推新事件类型需前后端同时扩展(按字段分派即可,向后兼容)。
- **前端交互卡片范式**:`_showImportConflictDialog`(app.js L5476-5538)动态改 modal+绑回调,可复用为问题卡片;聊天流内嵌卡片则参考 renderBotMessage 的 bubble 结构。
- **ChatMemory 陷阱**:`LastChunkMessageChatMemoryAdvisor` 只在 ON_COMPLETE 写库,CANCEL/ERROR 整轮丢失 → 阻塞方案里超时/取消路径必须让 Flux 正常 complete(返回"用户未作答"文本)而非 error。

### 候选路径(待 brainstorm 确认,未拍板)

- **路径 1(推荐,最接近 Claude CLI 体验)**:`IAskUserTool extends IEmbedTool` + `@ToolGroup(value="askUser", defaultGranted=true)`(不进 RBAC,对齐用户"无需出现在权限里"要求);工具方法内:生成 questionId → `SseEmitterRegistry.get(username,convId).emitter().send({askUser:{questionId,question,options,multiSelect,allowCustom}})` → `CompletableFuture<String>` 存入按 questionId 索引的 Registry → `future.get(timeout)` 阻塞;前端 onChunk 按 askUser 字段渲染卡片(单选/多选/自定义输入,鼠标操作);用户提交 → `POST /spring/ai/loom/ask/{questionId}/answer`(校验 UserContextHolder 与提问 username 一致)→ complete(future) → 工具 return 用户选择 → LLM 同流无缝继续。风险:阻塞 Reactor 线程(需验证 DashScope 客户端 tool 执行线程池,必要时 boundedElastic);stop 按钮要同时 cancel 挂起 Future;超时返回"用户未作答"。
- 路径 2(零线程风险):工具立即返回哨兵文本+推卡片事件,本轮正常 complete;用户选择后前端发第二次 stream 请求把答案作为 user message(conversationId 不变)。缺点:多一轮往返、答案不是 tool_result 形态。
- 路径 3(subtask 式轮询):体验差,仅退路。
- 工具组 8→9(File/Git/Maven/Deploy/Time/Skill/SubTask/Schedule + **AskUser**)→ **概览图重生成,提醒用户**。
- 问题卡片数据模型草案:`{questionId, question, header?, multiSelect:bool, options:[{label, description?}], allowCustomInput:bool}` —— 对齐 Claude CLI AskUserQuestion 的 question/header/options/multiSelect 结构。

---

## 维护约定

- 每完成一项,更新本文件对应小节状态 🔵→🟡→✅ + 追加"落地记录"(commit 号、偏差决策)。
- 本文件是活文档;与 CLAUDE.md 冲突时以 CLAUDE.md 为准(本文件完成后要把结论合并回 CLAUDE.md/README)。
