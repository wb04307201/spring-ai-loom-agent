# 路线图 — 2026-09 四项需求(调研结论 + 决策存档)

> **用途**:durable 存档。四项需求(AskUser 工具 / 文档清理 / H2 向量存储 / 控制台 CRUD+审批流)的调研结论与已拍板决策都记录在这里,防止 session 中断/电脑重启丢失上下文。每项做之前先读本文件对应小节。
>
> 状态图例:🔵 未开始 / 🟡 进行中 / ✅ 完成

---

## 总排期(已拍板)

| 序 | 项 | 状态 | 量级 |
|---|---|---|---|
| 1 | #4 控制台 CRUD + 复活审批流 | ✅ 完成(2026-09-07,10 Task 全部落地) | 中-大 |
| 2 | #2 文档清理(删 CHANGELOG + md 对齐) | ✅ 完成(2026-09-07) | 小 |
| 3 | #3 H2 向量存储(H2JVectorStore) | ✅ 完成(2026-09-07) | 大 |
| 4 | #1 AskUser 交互工具 | 🔵 | 大 |
| 末 | 概览图重生成(#1 工具组 8→9 + #3 JVector→H2 会让图失真;全部代码完成后一次性重生成,**需提醒用户**) | 🔵 | 小 |

#2 排在 #4 之后、#1/#3 之前的原因:#1/#3 会实质改 README/CLAUDE 技术栈描述,文档对齐放在代码改动后才有意义;但 #4 也改文档(admin 控制台职责),所以 #2 在 #4 落地后做,再随 #1/#3 增量更新。

---

## #4 控制台 CRUD + 复活审批流(✅ 完成)

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

### 落地记录(2026-09-07)

**Commits(按 Task 序)**:
- `d0ae184` — T1 archive 表(market_skill_archive + loom_market_knowledge_archive 入 V1.0)
- `7649eb7` — T2 createApproved(admin 直发 APPROVED + created_by_kind='ADMIN')
- `a412086` + `5ff225b` — T3 skill submit→PENDING / pull 403 + §2 ruling 修正(REJECTED 重投归档;PENDING/APPROVED 同名重投原地更新、状态不动)
- `5bc3995` — T4 KB 镜像 + IT 断言翻转
- `cd95266` — T5 admin delete 级联清理(cascadeCleanup user_*/role_* 引用行)
- `3b16350` — T6 路由收敛(退役 v1 重复注册,v2 唯一赢家;admin POST→createApproved)
- `079f529` — T7 admin 前端(审批按钮 + reject comment + 新增表单接线)
- `16b4588` — T8 用户前端(我的发布状态徽章 + 拒绝理由 + 重投入口)
- `df8dc7d` — T7.5 gate-fix(category 补进 MarketSkill/MarketKnowledgeRecord DTO)
- `67c20ba` — T9 回归门(全量测试翻转到两阶段 submit→approve)
- `e55778b` + `6842a52` — T10 文档同步(API/CUSTOMIZATION/CLAUDE/README×2/接口 javadoc/roadmap;fix round 1 补 3 处 admin-cannot-create)
- `d55ca97` — 终审 fix wave(I-1 技能 withdraw 三态按钮 + I-2 README 撤回级联假声明修正 + M-1 reviewer 列绑定 + M-2 幽灵版本行 + _kmStatusLabel 死代码 + KB 新增表单 official/rank 仅编辑态)
- `262bf35` — Chrome 复验 hotfix(withdraw 按钮渲染裸 i18n key:local t() 补传 fallback + market.withdraw.* 三键入双语字典)

**与 spec/调研的偏差**:
1. **B1 fresh-DB**:按项目"只跑全新库"政策清库重跑,25 条测试技能数据被清(无需迁移脚本)。
2. **KB v1 list 腿保留**:`GET /api/knowledge-market` 未退役 —— admin/roles.js 角色知识库授权下拉仍依赖该路径;follow-up 迁 v2 后再退役。
3. **KB v2 user-submit 腿退役**:`POST /user/market-knowledge` 删除(前端零调用;聊天侧投稿走 v1 `POST /api/knowledge/{id}/submit`)。
4. **category 补进两个 DTO**:MarketSkill(18 组件)/ MarketKnowledgeRecord(17 组件)source-breaking,沿用 M4 T3 先例(record 加组件属可接受破坏)。

**回归门**:库单元 118/0 + test 模块单元 383/0 + IT 120/0/3-skip,全绿。

**Follow-ups(记录,本期不做)**:
- archive 浏览 UI(admin 查看历史归档的 REJECTED 行)。
- roles.js 迁 v2 Page 形态 → 随后退役 KB v1 list 腿。
- KB 重投需要 MarketKnowledgeRecord 携带 source-knowledgeId(当前重投从主表行反查)。
- reject() 加 PENDING-only 前置条件(当前任意状态可 reject)——候选收紧。
- `_kmStatusLabel` 死代码清理(app.js)。
- KB admin 新增表单 official/rank 字段静默丢弃(MarketCreateRequest 无该两字段)。
- `ISkillMarketService.listAllForAdmin` 零调用方,shim 到期随接口一并删除。
- **市场指针卫生(final review 归并,一个不变量:除 cascadeCleanup 或显式 rebind 外,引用不得比市场行活得久)**:reject() PENDING-only 前置 + skill withdraw() 级联/置空其他用户 user_skill.market_skill_id + role_skill 清理(当前只清作者自己 backlink,KB withdraw 已级联 → 两侧不对称)+ resubmit 归档时 rebind/null 非作者指针 + KB createApproved name-blank 校验对齐 skill。触发面:纯 UI 流不可达(admin 审批按钮仅 PENDING 行渲染);API-only reject-of-APPROVED 或 KB withdraw-of-APPROVED 才触达,低危(无 FK 不报错、副本仍可用、re-pull 自愈),但作为一个连贯卫生项一起修。
- **M-3 测试补强(final review)**:double-archive cycle(二次重投再归档)、backlink-to-new-id 断言(resubmitRejectedArchivesOldRow 未断 user_skill.market_skill_id=id2)、route-level blank-comment→400 IT、reject-no-precondition 决策测试(无论 follow-up 怎么裁,都该有 test 记录决定)。
- **M-4 死 i18n 键**:`market.admin.reject.commentRequired` / `market.admin.create.skill` / `market.admin.create.knowledge` 已加但未被消费(admin 页用硬编码中文,与既有风格一致);要么接线要么删。`market.withdraw.*`(本期新增)已接线。
- **M-5**:skill submit 原地更新分支未写 `updated_at`(admin update() 写了)—— cosmetic。
- **M-2 skill data-id 未 escapeHtml**(KB 侧 escapeHtml):数值 BIGINT 安全,纯样式一致性。
- **dynamic-SET 无法从编辑弹窗清空 category/rank**(null 被跳过,两侧对称的 pre-existing 契约);需 sentinel/显式 clear 语义设计。
- **CglibAopProxy WARN**(final currentStatus() 被代理):pre-existing 日志噪音,无害。

---

## #2 文档清理(✅ 完成 2026-09-07)

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

### 落地记录(2026-09-07)

**CHANGELOG 删除**:`git rm CHANGELOG.md`(77 行);全库非-md 文件(pom/脚本/java)零引用,活 md 仅 CLAUDE.md L45/L53 两处引用已清。CLAUDE.md 顶部加 **No CHANGELOG** 声明(变更历史看 git log)。docs/superpowers/{specs,plans,decisions} 内的历史 CHANGELOG 引用按"历史快照不改"约定保留原样。

**活文档失真修正**(以源码为准逐条核实,Explore 子 agent 扫描 + controller 裁决):
- **种子落点**:V1.1 迁移把 6 个 system skill seed 进**默认 admin 用户**的 `user_skill`(source=USER_CREATED),**非** market_skill/author=system/version=1.0.0 —— 修正 CUSTOMIZATION×2、TOOLS×2、README×2、API.zh-CN(去掉硬编码个人用户名 wb04307201)。
- **MARKET_PULLED description 锁**:`DefaultSkillStorage.patch()` L144-148 对 MARKET_PULLED 改 desc 抛 403,只允许 default_loaded —— 修正 README×2 权限矩阵(desc MARKET_PULLED ✅→✗)+ API×2 §6.3 PATCH 描述。
- **pull 同名语义**:USER_CREATED→403(非 400),MARKET_PULLED→静默刷新 content —— 修正 API×2 自相矛盾的"抛 400 + 静默刷新"。
- **union view / MARKET_VIEW**:均已移除 —— 清 README×2 "admin 还会看到 union view"、API.md:724 "falls back to market view"、API.zh-CN 两处 "( 起移除 MARKET_VIEW" 残句。
- **setRoleKnowledges→setRoleSkills**:技能角色授权节误用知识库函数名 —— 修正 README.md role_skill 行 + 生命周期步骤 4。
- **withdraw 三态**:README.md:294 "Withdraw PENDING items" → 三态文案 + KB 撤回级联清理 loom_user_knowledge/loom_role_knowledge(与 skill 侧"仅清作者 backlink"的非对称如实描述)。
- **admin 控制台表**:README×2 补 knowledge-market.html 行;stats.html 侧栏现名"日志"(非"用量统计");角色管理补 Knowledge 授权。
- **空占位残句**(疑似历史 CHANGELOG/版本号引用被机械剥离):`****:`、``added in ` ` ``、``( removed version)``、``( 起移除)``、``：去掉``、``/ `` Flyway、``D2 ()`` 等 —— 全部还原为可读文本或移除。
- **死链**:API.md "See docs/knowledge-market.md"(文件不存在)→ 改指本文 §5.8。
- **version 字段残留**:README.zh-CN.md:219 "角色锁的是具体版本" → "角色锁的是该市场条目"(version 字段已移除)。
- **README.zh-CN.md 格式事故**:技能生命周期 1-5 步被压成单行、`###` 标题内联 —— 重建为正常 markdown 列表/标题。

**概览图**:删 CHANGELOG 不影响图;#4 未加工具组、未改 admin 侧栏区块数(仍 6 区块)→ **图无需重生成**(重生成仍排在 #1/#3 之后,届时提醒用户)。

**回归**:纯文档改动,无代码/测试影响。

---

## #3 H2 向量存储(✅ 完成 2026-09-07)

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

### 落地记录(2026-09-07)

- **最终形态**:方案 A 落地为 `H2JVectorStore`(H2 表 `loom_vector_store` 持久化 embedding BLOB little-endian float32 + 内存 JVector HNSW 索引;`H2VectorStoreReloader` 在 `ApplicationReadyEvent` hydrate,消灭启动 re-embed)。表无 username/knowledge_id 列(按 spec D1 裁决:仅 6 组件 `document_id/content/metadata_json/embedding/dim/score`,隔离仍走 metadata 后过滤)。旧 `JVectorStore` 类 + `~/.loom/jvector-index/` json 目录退役;`jvector.indexPath` 属性删除,节名 `spring.ai.loom.agent.jvector.*`(m/efConstruction/efSearch)保留。全新库政策,无迁移工具(spec D3)。
- **Commit**:T1 `14cb627`(VectorRowCodec + FakeEmbeddingModel)/ T2 `7fd362e`(loom_vector_store 表 + H2JVectorStore)/ T3 `26b7fc6`(H2VectorStoreReloader + RagConfiguration 接线 + 删 indexPath)/ T4 `8a87910`(删旧 JVectorStore 类 + H2VectorStoreIT)/ T5 文档同步 + 回归门(本 commit)。
- **与 spec 偏差**:无(plan 预检时补充了 hydrate 原子交换细节 — decode 先行、clear+putAll,防毒行导致 doc/id/vector 错位,已按此实现)。
- **回归门**(2026-09-07,三段式全绿):库单元 `mvn test -pl spring-ai-loom-agent` → 151 run, 0 failures(含新增 VectorRowCodecTest 5 + H2JVectorStoreTest 9 + H2VectorStoreReloaderTest 3);`mvn clean install`(3 库模块,-DskipTests)→ BUILD SUCCESS;test 模块单元 → **382 run, 0 failures**(383 基线 − 1,PropertiesDefaultsTest 删 jvectorIndexPath 用例);清库后 IT gate → **123 run, 0 failures, 3 skipped**(120 基线 + H2VectorStoreIT 3;skip 为既有 Maven 工具 IT 条件跳过)。
- **概览图(spec §8 结论)**:**不重生成** —— 8 技术栈胶囊仍全准(JVector 仍是 HNSW 引擎名、H2 本就是胶囊);`.claude/skills/project-overview-image` 不动。下一个重生成触发源是 #1(工具组 9→10)。

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
