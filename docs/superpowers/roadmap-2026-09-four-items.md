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
| 4 | #1 AskUser 交互工具 | ✅ 完成(2026-09-08,代码+文档+Chrome 端到端复验全绿;3 个复验缺陷 + 1 个用户反馈缺陷已修) | 大 |
| 末 | 概览图重生成(#1 工具组 9→10 + #3 JVector→H2 会让图失真;全部代码完成后一次性重生成,**需提醒用户**) | 🟡(布局脚本 generate.py 已改 9→10 工具组;PNG 重生成待 `DASHSCOPE_API_KEY` —— 当前环境只有 WORKSPACE_ID + PERSON_TOKEN) | 小 |

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

## #1 AskUser 交互工具(✅ 完成 2026-09-08)

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

### 落地记录(2026-09-08)

- **最终形态**:路径 1 阻塞同流落地。新包 `askuser`:IAskUserTool(@ToolGroup universal)+ DefaultAskUserTool(future.get 阻塞,timeoutSeconds 可注入)+ AskUserRegistry(纯内存);ChatResponseRecord 第 3 组件 askUser(2-arg 兼容构造器);`POST /ask/{questionId}/answer`(跨用户/未知/已失效统一 404);stop 路径 cancelAll 哨兵释放阻塞线程;子任务/定时任务 schema 级排除(DefaultSubTaskExecutor 过滤器 + 委派契约文案);前端内嵌卡片(单选即点即交/多选显式提交/自定义输入/倒计时/已答·已超时·已取消定格)。
- **Commits(按 Task 序)**:T1 `f14006d`(AskUserEvent/AskUserOption/ChatResponseRecord 第 3 组件)/ T2 `41605ec`(AskUserRegistry)/ T3 `5187685`(IAskUserTool+DefaultAskUserTool+properties+beans)+ fix `2aaf7bb`(null-element 守卫)+ fix `b59ccf5`(LoomAgentToolAutoConfigTest 上下文 bean)/ T4 `dc26d48`(answer 端点+stop cancelAll)/ T5 `0f5fc50`(子任务 schema 级排除+契约文档)/ T6 `2511053`(前端内嵌卡片)/ T7a `0028f53`(文档同步+回归门)/ 终审 fix wave `af8110f`(C1 前端单选+自定义提交空串 → trim+filter 空值 & 非 404 失败可重试;I1 流 onError/onTimeout/onCompletion 补 cancelAll;M1 D9 记录)/ **浏览器复验抓到并修复 3 个缺陷**:`9fce0c9`(-parameters + impl @ToolParam — LLM 原本只看到 arg0..arg5 无名无描述,反复猜参数映射且首调失败)、`6e6e516`(blankToNull 归一化字面 "null" — qwen 间歇把可空 header/background 传成字符串 "null" 致卡片渲染出 "null" 字样)、`2bd0b5d`(loomAgentProperties 补 setAskuser — 原本 yml/cmdline 的 askuser.timeout-seconds 被手动逐字段拷贝静默丢弃)/ **用户反馈追加缺陷** `8876b2f`(提交成功后按钮永久停留"提交中..." — freeze() 只 disable 未复位在飞标签;修复为终态隐藏提交按钮,徽章承载终态语义;浏览器复验已答/停止两条终态路径)+ T7b 本 commit(generate.py 布局 9→10 工具组 + 本回填)。
- **与 spec 偏差**:(1) spec §6 的"接线 IT"以 AskUserRouterTest(真 router+真 Registry,无 Spring 上下文,AdminRouterSpotTest 先例)等价交付;(2) answer 路由 body 解析用 `request.body(Map.class)`(仓库 ~40 处同款约定;LoomAgentTestUtil 无 StringHttpMessageConverter),状态码/响应体/join 语义与 spec 完全一致;(3) T3 补 null-element 守卫(fix round,spec 零异常逃逸硬约束的 plan 自身漏洞);(4) **复验暴露 3 个单测/IT/终审都看不到的缺陷**(见 Commits 行 `9fce0c9`/`6e6e516`/`2bd0b5d`)—— 印证 spec §6 把 Chrome 手动复验列为门禁的必要性;其中 `-parameters` 缺失是**全项目潜在 bug**(连既有 startSubTask 都编译成 arg0/arg1,只因参数少+描述清晰一直没暴露),本次顺手修正全工具参数名。
- **回归门**(2026-09-08,fix wave 后复跑四段全绿):库单元 `mvn test -pl spring-ai-loom-agent` → **175 run, 0 failures**(173 + fixwave3 的 2 个 null 归一化测试;首跑曾现 1 个 flaky 失败但 surefire XML 无失败记录、连跑两次均 175/0,系既有 BatchedCounterService/LoggingToolCallback 模拟 DB 故障的计时型 flake,与本次改动无关 —— -parameters 仅元数据、null/binding 改动 askuser-local);`mvn clean install -DskipTests`(排除 4 个 MCP 模块 —— 运行中 MCP JVM 持有 Windows 文件锁,既定先例;4 模块零改动)→ BUILD SUCCESS;test 模块单元 → **394 run, 0 failures**(392 + fixwave4 的 LoomAgentPropertiesBindingTest ×2);清库后 IT gate → **123 run, 0 failures, 3 skipped**(skip 为既有 Maven 工具 IT 条件跳过)。
- **D9 线程预案**:**经验证实无需兜底**。终审字节码核验(spring-ai-alibaba-dashscope 1.1.2.3,DashScopeChatModel.internalStream)预测 tool 执行 continuation 跑在 `Schedulers.boundedElastic()`(非 spec §2 推测的 ForkJoinPool common);**Chrome 复验实测吻合** —— 日志 `Executing tool call: askUser` 出现在 `[boundedElastic-N]` 线程,工具阻塞期间主流内容帧无卡顿,D9 兜底方案(外包 boundedElastic)即现状,未引入。
- **Chrome 端到端复验(T7b,真实 DashScope qwen3.8-max,全新库)**:happy-path 单选 ✅、自定义输入(C1 回归)✅、多选 ✅、刷新后文本持久化(ChatMemory ON_COMPLETE 跨刷新)✅、stop→cancelAll 同毫秒释放阻塞线程(D7)✅、超时→卡片冻结"已超时"+LLM 收"用户未作答"继续不重复提问+ChatMemory 仍落库 ✅、universal 工具正确隐藏于 /api/capabilities 但 LLM 可自由调用 ✅、askuser.timeout-seconds 配置生效(25s override → 倒计时 ~0:25 而非默认 5:00,binding fix 经验确认)✅。
- **概览图**:工具组 9→10,**布局脚本已改**(`generate.py` EN_LAYOUT/ZH_LAYOUT:胶囊 09→10 TOOLS、07→08 ON,卡片区 +Ask-user/问答 第 10 张 + 徽章 1);**PNG 重生成 DEFERRED** —— 需 `DASHSCOPE_API_KEY`(当前环境只有 DASHSCOPE_WORKSPACE_ID + PERSON_TOKEN,变量名不匹配),用户裁定"改布局脚本,稍后生成"。下次持凭据时按 project-overview-image skill 触发流程跑 generate.py 重生成 `docs/project-overview-{en,zh}.png`。

### 四项后续调整落地记录(2026-09-08)

AskUser 主功能落地后追加的四项调整(卡片折叠 / 日志页提问记录 / admin 可分配角色 / 两个官方种子技能):

- **设计文档(spec)**:`docs/superpowers/specs/2026-09-08-askuser-followups-design.md`
- **实现计划(plan)**:`docs/superpowers/plans/2026-09-08-askuser-followups.md`
- **Commits(按执行序 §3→§1→§2→§4)**:
  - T1 `dee7749` — §3 admin 用户可被分配角色(删 console.js ADMIN early-return + hint 附 strict RBAC 提示句;`IRoleService`/`DefaultRoleService.setUserRolesOrSkipAdmin` 标 `@Deprecated`,下一 minor 删除)
  - T2 `491e1a9` — §1 askUser 卡片终态折叠成一行摘要(app.js `freeze()` 加 answerText 参 + `.askuser-wrap`/`.askuser-summary` DOM + style.css 摘要行样式;点击摘要展开回看,escapeHtml 安全)
  - T3 `1c0e73d` — §2 后端只读查询(`IAskUserLogQuery` + `JdbcAskUserLogQuery`,读 `loom_tool_call_log` WHERE tool_name='askUser',不新增表不改写入路径)+ `GET /spring/ai/loom/admin/ask-logs` 路由(adminPathPatterns 门禁;limit 默认 50 钳制 [1,200];status ANSWERED/TIMEOUT/CANCELLED/FAILED/UNKNOWN)
  - T4 `1b93cb7` — §2 admin 日志页 stats.html/stats.js 新增"提问卡片"区块(时间/用户/问题/答案或状态徽章/等待时长"等待 Ns"/会话;username 过滤)
  - T5 `7b3fa27` — §4 V1.0__init.sql 尾部种子 2 条官方技能 market_skill(author=system / APPROVED / is_official=TRUE / created_by_kind=ADMIN / category=表达沟通):"STAR-IJ 讲清一件事"(六步)+ "靶心人公式 讲好一个故事"(七步,原词"转弯";含努力人/意外人 4 步变体)
  - T6 `47cd3dd` — 文档同步(CLAUDE.md 4 处 + docs/API.md/API.zh-CN.md 补 ask-logs 端点行 + 本落地记录)+ console.js 注释口径统一(M3→M5)+ 全量回归门
  - fix `ad7bc8f` — **Chrome 复验抓到**:日志页状态显示"未知" —— Spring AI MethodToolCallback 把 @Tool String 返回值 JSON 序列化成带引号 string literal(`"[用户已回答] x"`),LoggingToolCallback 落库的就是该形态,而 `deriveStatus` 按裸文本 startsWith 判断永不匹配。修复:`JdbcAskUserLogQuery.unwrapJsonString`(Jackson 解 JSON string literal,畸形/null 安全原样返回)+ mapRow 规范化 + 两形态测试(单元 10 用例)+ IT seed 改带引号生产形态(scoped re-review: all addressed)
- **回归门(2026-09-08,T6 四段全绿;fix 后 lib 185)**:库单元 `mvn test -pl spring-ai-loom-agent` → **183 run, 0 failures**(175 基线 + 8 AskUserLogParsingTest,首跑即过无 flaky;fix `ad7bc8f` 后 **185**,新增 2 个带引号形态用例);`mvn clean install -DskipTests`(排除 4 个 MCP 模块,既定先例)→ BUILD SUCCESS;test 模块单元 → **411 run, 0 failures**(397 基线 + T1 的 2 + T2 的 3 + T3 的 4 + T5 的 5);清库(`rm -rf ~/.loom/datasource` + `target/test-ds` + `target/surefire-reports`)后 IT gate → **127 run, 0 failures, 3 skipped**(123 基线 + SeedSkillIT 2 + JdbcAskUserLogQueryIT 2;skip 为既有 Maven 工具 IT 条件跳过)。
- **升级提醒**:§4 种子行在 V1.0__init.sql 内 —— **已运行实例需清库重启**(`rm -rf ~/.loom/datasource`)才能拿到 2 条官方种子技能(项目"只跑全新库"政策,无增量迁移)。
- **Chrome 复验(2026-09-09,controller 执行,全新库 8080 + 真实 DashScope qwen3.8-max + MCP 启用)**:
  - V1 已答折叠 ✅:卡片隐藏,摘要 `✓ 问题 → 答案` 绿(ellipsis 生效),点击展开(▸→▾,inputs 禁用/提交按钮隐藏,只读回看)再收起
  - V2 超时折叠 ✅:`⏳ 问题 → 已超时，未作答` 灰;多卡片独立不串扰
  - V2b 停止折叠 ✅:stop → `✗ 问题 → 已取消`;流结束 cancelAll → `✗ → 已结束`
  - V3 XSS ✅:question/label/自定义答案全 payload(multiSelect+custom)→ 摘要行 textContent 字面转义,img/svg/b 注入元素 0,`__XSS_*` 全 undefined
  - V4 日志页 ✅(fix `ad7bc8f` 后复验):真实 LLM askUser 作答 → "提问卡片"区块出现记录,答案文本 + `等待 6s` + 会话 ID,username 过滤生效;原始 JSON `status=ANSWERED` 正确
  - V5 admin 分配角色 ✅:弹窗加载角色列表(旧文案已消失),hint 含 strict RBAC 提示句,勾选保存 toast 成功,`GET roles` 确认
  - V6 RBAC 闭环 ✅:dev-role 授权 bing-search → admin 的 capabilities 中 bing-search `effectiveEnabled=True`,其余 MCP/RBAC 工具仍 False —— admin 的 MCP 确实按角色控制
  - V7 种子技能 ✅:admin 市场页 2 条官方技能(APPROVED + 🏛️ 官方徽章 + category=表达沟通);普通视角 pull STAR-IJ 成功
  - V8 STAR-IJ 真实 LLM 一问一答 ✅:选中技能发"讲清楚上周项目上线" → LLM 第 1 问 S情境(header"情境",4 引导选项+自由输入)→ 作答折叠 → 自动第 2 问 T任务(header"任务"),**逐步推进、不自问自答、不描述计划**;stop 后第 2 卡冻结"已结束"
  - V9 回归 ✅:工具弹窗(RBAC 3 工具+MCP 列表)/文件模态框/textarea/send 全正常,console 零 error/warn
  - 截图:`docs/superpowers/reports/assets/v1-summary-answered.png`、`v4-asklog-fixed.png`
- **全分支终审(opus,fb6b33b..d447e1e,2026-09-09)**:裁决 **可合并**,0 Critical / 0 Important;安全面(XSS textContent+5 字符 escapeHtml、SQL 全参数化、门禁零路由内校验、种子 SQL 幂等)逐字核实通过;AskUserLogRecord 9 字段/status 5 枚举在后端·路由·前端·文档四处一致。
- **Follow-ups(终审 N1-N6 + 缓议 Minor,归 3 组,本期不做)**:
  - **组 A(下一 minor 删 `setUserRolesOrSkipAdmin` 时同一 commit)**:DefaultRoleService 实现侧补 `@deprecated` javadoc 标签;迁移 `LoomAgentConfiguration` L2154 + `DefaultRoleServiceErrorMappingTest` 两处调用点(删方法时编译告警即硬提醒)。
  - **组 B(测试补齐)**:`recent(500)` → 上限钳制 200 断言(锁 `MAX_LIMIT`);`extractAnswer` 前缀后纯空白边界用例。
  - **组 C(前端/文档小清扫)**:CLAUDE.md L107 "14 RouterFunctions" 计数陈旧(实测 19)→ 改数字或去数字化措辞;stats.js FAILED 徽章硬编码 `#ef4444` → 换 danger token;stats.js 旧 `load()` catch 的 `${e.message}` 补 escapeHtml(新 `loadAskLogs` 已是正确范本);`q.slice(0,60)` emoji 代理对截断(纯观感,可留);`fmtWait` Math.round 60s 边界舍入(可留);`map[status]` 原型键理论 TypeError(`Object.hasOwn` 一行加固,可留)。
  - **组 D(schema,触发式)**:loom_tool_call_log 增长到 10⁵+ 行时,V1.0 补 `(tool_name, created_at)` 索引(fresh-DB 政策下零成本);当前 admin-only + LIMIT≤200 全表扫可接受。
  - **组 E(SSE 断连,第三轮测试 E1/E2 发现)**:askUser 挂起期间用户**刷新/登出**不会即时触发 cancelAll —— Tomcat 异步 servlet 仅在写入时检测断连,阻塞期 SSE 无数据帧 → onError/onCompletion 不触发 → 挂起 future 占 1 个 boundedElastic 线程至 timeoutSeconds(默认 300s)才释放。低危(自我伤害面、最终释放、无数据损坏;stop 按钮路径不受影响,主动 POST /stop 已验秒级 cancelAll)。**增强候选**:askUser 挂起期 SSE 定期发心跳帧(keep-alive),刷新后下次心跳写入即触发 onError → cancelAll 秒级释放线程。

### 第三轮全面测试(2026-09-09,四项调整后全量验证)

报告:`docs/superpowers/reports/2026-09-09-round3-comprehensive-test.md`。**结论:通过,0 需修缺陷。** 本轮 **MCP 首次启用**(前两轮关闭)+ **RAG 路径(#3 H2JVectorStore)生产首验**:
- R1 MCP 环境差异 4/4(RBAC 双维度授权、混合流、ask-logs 隔离性、JSON UTF-8);R2 RAG 3/3(上传→embed 落库 dim=1024、检索→引用、**重启 hydrate loaded=1 零 re-embed**);R3 STAR-IJ 完整六步教科书级(7 问含 A 追问、立即汇总、电梯稿);R4 定时任务子任务在挂起提问期间并发触发、schema 级排除线程级实证;R5 边界 E1-E4(登出/刷新/断网重试/双用户跨会话 404 隔离);R6/R7 回归+响应式全过。
- 三轮累计抓缺陷:第一轮 4(-parameters/字面 null/配置绑定/按钮卡死)+ 第二轮 2(CSS token/移动裁切)+ **第三轮 1**(result_text JSON 引号形态 → fix `ad7bc8f`)—— 质量逐轮收敛。
- 唯一架构观察 = 组 E(SSE 惰性断连),低危不阻断。

---

## 维护约定

- 每完成一项,更新本文件对应小节状态 🔵→🟡→✅ + 追加"落地记录"(commit 号、偏差决策)。
- 本文件是活文档;与 CLAUDE.md 冲突时以 CLAUDE.md 为准(本文件完成后要把结论合并回 CLAUDE.md/README)。
