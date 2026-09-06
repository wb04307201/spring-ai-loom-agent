# M3+ Technical Debt Cleanup — 设计稿

| | |
|---|---|
| **状态** | DRAFT |
| **日期** | 2026-09-05 |
| **议题** | 对 M0/M1/M2 升级(commit `26834b0..dceaddc`)留下的 24 项技术债做分阶段清理 |
| **影响模块** | `spring-ai-loom-agent` + `spring-ai-loom-agent-spring-boot-autoconfigure` + 前端 |
| **Schema 落地** | V1.0 末尾追加(B1 真修需要) |

---

## 1. TL;DR

按 7 个 phase(T0–T6, 8–12 周)清理已识别的 24 项技术债。核心 phase 是 **T1(B1 真修)** —— 把 `BIGINT id` 与 `VARCHAR(36) KB UUID` 的 schema 类型分歧抹平,系统性消除 graceful-degradation / String-keyed twins / `Long.parseLong` fallback 等 8 项临时补救。

---

## 2. 用户决策(已确认)

- **B1 真修**:把 KB 与 skill 两侧 id 类型对齐 —— `market_content_announcement`、`loom_market_knowledge_stats`、`loom_market_knowledge_review` 三表的 `market_id` 列 `BIGINT → VARCHAR(36)`,同时 `IMarketContentAdminService<M, U, R>` 重构为 `<K, M, U, R>`。
- **今天只写 plan,PR 之后再说**:本次只输出 spec + plan 文档,不实际动代码 / 不开分支 / 不开 PR。

---

## 3. 决策汇总表

| 议题 | 决策 | 理由 |
|---|---|---|
| 路径分类 | **Architectural**(影响 schema + 接口 + 路由 + 前端四层) | 范围跨多子系统,4 个 spec 会触达 |
| B1 SCHEMA 方向 | **统一 VARCHAR(36)**(不是统一 BIGINT) | `loom_market_knowledge.id` 已是 VARCHAR(36) UUID,迁移成本对称 |
| B1 接口方向 | `<K>` 参数化抽象类 | 一致性 / 编译时清晰(Long 与 String 行为不可混) |
| B3 v1 服务清理 | **保留旧接口作为 shim,新功能全部走 v2**,观察 1 个小版本后视引用计数删除 | 兼容性优先,避免回归 |
| B2 N+1 修方向 | **后端 list SQL 加 LEFT JOIN,DTO 带 `announcementTitle` / `announcementBody` / `tags`** | 1 次 GET 替代 N 次小请求,符合 spec § 6.3 注释 |
| B4 Skill tag | **本期不做**,登记为 M4 候选 | spec § M2 只要求 KB tag |
| B8 i18n / metrics / rate-limit | **小修**(T0 抽 i18n key 占位 + T4 上 metrics + rate-limit) | 三者独立、可独立 ship |
| 阶段切分 | **T0 = doc 小补**,T1 = B1 真修,T2–T6 = 性能 / 架构 / 可观测 / 可移植 / 测试 | 风险递进 |

---

## 4. 数据模型变更

### 4.1 加列(A12 项)

```sql
ALTER TABLE market_skill          ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE loom_user_knowledge  ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
```

注:`market_skill.updated_at` 在 T5 final review 已识别为 missing column;`loom_user_knowledge.updated_at` 用以和 review 表的 `updated_at` 对齐。

### 4.2 改列(B1 真修)

```sql
ALTER TABLE market_content_announcement   MODIFY market_id VARCHAR(36);
ALTER TABLE loom_market_knowledge_stats  MODIFY market_id VARCHAR(36);
ALTER TABLE loom_market_knowledge_review MODIFY market_id VARCHAR(36);
```

迁移路径:由于当前生产这些表的 PK 列允许并已约束为 BIGINT,实际存量行 id 值都是 numeric test data(测试用),可被 H2 直接隐式转换 VARCHAR(36)。无需数据迁移脚本。

### 4.3 索引重审

由于 review / stats 表的 PK = `(market_id, ...)` 改了列类型 VARCHAR(36),PK 索引自动适配;无需 DDL 重建。

---

## 5. 接口与类架构变更

### 5.1 抽象类参数化

```java
// 当前(T3 引入):
public interface IMarketContentAdminService<M, U, R> {
    M getById(Long id);                                  // ← Long 强类型
    M update(Long id, MarketUpdateRequest req);
    ...
}
public abstract class AbstractMarketAdminService<M, U, R> implements IMarketContentAdminService<M, U, R> {
    protected abstract Long extractId(M entry);           // ← 强类型 Long
    ...
}

// T1 修后:
public interface IMarketContentAdminService<K, M, U, R> {
    M getById(K id);
    M update(K id, MarketUpdateRequest req);
    K extractId(M entry);                                // 注意:abstract method 移到 interface
    ...
}
public abstract class AbstractMarketAdminService<K, M, U, R> implements IMarketContentAdminService<K, M, U, R> {
    ...
}
class DefaultSkillMarketService   extends AbstractMarketAdminService<Long,  MarketSkill, UserSkill, SkillReview>    { ... }
class DefaultKnowledgeMarketService extends AbstractMarketAdminService<String, MarketKnowledgeRecord, UserKnowledge, KnowledgeReview> { ... }
```

`extractId` 从 abstract method 升到 interface method(因为 skill 和 KB 的 id 类型不同)。

### 5.2 删除

- `DefaultKnowledgeMarketService` 上的 **String-keyed twin 方法**(`getById(String)` 等),全部改走泛型抽象的 `getById(String)` —— `Long` / `String` 类型的消失让 twin pattern 不再需要。
- `LoomAgentConfiguration` 内被标记 `// DEFER to T3.2` 的 v1 router bean 路径冲突 —— T3.2 修后 v2 router 已经完全覆盖,T3.2 不再需要。
- `MarketAdmin.listWithAnnouncements` / `listWithTags`(前端 in-place mutate helper)—— T2 修后删,前端改读 DTO 嵌入式字段。
- `MarketAnnouncementRepository.findOneByRawId(String, String)`(T19 round 2 加的 UUID-tolerant workaround)—— T1 修后删,改用 `findOne(String marketKind, K marketId)`。
- `DefaultKnowledgeMarketService.findOne(String marketId)` 的 `Long.parseLong` graceful-degradation 注释 / 路径 —— T1 修后删。
- `defaultGateway endpoint` 里的 `Long.parseLong` 改 `idParser.apply(pathVariable)`(下一项)。

### 5.3 路由层 id 解析

```java
// 当前:每个 KB router handler 自己写 Long.parseLong
.andRoute(GET(".../market-knowledge/{id}"), req -> {
    String id = req.pathVariable("id");
    // Long.parseLong(id) → 400 on UUID  → graceful degradation fallback ...
})

// T1 修后:抽象统一
public interface RouterIdParser<K> { K parse(String rawId); }
public class SkillRouterIdParser implements RouterIdParser<Long> {
    public Long parse(String s) { return Long.parseLong(s); }   // numeric only
}
public class KnowledgeRouterIdParser implements RouterIdParser<String> {
    public String parse(String s) { return s; }                // raw UUID
}
```

T3 refactor 时抽出来(T3.2 parked 项目顺道做)。

### 5.4 LLM 工具集成

`DefaultKnowledgeTool.searchKnowledge` 的 try/catch `NumberFormatException` 路径删除 —— T1 修后不再 no-op。`kbStatsService.incrementStat(...)` 改为 `kbStatsService.incrementStat(marketKnowledgeId, "SEARCH")` 直接 UUID 入参。

---

## 6. REST 行为变更(端点级)

| 行为变更 | 端点 | 变更前后 |
|---|---|---|
| KB UUID 入参 | `GET /admin/market-knowledge/{id}` | 修前:`400 on UUID`。修后:`200` 真 UUID 路径。 |
| KB announcement | `GET /market-knowledge/{id}/announcement` | 修前:`null` (UUID rejected silently)。修后:`200` 真 KB announcement。 |
| KB stats | `GET /market-knowledge/{id}/stats` | 修前:`{id: null, searchCount: 0}` (UUID)。修后:`真实 row`。 |
| KB review | `POST /market-knowledge/{id}/reviews` | 修前:`500` (PK 类型不匹配)。修后:`201` 实际写入。 |
| list N+1 | `GET /market-skills` / `/market-knowledge` | 修前:List DTO 无 announcement / tags 字段,前端发 N 次附加 GET。修后:DTO 嵌入式,1 次搞定。 |
| Skill tag | — | **本期不做**,登记 M4 候选。 |
| old `ISkillMarketService` 接口 | — | shim 保留 1 个小版本。 |

---

## 7. 非目标(本期不做)

- Skill tag(虽然镜像一 KB tag 表可做,但本期聚焦清理债,不做新功能)
- B1 之外的真实 schema 改动(如 `loom_role_*` cascade review、file content BLOB cleanup)
- v3 ≥ 新功能(skill 推荐系统、KB 知识图谱、跨市场搜索 dashboard)
- 完整的可观测性栈(只有 Micrometer exporter + rate-limit bucket;tracing 不在本期)

---

## 8. 风险与缓解

| 风险 | 缓解 |
|---|---|
| B1 schema migration 在已有 env 上失败 | 项目策略:全新库 V1.0 一站式;老库 baseline 后人工迁移。H2 隐式转换 BIGINT → VARCHAR(36) 在已 numeric 库上行级 work。 |
| `IMarketContentAdminService<K, ...>` 重命名/参数化签名破坏下游消费者 | 现有消费者只有 `spring-ai-loom-agent-test` 一个;plan 把迁移作为 task 1.3 的必交付。 |
| 删除 String-keyed twins 后某个旧 KB router 漏改 | T1 修完后跑完整 IT(`mvn test`)必须全绿,否则该 phase 不 complete。 |
| v1 service 残留(老路由、老接口) | B3 阶段保守:shim 保留 1 个小版本,grep `ISkillMarketService` 引用计数归零后才正式删除。 |

---

## 9. ADR(决策记录)

| 编号 | 议题 | 决定 |
|---|---|---|
| ADR-T01 | B1 schema 方向 | 统一 VARCHAR(36);KB 端 / skill 端接口用 `<K>` 区分。 |
| ADR-T02 | String-keyed twins 保留? | 删除(T1 修后不再需要)。 |
| ADR-T03 | v1 service 路径 | shim 保留 1 小版本,观察期后删。 |
| ADR-T04 | N+1 修方式 | 后端 DTO embed + 前端不再 per-row parallel fetch。 |
| ADR-T05 | spec drift(A3/A10) | spec 改 422 → 403(代码已返 403,落后于 spec)。 |
| ADR-T06 | Skill tag | **不做**,M4 候选。 |
| ADR-T07 | Metric 栈 | Micrometer + Spring Boot Actuator,**不做** Prometheus / OTel tracing。 |
| ADR-T08 | Rate-limit | in-memory bucket(Bucket4j),不是 Redis 分布式;**不**支持横向扩展限速。 |

---

## 10. Rulings I made (统筹自原 SDD ledger 中沉淀的 9 项 + 本 spec 8 项)

1. T3 declares all 3 interfaces + MarketAnnouncementRepository
2. T17 admin exclusion SQL fallback chain
3. T17 MERGE INTO upsert + edit_count 服务层校验
4. T1 fix-up:ADD COLUMN access_count + update index + new columnExists
5. 测试数据源 wipe 包括 ./target/test-ds
6. KB id type String-keyed twins(被 ADR-T01 替代)
7. v1/v2 router bean 路径冲突 parked(被 T1 修后解决)
8. final review MUST-FIX-1:Skill 404
9. final review MUST-FIX-2:KB tag @Transactional

加 spec 内新 ruling:

10. ADR-T01:B1 真修 —— VARCHAR(36) 统一 + `<K>` 参数化
11. ADR-T03:v1 service 保留 shim 1 小版本
12. ADR-T04:N+1 修 = 后端 DTO embed
13. ADR-T05:spec drift A3/A10 422 → 403(spec 跟代码对齐)
14. ADR-T06:Skill tag 不做(M4 候选)

### §10.1 Ruling Classification (4-bucket)

| Bucket | 含义 | 已识别条目 |
|---|---|---|
| **Planned** | plan T0–T6 + T7.1 有 task label | T1.1 / T1.3 / T1.4 / T1.5 / T1.6 / T2.1 / T2.2 / T3.1 / T3.2 / T4.1 / T4.2 / T4.3 / T5.1 / T5.2 / T5.3 / T6.1 / T6.2 / T7.1 |
| **Pre-fixed** | M3 plan 起草前已修复,无 plan task | Ruling #2 admin-exclusion SQL (`aaadff7`); Ruling #3 `edit_count < 1` 校验 (`aaadff7`); Ruling #4 access_count / index / columnExists (`fdb80bf`); Ruling #8 Skill missing-id 404 (`dceaddc`); Ruling #9 KB tag @Transactional rollback (`dceaddc`) |
| **M4 defer** | spec §3 / §7 显式延期 | B4 Skill tag(spec 写明 "本期不做, M4 候选") |
| **Untracked** | 进程级约束,无 code task | Ruling #5 测试数据源 wipe(plan §24 process constraint) |

注:本表分类基于 dev 分支 commit 证据 + spec 文本交叉推断,非权威。Ruling #1 / #6 / #7 / #10–#14 待逐条复核后归类。

---

## § Verification (T7.1)

> **状态(2026-09-06):Pass。** T7.1 初验为 partial(ADR-T07.1);3 项 residual 已由 R2 `699c1a1` / R3 `c0df007` / R4 `cb8178b` / fix wave `376454d` 关闭,AT1、AT2 升级至 Pass。最终 gate:`-Dtest='*IT'` ×3 连续 80/0/0(3 env-gated skip)+ 默认全套 380/0/0,全部 BUILD SUCCESS。详见 ADR-T07.1 closure 节。

M3+ cleanup 的最终验证由本节定义。E2E 验收复用 [`docs/superpowers/specs/2026-09-04-skill-knowledge-market-design.md`](2026-09-04-skill-knowledge-market-design.md) §12 A1–A15,本节定义 cleanup-specific 的 AT1–AT5。

### AT1: VARCHAR(36) UUID 真路径 E2E — **Pass(2026-09-06)**

- 触发:KB review / stats / announcement 端点用 UUID 入参
- 期望:端点返回 2xx + 真实结果。**措辞修正(2026-09-06,final-review Minor #6)**:对 spec 点名的验证 UUID(A16/A17 用**不存在**的 UUID),合法结果是 200 + 空 page / 204——"真实 row 非 null"仅适用于**已 seed 数据**的正向用例(a13kb / a16b / a17b / kbReviewFullUuidRoundTrip,fix wave 376454d 补齐)
- 验证位置:`MarketAcceptanceIT` A12 / A16 / A16b / A17 / A17b / A18 + `DefaultKnowledgeReviewServiceIT`(全真 UUID)

### AT2: N+1 修复(list + announcement + tags 一次 GET)— **Pass(规范路径,2026-09-06)**

- 触发:`GET /market-skills` 或 `/market-knowledge`
- 期望:response DTO 包含 `announcementTitle` / `announcementBody` / `tags` 字段;前端不再二次 GET
- 状态:v2 `listPaged`/`search`(T2.1 announcement LEFT JOIN + R4 tags 批量 SELECT + R4 `marketKind()` "KB"→"KNOWLEDGE" join 修复)与 v1 `listApproved`(R4 tags embed)均满足。**遗留:`?tag=` 过滤路径绕过 enrichment → FU-1**
- 验证位置:`MarketAcceptanceIT`(A18 + embed 断言)+ `DefaultKnowledgeMarketServiceIT`(announcement/tags/coexist 用例)+ `market-admin.js` / `app.js` 行为(T2.2)

### AT3: v1 service shim 兼容

- 触发:`ISkillMarketService` / `IKnowledgeMarketService` 旧 API 调用
- 期望:1 个 minor version 期间仍可工作;新代码全部走 v2
- 验证:grep `ISkillMarketService` 引用计数随 release notes 下降;最终 = 0 才删除 shim(T3.1)

### AT4: Micrometer counter / timer 增量

- 触发:`/admin/market-*` 端点被调用
- 期望:`/actuator/metrics/...` 显示 counter / timer 增量
- 验证位置:`MarketAcceptanceIT` 检查 metrics endpoint(T4.1)

### AT5: Bucket4j in-memory 限流

- 触发:`/pull` / `/access` / `/reviews` 高频请求
- 期望:超阈值返回 429 + Retry-After header
- 验证位置:`MarketAcceptanceIT`(T4.2)

### 回归门(完整)

- `mvn test` 全绿(IT + unit)。**方法学修正(2026-09-06)**:默认 `mvn test -pl spring-ai-loom-agent-test`(surefire 默认 include,无 failsafe)只跑 `*Test` 类(380 个),**不跑任何 `*IT` 类**;IT gate 必须显式 `-Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false`(当前 80 个,3 个 env-gated skip),且运行前清空 `~/.loom/datasource` + `target/test-ds` + `target/surefire-reports`(陈旧 report 会伪装成覆盖)。flaky 类残留的关闭标准 = ×3 连续绿
- A1–A15(design spec §12)全部通过
- AT1–AT5 全部通过

### Defer / out-of-scope(T7.1 不验证)

- B4 Skill tag — M4 候选
- 完整 i18n framework / Prometheus / OTel / Redis 分布式限流 — 显式 out-of-scope
- v1 service 完全删除 — 等 AT3 引用计数归零(>1 minor version)
- KB 物理文件 versioning(ADR-002)— M4 defer
- LOOM_VOICE banner 集成 — spec §17 第 4 问已显式删除

### § Follow-ups(post-M3+,登记于 2026-09-06 final whole-branch review)

| ID | 内容 | 严重度 | 备注 |
|---|---|---|---|
| FU-1 | `?tag=` 过滤路径(`KnowledgeTagService.findByTag` / `findByAllTags`)直接 `SELECT mk.*`,绕过 listPaged/listApproved enrichment → 返回记录 tags=null 且 announcement=null。前端 null-safe(Array.isArray guard + detail 面板单独 fetch),纯观感降级(过滤视图无 chips/banner)。候选方案:让 tag 过滤走 `listPaged`(`MarketFilter` 加 tag 维度)或把 `embedTags` 暴露给 tag service;一并考虑 v1 `listApproved` 的 announcement 对称性 | Minor / 非阻塞 | R4 concern 1 + final review R4(1) triage |
| FU-2 | 分页 size 无上限 → `embedTags` IN-list 无界(`parsePageOr` 不 clamp;`findByAllTags` 有 100 上限)。候选:routers 或 `MarketFilter` clamp size ≤100/200 | Minor / pre-existing 放大 | final review Minor #5 |
| FU-3 | 杂项:deferred minors 汇总 —— `DefaultKnowledgeTool` field/ctor + `LoomAgentToolAutoConfigTest` mock 仍 raw `IMarketContentStatsService`(public 构造器签名,API 面);A18/a13kb/a16b seeded 行不清理(计数断言全部 UUID-scoped,良性);`MarketKnowledgeRecord.from()` 体内注释仍说 listPaged-only;KnowledgeMarketIntegrationTest 缺 idx_market_kb_tag;readBack 500 消息含 marketId | Minor | SDD ledger triage 全部 OK-TO-DEFER |
| FU-4 | v1/v2 admin 路由重复注册(灰度设计,v2 按 bean order 生效)— T3.2 `MarketAdminRoutesHelper` 全量化时退役 v1 twin。**2026-09-06 Chrome UI 全链路测试确认运行时表现**:skill 端 GET list 由 **v1 router 胜出**(返回 ARRAY 形状、`announcementTitle` 恒 null、无 `featuredRank` 字段);KB 端返回 **v2 Page 形状**(`{items,total,page,size}`,tags+announcement embed 生效)。两侧不对称但无用户可见功能损失(前端 detail 面板单独 fetch `/announcement`,banner 正常)。退役 v1 twin 时一并消除 | Minor / 架构 | R4 concern 2 + T3.2 pattern-only 遗留;2026-09-06 Chrome UI 测试实测证据(skill ARRAY vs KB Page) |
| FU-5 | CHANGELOG 已记录本轮 API 可见变更(Unreleased 节);发版时随 release notes 发布 | 流程 | final review Minor #3 |
| FU-6 | **已修复(2026-09-06)** — `market-admin.js` 的 `ADMIN_ANNOUNCEMENT_API` / `ADMIN_REVIEW_API` 用 `market-${kind}s` 模板拼路径:SKILL→`market-skills`(对),KNOWLEDGE→`market-knowledges`(**错**,后端 KB admin 路由是单数 `market-knowledge`)→ admin UI 对 KB "发布公告"/"删除评价"均 404。修复:两个 builder 改从既有 `ADMIN_API[kind]` map 派生。UI 测试发现(Chrome P4/P6 阶段 reqid=130/142 404),修复后 live 复验 PUT/DELETE 均 200 | Minor(admin-only 写路径)/ 既有(ed176e0,M1 T19)| 非 M3+ 残留关闭回归;后端 canonical 路径一直正常 |
| FU-7 | **已修复(2026-09-06)** — `i18n/i18n.js` 的 `loadDict` 用相对路径 `fetch('../i18n/${locale}.json')`:相对**文档 URL** 解析,从 `admin/*.html`(下一层)正确,但从 `index.html`(与 i18n/ 同层)解析到 `/spring/ai/i18n/` → 404 → 字典永不加载 → `I18N.t()` 回显原始 key(市场公告 banner 徽章渲染成 `market.admin.announcement.badge` 而非 "📢 公告")。修复:改用 `document.currentScript.src` 派生脚本自身目录作 BASE。UI 测试发现(Chrome P5/P7),修复后 live 复验 index.html 徽章 = "📢 公告"(zh)/"📢 Announcement"(en)| Minor / 观感 / 既有(27f1d24,M3+ T4.3)| 非残留关闭回归;admin 页 i18n 一直正常。**Round-2(2026-09-06)补全两处遗留**:① 无人调用 `I18N.ready()` → 字典 cache 恒空、早于 ready 的渲染回显原始 key,现 i18n.js 在 parse 时自启 `ready()`;② `t(key, fallback)` 第二参被当作 locale 覆盖导致回显原始 key,现将非受支持 locale 的第二参视为 fallback 文本 |
| FU-8 | **已修复(2026-09-06,round-2)** — 共享市场 UI 组件 CSS(公告 banner / 评价 / 星级 / tag 筛选 / `primary-btn`·`form-input`·`type-badge`·`modal-footer`)只定义在 `admin/console.css`,而 `index.html` 仅加载 `style.css` → 聊天页知识空间模态框的市场 tab **整块裸奔无样式**。修复:把这些块迁入两页共用的基础层 `style.css`(保持相对顺序 → admin 级联不变),`console.css` 只留 admin 专属版式;另新写从未定义的文件树 `tree-*` / `detail-section-content` / `market-reviews-slot` / `review-list-wrap` / `delete-skill-btn` / `mcp-*`。运行时"无样式类扫描"另查出 3 处隐性缺口:`.ks-sidebar-list`(CSS 只写 `.ks-sidebar` 从不匹配 → 侧栏丢宽度/边框/滚动)、`.skill-item.disabled`、`.conv-state-label` | Minor / 观感 / 既有(M2 市场 UI)| 非残留关闭回归。修复后 index.html 无样式类扫描 = 0、admin 级联无回归 |
| FU-9 | **已实现(2026-09-06,round-2)** — 技能模态框市场 tab 补搜索框(与知识空间市场 tab 对称)。后端公开技能 list 走 v1 `listApproved()` twin(FU-4,忽略 `?query=`)、技能无 tag 体系(B4 defer),故采客户端关键词过滤(name/description/author);复用共享 `kb-tag-filter-bar`/`-input` 类,无需新 CSS,re-filter 保持输入焦点 | 特性补齐 / 既有不对称 | 待 FU-4 退役 v1 twin、skill list 改走 v2 `listPaged(filter)` 后,可把客户端过滤升级为服务端 `?query=` |

---

## 11. 文件清单(本次)

- 修改:`V1.0__init.sql` 末尾追加 ALTER TABLE × 3(4.1 + 4.2)
- 修改:`IMarketContentAdminService.java`(参数化 `<K>`)
- 修改:`AbstractMarketAdminService.java`(类型参数同步)
- 修改:`DefaultSkillMarketService.java` + `DefaultKnowledgeMarketService.java`(类型参数补齐,删除 twins)
- 修改:`LoomAgentConfiguration.java`(所有 KB router handler 改用泛型解析)
- 修改:`DefaultKnowledgeTool.java`(`searchKnowledge` 删除 try/catch NFE)
- 修改:`MarketAnnouncementRepository.java` + impl(删除 `findOneByRawId`)
- 修改:`market-admin.js`(删除 `listWithAnnouncements` / `listWithTags`,改用 DTO 嵌入式字段)
- 修改:`CLAUDE.md`(T0 文档增 M0/M1/M2 + tech-debt 章节)
- 新建:`LoomAgentTestUtil.java`(T6 safeRoute 抽出)
