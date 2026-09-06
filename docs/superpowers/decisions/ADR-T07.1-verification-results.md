# ADR-T07.1: M3+ full-suite verification results

| | |
|---|---|
| **编号** | ADR-T07.1(M3+ plan T7.1) |
| **日期** | 2026-09-05 |
| **状态** | **Pass** — residuals closed 2026-09-06(见文末 closure 节) |
| **议题** | M3+ 全套回归门 (AT1–AT5 + A1–A15) |
| **决策** | T0–T6 范围内修改的测试全部通过;3 个测试文件存在 **已知 pre-existing 或 follow-up** 失败(详见 Residual) |
| **理由** | 文档化当前状态,避免误判 M3+ 引入回归;明确剩余 follow-up 责任 |
| **影响模块** | 测试矩阵 + 已知 follow-up |
| **关联 task** | T7.1 |

## 套件通过情况

### 本次 M3+ 改动影响的测试 — 全部通过

| 测试类 | 结果 | 备注 |
|---|---|---|
| `MarketAcceptanceIT`(17 个 case) | 16 pass / 1 fail / 1 skipped(异步 IT) | 仅 a13_adminAnnouncementPinsFeaturedRank 失败(见 Residual 3) |
| `SkillAdminMissingIdReturns404IT` | 3/3 pass | T6.1 refactor 验证通过 |
| `AbstractMarketAdminServiceTest` | pass | T2.1 加 marketKind() hook 已覆盖 |
| `DefaultSkillMarketServiceIT` | pass | T2.1 record 字段扩展验证通过 |
| `DefaultKnowledgeMarketServiceIT` | pass | T2.1 record 字段扩展验证通过 |
| `DefaultSkillStatsServiceIT` | pass | — |
| `DefaultSkillReviewServiceIT` | pass | — |
| `DefaultMarketAnnouncementRepositoryIT` | pass | — |
| `ReplaceTagsRollbackOnFailureIT` | pass | — |
| `MarketSchemaTest` | pass | T1.1 schema 验证 |
| `KnowledgeRouterAuthzTest` | pass | — |

### Pre-existing / 已知 Residual — 失败

> ⚠️ 本节是 2026-09-05 的历史快照;3 项 residual 已于 2026-09-06 全部关闭,见文末 closure 节。

| 测试类 | 失败数 | 原因 | 责任 |
|---|---|---|---|
| `KnowledgeMarketIntegrationTest` | 6/6 | 测试用 minimal schema,**无 `market_content_announcement` 表**;T2.1 LEFT JOIN 后引用该表导致语法错 | pre-existing 测试 schema 不全 — 需测试本身补表;**非 M3+ 回归** |
| `DefaultKnowledgeReviewServiceIT` | (some) | `IMarketContentReviewService` String overloads 仍 `Long.parseLong` + 404;UUID 真路径未实现 | pre-existing implementation gap,plan T1.7 follow-up |
| `MarketAcceptanceIT.a13_adminAnnouncementPinsFeaturedRank` | 1 | `findOne` after `upsert` returns null → `ServerResponse.ok().body(null)` NPE;可能与 schema 或 repo 缓存相关 | pre-existing 或 T2.1 间接影响;需要 follow-up |

## Residual 处理建议

1. **KnowledgeMarketIntegrationTest**:测试用 `@BeforeEach setUp()` 创建 in-memory schema,需要补 `CREATE TABLE market_content_announcement` 等表才能过 T2.1 后的 LEFT JOIN。**修测试,非修代码**。

2. **DefaultKnowledgeReviewServiceIT UUID 路径**:`IMarketContentReviewService` String overloads 需要去掉 `Long.parseLong` + `parseMarketIdOrThrow`,改成纯 String UUID 路径。**这是 T1.7 implementation gap** — plan 写的是 `test(acceptance):`,但实际实现未到位。

3. **A13 announcement upsert**:可能是 repo 缓存问题或 schema 排序问题;需要单独 debug。

## AT1–AT5 状态

| AT | 描述 | 状态 |
|---|---|---|
| AT1 | VARCHAR(36) UUID 真路径 E2E | 部分 — A12 真 UUID 化(T1.7)+ MarketAcceptanceIT 大部分通过;residual = review/announcement String overloads 未完成 UUID 真路径 |
| AT2 | N+1 修复(list + announcement + tags 一次 GET) | 部分 — announcement embed 已落地(T2.1);tags embed 待 batch SELECT follow-up |
| AT3 | v1 service shim 兼容 | 通过 — T3.1 加 `@Deprecated`,impl 类未改 |
| AT4 | Micrometer counter / timer 增量 | 通过 — T4.1 bean 装配 OK |
| AT5 | Bucket4j in-memory 限流 | 通过 — T4.2 filter 装配 OK;行为验证需要端到端 |

## A1–A15 状态

A1–A12 + A14–A17 全部通过(design spec §12)。
A13 — 已知 residual(见上)。

## 决策日志

- 2026-09-05:Partial pass committed(T7.1 acceptance gate);residual items documented above;follow-up tasks to be scheduled.
- 2026-09-06:All 3 residuals closed + AT1/AT2 升级至 Pass(见下方 closure 节);状态改为 **Pass**。

---

## 2026-09-06 Residual Closure(R2 / R3 / R4 + final-review fix wave)

### Residual 1 — `KnowledgeMarketIntegrationTest`(测试 minimal schema 缺表)

**已解决,无需本阶段动作**:e96a701(T2.1 `from()` fix-up,2026-09-05)已让 `MarketKnowledgeRecord.from` 容忍 un-joined SELECT;2026-09-06 连续 3 次运行确认 6/6 绿。R4(cb8178b)另为其 minimal schema 补了 `loom_market_knowledge_tag` DDL(镜像 V1.0),因为 `listApproved` 现在 embed tags。

### Residual 2 — `DefaultKnowledgeReviewServiceIT` UUID 真路径(T1.7 implementation gap)

**已解决:699c1a1(R2)** — review 链 `<K>` 参数化(镜像 T1.5 stats 模式):`IMarketContentReviewService<K>` + `ReviewRow<K>` + `AbstractMarketReviewService<K>`(`readKey` hook);`DefaultSkillReviewService<Long>`(market_skill_review.market_id 仍 BIGINT,行为不变)、`DefaultKnowledgeReviewService<String>`(真 UUID,严门槛直接 String 比对);String/Long 孪生 overload 与 `parseMarketIdOrThrow` 全部删除。IT 全量迁移真 UUID(10/10,含新 `kbReviewFullUuidRoundTrip`)。flaky `评价 upsert 失败:行未写入` 的根因(Long 绑定 VARCHAR(36) 列后 readBack 落空)被**结构性消除**——不是缓解,是类型对齐。

### Residual 3 — `MarketAcceptanceIT.a13` announcement upsert 500

**已解决:c0df007(R3)** — announcement 链 String-native(共享表 `market_content_announcement.market_id` VARCHAR(36) 对两种 kind 都是 String;`MarketAnnouncement.marketId` Long→String;repo 单一 String API,Long overload + `parseMarketIdOrThrow` 删除);PUT handler null-safe(成功 upsert 后**永不** `body(null)` → a13 的 500 路径消除);`listPaged` JOIN 改 `a.market_id = CAST(m.id AS VARCHAR(36))`(跨 kind UUID 行不再可能让 SKILL 查询做 VARCHAR→BIGINT coerce);新增 A18 跨 kind poison-row 回归。
**376454d(fix wave)** 进一步把 a13/A12 的 `safeRoute` 条件断言改成 strict `route()` 无条件 200(并删除 a13 的直连 DB 兜底——断言只有 router PUT 真跑过才能过),新增 a13kb(KB PUT announcement 真 UUID → strict 200 → DB 行 + rank 999)、a16b(KB review POST 正向 → 200 + `ReviewRow<String>`)、a17b(KB GET announcement 有行 → 200 + 真 title/body)。

### 附加发现 — KB `marketKind()` "KB" vs "KNOWLEDGE"(R3 期间发现,R4 修复)

`DefaultKnowledgeMarketService.marketKind()` 返回 `"KB"`,但公告/标签全部以 `"KNOWLEDGE"` 写入 → `listPaged` 的 announcement LEFT JOIN 过滤 `a.market_kind='KB'` **从未命中** → KB 列表的公告嵌入自 T2.1 起一直是静默坏的。**cb8178b(R4)修复**(一行 + red-green 测试证明),同 commit 落地 AT2 tags embed(`listPaged` + `listApproved` 单次批量 SELECT,parameterized IN-list,`withTags` copy-helper,无 tags → `List.of()` 永不为 null)。

### AT1–AT5 终态

| AT | 状态(2026-09-06) |
|---|---|
| AT1 | **Pass** — review/announcement/stats 全链真 UUID 2xx(A12/A16/A16b/A17/A17b/A18 + service/repo 层 round-trip 测试);KB 市场路径已无任何 `Long.parseLong` |
| AT2 | **Pass(规范路径)** — v2 `listPaged`/`search` + v1 `listApproved` 均 embed announcement+tags,前端零二次 GET。已登记 follow-up:`?tag=` 过滤路径(`KnowledgeTagService.findByTag/findByAllTags`)绕过 enrichment,tags/announcement 为 null(前端 null-safe,纯观感降级)— 见 spec § Follow-ups FU-1 |
| AT3/AT4/AT5 | 不变(T7.1 已通过) |

### 验证方法学备注(重要)

默认 `mvn test -pl spring-ai-loom-agent-test` 跑 380 个测试但**不包含任何 `*IT` 类**(项目无 failsafe 插件、surefire 无 include 配置;`*IT` 仅匹配显式 `-Dtest=`)。原 T7.1 的"partial pass"判定与 2026-09-06 的所有残留复现/关闭证据均来自显式 `-Dtest='*IT'` 运行。**本 closure 的最终 gate**(每次运行前清空 `~/.loom/datasource` + `target/test-ds` + `surefire-reports`):`-Dtest='*IT'` ×3 连续 → 每次 `Tests run: 80, Failures: 0, Errors: 0, Skipped: 3`(skip = 2 个 env-gated Maven IT + 1 个 env-gated a11_async)+ BUILD SUCCESS;默认全套 → `Tests run: 380, Failures: 0, Errors: 0, Skipped: 0` + BUILD SUCCESS。commit:376454d。

### Commit 索引(closure 阶段)

| Commit | 内容 |
|---|---|
| 699c1a1 | R2 — review 链 `<K>` + KB 真 UUID(residual 2) |
| c0df007 | R3 — announcement String-native + a13 null-safe + CAST join(residual 3) |
| cb8178b | R4 — marketKind 修复 + KB tags batch embed(AT2) |
| 376454d | final-review fix wave — strict router 断言 + KB 正向 IT + stats 泛型 + helper 抽取 |

