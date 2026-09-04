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
- `LoomAgentConfiguration` 内被标记 `// DEFER to T8.7` 的 v1 router bean 路径冲突 —— T3 修后 v2 router 已经完全覆盖,T8.7 不再需要。
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

T3 refactor 时抽出来(T8.7 parked 项目顺道做)。

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
