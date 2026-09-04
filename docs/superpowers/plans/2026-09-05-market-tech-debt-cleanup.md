# M3+ Technical Debt Cleanup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 清理 M0/M1/M2 升级留下的 24 项技术债,核心是抹平 `BIGINT / VARCHAR(36)` schema 类型不匹配(B1 真修),并分阶段解决 N+1 / v1 service 残留 / 可观测性等债。

**Architecture:** 单接口 `<K, M, U, R>` 参数化的 `IMarketContentAdminService` 让 skill(Long)和 KB(String)两端 id 类型编译时就清晰;schema 统一 VARCHAR(36) 让所有 UUID KB 真路径可达;后端 DTO embed 收齐 announcement / tags 字段消除前端 N+1。

**Tech Stack:** Spring Boot 3.5 + Spring AI 1.x + JdbcTemplate + H2 2.3 + Flyway + RouterFunctions(backend)。原生 DOM + 模板字符串(前端)。Micrometer + Bucket4j + Spring Boot Actuator(T4)。

**Spec:** `docs/superpowers/specs/2026-09-05-market-tech-debt-cleanup.md`

---

## Global Constraints

1. Java 17+,Maven 多模块,`mvn clean install -Dgpg.skip=true` 全程绿。
2. H2 2.3 方言,Flyway V1.0 末尾追加(老库 baseline 后手动迁移)。
3. 所有 service bean `@ConditionalOnMissingBean`,可整套替换。
4. 路由风格 RouterFunctions,无 `@RestController` 注解。
5. 所有 admin 端点走 `AuthenticationFilter.adminPathPatterns`,无 controller 内部重复 check。
6. Schema 沿用项目 V1.0 单一文件策略;新装清库重跑,老库 baseline 后迁移。
7. commit 习惯:`feat:` / `fix:` / `refactor:` / `docs:` / `test:` 前缀;每 task 一 commit。
8. 本期 YAGNI:Skill tag(保留 M4)/ Prometheus(保留 M5)/ tracing / 跨方言 schema 迁移(postgres)/ 完整 i18n。
9. 测试数据源仍是分离的:`~/.loom/datasource`(主)+ `./spring-ai-loom-agent-test/target/test-ds/`(test 模块),每次 IT 跑前清空。
10. Spec 是 source of truth —— spec 修订由 spec PR 发起,代码 PR 跟随。spec drift(A3/A10)在 T5.2 修。

---

## File Structure(本次变更)

```
spring-ai-loom-agent/src/main/resources/db/migration/
└── V1.0__init.sql                                              # T1.1 末尾追加 ALTER × 5

spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/
├── market/
│   ├── IMarketContentAdminService.java                         # T1.2 类型参数 <K, M, U, R>
│   ├── AbstractMarketAdminService.java                          # T1.2 / T3 同
│   ├── RouterIdParser.java                                     # T1.4 新(接口),Long / String impl
│   └── MarketAnnouncementRepository.java                       # T1.6 删 findOneByRawId
├── skill/
│   └── DefaultSkillMarketService.java                          # T1.3 类型参数补齐 <Long, ...>
├── knowledge/
│   ├── DefaultKnowledgeMarketService.java                      # T1.3 删 String twins
│   └── DefaultKnowledgeTool.java                                # T1.5 删 try/catch NFE
└── (新增 abstract + impl)
    ├── RouterIdParserSkill.java                                # T1.4
    └── RouterIdParserKnowledge.java                             # T1.4

spring-ai-loom-agent-spring-boot-autoconfigure/.../
└── LoomAgentConfiguration.java                                  # T1.4 + T3.2 + T3.1 v1 清理

spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/
├── admin/market-admin.js                                        # T2.2 删 listWithAnnouncements / Tags
├── app.js                                                       # T2.2 改读 DTO 嵌入式
└── admin/console.html / console.js / *.html                     # T4.3 i18n key 抽取

spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/
└── LoomAgentTestUtil.java                                      # T6.1 safeRoute 抽出

CLAUDE.md                                                        # T0.1
```

---

## Phase T0 — 文档 + 小修补

### Task T0.1: CLAUDE.md + CHANGELOG 同步升级

**Files:**
- Modify: `CLAUDE.md`(根目录,项目根)
- Create: `CHANGELOG.md`(根目录,新文件)

**Interfaces:**
- Consumes: 已有的 M0/M1/M2 spec + plan 文档
- Produces: CLAUDE.md 含"M0/M1/M2 市场升级"章节;CHANGELOG.md 顶部含 v1.2.0 entry

- [ ] **Step T0.1.1**: 读 `docs/superpowers/specs/2026-09-04-skill-knowledge-market-design.md` 摘要几节 + `CLAUDE.md` 现状。

- [ ] **Step T0.1.2**: 起草 `CLAUDE.md` 的 "M0/M1/M2 Market Upgrade" 章节(插入到 "Key Commands" 之后或 project overview 之内的合适位置),列出 24 项技术债分类表(A 列 + B 列)。

- [ ] **Step T0.1.3**: 创建 `CHANGELOG.md`,文件结构遵循 [Keep a Changelog 1.1](https://keepachangelog.com/)。本版本条目:

```
## [v1.2.0] - 2026-09-04
### Added
- Market admin: 审批流 (PENDING/APPROVED/REJECTED)
- Market admin: is_official / featured_rank / category columns
- Stat tracking: pull_count (skill) + search_count (KB)
- Review service: 5-star + comment + edit_count gate
- Announcement table + per-row banner
- KB tag system + tag filter

### Known Limitations (remediated in M3+ cleanup)
- See CLAUDE.md "M3+ Technical Debt" section for 24-item catalogue
```

- [ ] **Step T0.1.4**: commit `docs: CLAUDE.md + CHANGELOG for v1.2.0`。

---

### Task T0.2: `BatchedCounterService` javadoc + `@EnableScheduling` 自动启用

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/BatchedCounterService.java`
- Modify: `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java`

**Interfaces:**
- Consumes: 已有 `BatchedCounterService.flush()` / `discard()` 方法
- Produces: 类级别 javadoc 显式说明依赖 `@EnableScheduling`;`LoomAgentConfiguration.StorageConfiguration` 加 `@EnableScheduling`

- [ ] **Step T0.2.1**: 给 `BatchedCounterService` 类加类级 javadoc,写明:
```
默认 30s flush 通过 @Scheduled 触发,**消费者项目必须加 @EnableScheduling**;
否则只剩 @PreDestroy / shutdown hook 触发 flush,可能的丢数据窗口增大。
当前 LoomAgentConfiguration.StorageConfiguration 已自动启用 @EnableScheduling(本 task 加)。
```

- [ ] **Step T0.2.2**: 在 `LoomAgentConfiguration.StorageConfiguration` 加 `@EnableScheduling`(类级别)。如果有冲突,与已有的 `ScheduleConfiguration` 协调(`ScheduleRestoreListener` 已 trigger 了 Scheduling,检查有无 `@EnableScheduling` 已存在)。

- [ ] **Step T0.2.3**: 跑现有 IT:必须仍全绿(`mvn test -pl spring-ai-loom-agent-test -Dsurefire.failIfNoSpecifiedTests=false`)。

- [ ] **Step T0.2.4**: commit `refactor(stats): auto-enable @EnableScheduling + javadoc for BatchedCounterService`。

---

## Phase T1 — B1 真修:SCHEMA + 接口层

> 这是整个技术债修复**最关键**的 phase。接口层重塑 + schema migration 同时落地,删除所有"String-keyed twins / `Long.parseLong` graceful-degradation / `findOneByRawId` / try-catch NFE"临时补救。

### Task T1.1: Schema migration(`updated_at` × 2 + `market_id` × 3)

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/db/migration/V1.0__init.sql` 末尾(`-- ==== M0 market upgrade (spec § 4) ====` 之后追加新段)
- Modify: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/MarketSchemaTest.java`(新增 5 条 column-Exists 与 0 条 tableExists 断言)

- [ ] **Step T1.1.1**: 写失败测试。在 `MarketSchemaTest` 新增 5 个 `@Test`:
```java
@Test void marketSkillHasUpdatedAt() { assertTrue(columnExists("market_skill", "updated_at")); }
@Test void loomUserKnowledgeHasUpdatedAt() { assertTrue(columnExists("loom_user_knowledge", "updated_at")); }
@Test void marketContentAnnouncementMarketIdIsVarchar36() {
    String type = jdbc.queryForObject(
        "SELECT TYPE_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='MARKET_CONTENT_ANNOUNCEMENT' AND COLUMN_NAME='MARKET_ID'",
        String.class);
    assertEquals("VARCHAR", type);
    Integer size = jdbc.queryForObject(
        "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='MARKET_CONTENT_ANNOUNCEMENT' AND COLUMN_NAME='MARKET_ID'",
        Integer.class);
    assertTrue("market_id should be at least 36 chars (UUID)", size >= 36);
}
@Test void loomMarketKnowledgeStatsMarketIdIsVarchar36() { /* 镜像上面 */ }
@Test void loomMarketKnowledgeReviewMarketIdIsVarchar36() { /* 镜像上面 */ }
```

- [ ] **Step T1.1.2**: 跑测试,确认 fail(column not found / type wrong)。

- [ ] **Step T1.1.3**: 修改 `V1.0__init.sql` 末尾(新的 M3 段,注释 `-- ==== M3+ technical debt cleanup (spec § 4.1 + § 4.2) ====`):

```sql
-- 加 updated_at 列(A12)
ALTER TABLE market_skill          ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE loom_user_knowledge   ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- 改 market_id 类型(B1):BIGINT → VARCHAR(36) (UUID 友好)
ALTER TABLE market_content_announcement   MODIFY market_id VARCHAR(36);
ALTER TABLE loom_market_knowledge_stats  MODIFY market_id VARCHAR(36);
ALTER TABLE loom_market_knowledge_review MODIFY market_id VARCHAR(36);
```

- [ ] **Step T1.1.4**: 跑测试,5 个新断言全 PASS(MarketSchemaTest 现 11 个 `@Test`)。

- [ ] **Step T1.1.5**: 跑全 IT 套件,确认既有用例仍绿。

- [ ] **Step T1.1.6**: commit `feat(schema): add updated_at + migrate market_id to VARCHAR(36) (M3+ B1)`。

---

### Task T1.2: `IMarketContentAdminService` 参数化 `<K, M, U, R>` + `extractId` 升 interface

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/IMarketContentAdminService.java`
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/AbstractMarketAdminService.java`
- Modify: 任何使用旧签名的客户端(grep `IMarketContentAdminService` 与 `extends AbstractMarketAdminService`)

**Interfaces:**
- Consumes: 当前 `IMarketContentAdminService<M, U, R>` + `extractId` 在 abstract 是 abstract method
- Produces: 4 类型参数版本 `IMarketContentAdminService<K, M, U, R>`,`K extractId(M entry)` 是 interface 抽象方法

- [ ] **Step T1.2.1**: 修改 `IMarketContentAdminService` 接口,把全部 `Long id` 参数改为 `K id`,加入 `<K>` 类型参数:
```java
public interface IMarketContentAdminService<K, M, U, R> {
    Page<M> listPaged(MarketFilter filter);
    M getById(K id);
    M create(String author, MarketCreateRequest req);
    M update(K id, MarketUpdateRequest req);
    void delete(K id);
    M approve(K id, String reviewer);
    M reject(K id, String reviewer, String comment);
    void setOfficial(K id, boolean isOfficial, String reviewer);
    void setFeaturedRank(K id, int rank, String reviewer);
    void setCategory(K id, String category, String reviewer);
    Page<M> search(String query, String category, int page, int size);
    K extractId(M entry);          // 从 abstract 提到 interface
    MarketContentStatus currentStatus(M entry);
}
```

- [ ] **Step T1.2.2**: 修改 `AbstractMarketAdminService<K, M, U, R> implements IMarketContentAdminService<K, M, U, R>`,删除 abstract `extractId` 声明(已升 interface),所有 `jdbc.update` 调用 SQL 内列名仍硬编 `id`(因为是受保护 abstract `tableName()` 返回的动态表),无需改 SQL,但 **return getById(id); 改为 getById(id)** —— 即用 interface 的 K id 而不再是 Long。注意 type-id 解析在 router 层。

- [ ] **Step T1.2.3**: 任何 `extends AbstractMarketAdminService<X, Y, Z>` 的具体类补 `<K>` 第 4 类型参数;`abstract Long extractId(...)` 现在没有了,删除 override 返回 `Long` 的残留(下个 task 1.3 处理)。

- [ ] **Step T1.2.4**: 跑 `mvn compile -pl spring-ai-loom-agent -Dgpg.skip=true`;预期会大量 fail(T1.3 修)。

- [ ] **Step T1.2.5**: 由于 compile 必然 fail,本 task 不需要 commit —— commit 在 T1.3 一起。

---

### Task T1.3: 修子类 + 删 String twins

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/skill/DefaultSkillMarketService.java`
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/knowledge/DefaultKnowledgeMarketService.java`
- Modify: 老 `ISkillMarketService` interface 留作 shim,如果还能跑 — 不动
- Modify: 任何引用 `getByIdByString` / `approveByString` 的 client(在 T7 实施中清理)

**Interfaces:**
- Consumes: T1.2 的 `<K>` 参数化接口
- Produces: `DefaultSkillMarketService extends AbstractMarketAdminService<Long, MarketSkill, UserSkill, SkillReview>` —— `extractId` override 返 `Long`
- Produces: `DefaultKnowledgeMarketService extends AbstractMarketAdminService<String, MarketKnowledgeRecord, UserKnowledge, KnowledgeReview>` —— `extractId` override 返 `String`;删 `getByIdByString` 等 twins(临时方案)

- [ ] **Step T1.3.1**: 改 `DefaultSkillMarketService extends AbstractMarketAdminService<MarketSkill, Void, Void>` → `<Long, MarketSkill, Void, Void>`(加 1 类型参数在最前)。`@Override protected Long extractId(...)` 已经在某处存在,验证类型正确。

- [ ] **Step T1.3.2**: `DefaultKnowledgeMarketService extends AbstractMarketAdminService<MarketKnowledgeRecord, Void, Void>` → `<String, MarketKnowledgeRecord, Void, Void>`。`@Override protected String extractId(...)` 返回 `entry.id()`(原 UUID 字符串)。

- [ ] **Step T1.3.3**: 删除 KB twins:`getByIdByString` / `approveByString` / `deleteByString` / `updateByString`(全删);现在 `getById(String id)`(继承 IMarketContentAdminService 后是 interface abstract method,直接由 abstract base 提供)。**所有 router handler 必须用 `kbSvc.getById(string)` 直接而不再 wrap**。

- [ ] **Step T1.3.4**: 移除 KB 端 `Long.parseLong` graceful-degradation:`findOne(String marketId)` 等方法中不能再 throw `UnsupportedOperationException` —— 现在 String-id 路径是真路径。

- [ ] **Step T1.3.5**: 跑 `mvn compile -pl spring-ai-loom-agent -Dgpg.skip=true` —— 必须 BUILD SUCCESS。

- [ ] **Step T1.3.6**: 跑 `mvn test -pl spring-ai-loom-agent-test -Dtest='DefaultSkillMarketServiceIT,DefaultKnowledgeMarketServiceIT' -Dsurefire.failIfNoSpecifiedTests=false` —— **预期 FAIL** 因为 router 层 + tool 层还在用旧 `Long.parseLong` 路径;T1.4 修。

- [ ] **Step T1.3.7**: commit `refactor(core): IMarketContentAdminService<K, M, U, R>; drop KB String twins (M3+ B1)`。

---

### Task T1.4: Router id 解析抽象(`RouterIdParser<K>` + 替换所有 `Long.parseLong`)

**Files:**
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/RouterIdParser.java`(interface)
- Create: 同 `.skill.RouterIdParserSkill implements RouterIdParser<Long>`
- Create: 同 `.knowledge.RouterIdParserKnowledge implements RouterIdParser<String>`
- Modify: `spring-ai-loom-agent-spring-boot-autoconfigure/.../LoomAgentConfiguration.java`(所有 KB router handler 替换)

**Interfaces:**
- Consumes: T1.3 后所有 `kbSvc.<method>(K id)` 接受 String id
- Produces: `RouterIdParser<K>` interface + 2 impls;routers 改 `<K> getById(parser.parse(pathVariable("id")))`

- [ ] **Step T1.4.1**: 创建 `RouterIdParser.java`:
```java
public interface RouterIdParser<K> {
    K parse(String rawId);
    Class<K> idType();
}
```

- [ ] **Step T1.4.2**: Skill / Knowledge impls:
```java
@Component public class RouterIdParserSkill implements RouterIdParser<Long> {
    public Long parse(String s) { return Long.parseLong(s); }
    public Class<Long> idType() { return Long.class; }
}
@Component public class RouterIdParserKnowledge implements RouterIdParser<String> {
    public String parse(String s) { return s; }                 // raw UUID
    public Class<String> idType() { return String.class; }
}
```

- [ ] **Step T1.4.3**: 在 `LoomAgentConfiguration` 找到所有 KB router handler(`loomAgentMarketKnowledgeAdminRouter`, `loomAgentMarketKnowledgePublicRouter`, `loomAgentKnowledgeMarketAdminRouter`),替换 `Long.parseLong(req.pathVariable("id"))` 为 `req.pathVariable("id")` 然后传给 `kbSvc.<method>()`。**Critical**:不能 Long.parseLong,因为现在 `kbSvc.getById(String)`。

- [ ] **Step T1.4.4**: 同一 router 里把 `Long.parseLong` 用于 `getById`,`setOfficial`, `setFeaturedRank`, `setCategory` 等 path-variable id 的位置全部清理。

- [ ] **Step T1.4.5**: `announcementRepo.upsert("KNOWLEDGE", K marketId, ...)` —— 现在 K=String,直接传 string 即可。

- [ ] **Step T1.4.6**: 跑现有 IT —— 必须全绿:`DefaultSkillMarketServiceIT`、`DefaultKnowledgeMarketServiceIT`、`MarketAcceptanceIT`、`SkillAdminMissingIdReturns404IT`、`ReplaceTagsRollbackOnFailureIT`。

- [ ] **Step T1.4.7**: commit `refactor(router): use RouterIdParser; KB endpoints accept real UUID strings (M3+ B1)`。

---

### Task T1.5: `DefaultKnowledgeTool.searchKnowledge` 删 NFE catch

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/knowledge/DefaultKnowledgeTool.java`

- [ ] **Step T1.5.1**: 找 `searchKnowledge` 内 try/catch `NumberFormatException` 路径,删除 —— 现在 KB id 是 String(UUID),`kbStatsService.incrementStat` 直接入参。

- [ ] **Step T1.5.2**: 让 `searchKnowledge` 直接 String marketId 入参到 stats service。这是它的主要 consumer。

- [ ] **Step T1.5.3**: 跑 `mvn test -pl spring-ai-loom-agent-test -Dsurefire.failIfNoSpecifiedTests=false` —— 全绿。

- [ ] **Step T1.5.4**: commit `refactor(knowledge): drop NFE catch in searchKnowledge (M3+ B1)`。

---

### Task T1.6: `MarketAnnouncementRepository` 删 `findOneByRawId`

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/MarketAnnouncementRepository.java`
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/DefaultMarketAnnouncementRepository.java`

- [ ] **Step T1.6.1**: 把 `MarketAnnouncementRepository.findOneByRawId(marketKind, rawMarketId)` 删除。`findOne(marketKind, marketId)` 现直接接受 String(UUID 或 numeric)而非要求 Long。

- [ ] **Step T1.6.2**: `DefaultMarketAnnouncementRepository.findOne(...)` 内删除 try/catch `NumberFormatException` 路径,直接 `WHERE market_id = ?` with String param。

- [ ] **Step T1.6.3**: 跑现有 IT —— 全绿。

- [ ] **Step T1.6.4**: commit `refactor(repo): drop findOneByRawId (M3+ B1 cleanup)`。

---

### Task T1.7: Acceptance test 迁移到真 UUID KB id

**Files:**
- Modify: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/MarketAcceptanceIT.java`
- Modify: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/DefaultKnowledgeMarketServiceIT.java`
- Modify: 任何用 `market_id = "K-100"` 这类 numeric test id 的 IT 测试

**Interfaces:**
- Consumes: T1.1 的 schema + T1.3 的 KB service
- Produces: A12 / A13 / KB review 测试用真 UUID 跑(不再是 numeric test data)

- [ ] **Step T1.7.1**: 找 MarketAcceptanceIT 里 KB 子段(A12/A13/UUID subset),把 numeric id 替换为 `String.valueOf(UUID.randomUUID())`(真 UUID 格式)。同步 `admin.setUniqueKBId()` helper(可在 `@BeforeEach` 准备 + 复用)。

- [ ] **Step T1.7.2**: 跑 `MarketAcceptanceIT` —— 期望 A1-A15 全绿,KB 测试现在真正验证 UUID 路径,不再是 numeric mock。

- [ ] **Step T1.7.3**: 跑全 IT —— 全绿。

- [ ] **Step T1.7.4**: commit `test(acceptance): KB tests now use real UUIDs (M3+ B1)`。

---

## Phase T2 — N+1 修

### Task T2.1: 后端 list DTO embed announcement + tags

**Files:**
- Modify: `AbstractMarketAdminService.listPaged` SQL
- Modify: `MarketSkill` record + `MarketKnowledgeRecord` record(加 `announcementTitle / announcementBody / tags` 字段)
- Modify: `MarketSkill.from(ResultSet)` / `MarketKnowledgeRecord.from(ResultSet)`(读 4 列,本次又加 3 字段)
- Modify: SQL 拼装:`listPaged` 的 SELECT 加 `LEFT JOIN (SELECT market_id, title AS announcementTitle, body AS announcementBody FROM market_content_announcement WHERE market_kind = ?) ON ...` 与 `LEFT JOIN (SELECT market_id, LISTAGG(tag, ',') ... FROM loom_market_knowledge_tag GROUP BY market_id)` KB 端(KB 真实生效)
- Modify: Skill 端也加 tag(本期扩展 `market_skill_tag`?)—— **不**,先 **只 KB**,Decision ADR-T06:Skill tag M4。`market_skill` 的 tags 字段为 null / empty。

**Interfaces:**
- Consumes: `MarketSkill` / `MarketKnowledgeRecord` 现有字段
- Produces: 3 新字段 embed 在 list response

- [ ] **Step T2.1.1**: 写失败测试:`MarketAcceptanceIT` 加 A-list-embeds-announcement:
```java
@Test void listSkillsEmbedsAnnouncement() {
    // admin 创建带 announcement 的 skill
    long sId = svc.create("alice", new MarketCreateRequest("s-test", "d", "c", null)).id();
    announcementRepo.upsert("SKILL", sId, "title", "body");
    svc.setOfficial(sId, true, "admin");
    
    List<MarketSkill> list = ... // call listPaged
    MarketSkill row = list.stream().filter(r -> r.id() == sId).findFirst().get();
    assertEquals("title", row.announcementTitle());
    assertEquals("body", row.announcementBody());
}
```

- [ ] **Step T2.1.2**: 加 `announcementTitle` / `announcementBody` / `tags` 到 `MarketSkill` 与 `MarketKnowledgeRecord`。`tags` 是 `List<String>`;若空则为 `List.of()`。

- [ ] **Step T2.1.3**: 修改 `AbstractMarketAdminService.listPaged`:`SELECT ... LEFT JOIN (ann subquery) ON m.<id> = ann.market_id WHERE market_kind = ?` —— 注意:H2 不支持 LEFT JOIN `VALUES` 子查询,LISTAGG 也可能在 H2 有兼容问题,**做最简方案**:在 Java 端 N+1 batch 改 1+1 次(拉 list,然后再拉 1 次所有 announcement id + title + body),内存 join。即:**先用 sublist 一次查所有 announcement** 而不是 SQL join。

- [ ] **Step T2.1.4**: SKILL 端在 `listPaged(...)` 末尾加一步:`if (kind == "SKILL") { List<MarketContentAnnouncement> all = announcementRepo.listAllForKind("SKILL", 0, 100000); Map<Long, ...> by id; enrich }`。KB 端镜像。

- [ ] **Step T2.1.5**: `tags` 字段对 KB 端也用同样批量模式:`KnowledgeTagService.findAllForList` 一次性返回所有 market_id → tags 映射(避免 N+1),或保留 N+1,等真有性能问题再优化(B2 scope 限定 N+1 消除是更小改动的 batch-fetch)。

- [ ] **Step T2.1.6**: 跑 MarketAcceptanceIT + 全 IT —— 必须全绿。

- [ ] **Step T2.1.7**: commit `feat(market): list DTO embeds announcement + tags (M3+ B2)`。

---

### Task T2.2: 前端删除 `listWithAnnouncements` / `listWithTags`

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/market-admin.js`
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js`

- [ ] **Step T2.2.1**: 删除 `MarketAdmin.listWithAnnouncements` 与 `MarketAdmin.listWithTags`(in-place mutate helpers)。`MarketAdmin.list(kind)` 返回的 row 现在自带 `announcementTitle / announcementBody / tags` 字段。

- [ ] **Step T2.2.2**: `_renderMarketTab` Skills + KB 两处 renderer:把 `await MarketAdmin.listWithAnnouncements(kind, items)` 调用整段移除,直接用 list row 的 `announcementTitle` / `announcementBody` / `tags` 渲染。

- [ ] **Step T2.2.3**: 移除每行 per-row parallel fetch 的 banner 渲染逻辑(已嵌入)。

- [ ] **Step T2.2.4**: 手动 mental trace:打开市场 Tab → 看到 50 行,每行自带动 announcement / tags 信息。无 parallel GET。

- [ ] **Step T2.2.5**: 跑 `node --check` 通过。

- [ ] **Step T2.2.6**: commit `refactor(frontend): drop listWithAnnouncements; read DTO-embedded fields (M3+ B2 + B5)`。

---

## Phase T3 — 架构清理

### Task T3.1: v1 service 保留 shim + 测试隔离

**Files:**
- Modify: 任何引用老 `ISkillMarketService` / `IKnowledgeMarketService` 的代码(Routers 可能不再用,但 interface 不能立即删)
- Create: `docs/superpowers/specs/2026-09-04-...-design.md` addendum(说明 v1 退役时间表)

- [ ] **Step T3.1.1**: grep 老 `ISkillMarketService.submit / withdraw / pull` 引用位置,确认前端使用 router 仍是 v1 的端点。

- [ ] **Step T3.1.2**: 若 v1 routers 仍有用户引用,**保留**;但加 `@Deprecated` 注释指向 v2 等价端点。

- [ ] **Step T3.1.3**: 在 spec.md 加 ADR(ADR-T03):v1 service shim 保留 1 小版本(v1.3.0),1.x.z 后视 grep 引用计数清除。

- [ ] **Step T3.1.4**: commit `docs(spec): v1 service shim policy + ADR-T03`。

---

### Task T3.2: T8.7 refactor — 抽出 `marketAdminRoutes(prefix, svc, ops, idParser)`

**Files:**
- Modify: `spring-ai-loom-agent-spring-boot-autoconfigure/.../LoomAgentConfiguration.java`

- [ ] **Step T3.2.1**: 抽 `private static <K, M, U, R> RouterFunction<ServerResponse> buildAdminMarketRoutes(...)` 静态方法,take `prefix`、`IAdminMarketOps<K, M>`(新接口)、`RouterIdParser<K>`、`Long-reviewer resolver`。

- [ ] **Step T3.2.2**: Skill bean `loomAgentMarketSkillAdminRouter` 调用 build 一次,KB bean `loomAgentMarketKnowledgeAdminRouter` 调一次。

- [ ] **Step T3.2.3**: 路径冲突(`GET/DELETE /admin/market-knowledge`)在两个 bean 都注册 → 改成只在 v2 bean 注册,v1 bean 删除这 2 个 handler(避免 v1/v2 conflict)。

- [ ] **Step T3.2.4**: 跑所有 router IT —— 全绿。

- [ ] **Step T3.2.5**: commit `refactor(router): T8.7 buildAdminMarketRoutes helper (M3+ architecture cleanup)`。

---

## Phase T4 — 可观测 / 安全

### Task T4.1: Micrometer metrics for new endpoints

**Files:**
- Modify: `spring-ai-loom-agent-spring-boot-autoconfigure/pom.xml` 加 `spring-boot-starter-actuator`
- Modify: `LoomAgentConfiguration.InfrastructureConfiguration` 加 `@EnableConfigurationProperties` actuator
- Create: `cn/wubo/spring/ai/loom/agent/market/MarketMetrics.java`(counter + timer)

- [ ] **Step T4.1.1**: 加 maven 依赖。

- [ ] **Step T4.1.2**: 在 admin + public market routers 关键 endpoint handler 包一层 `MarketMetrics.<endpoint>.increment()` + `Timer` 记录;每个 endpoint 标签 kind=SKILL/KNOWLEDGE。

- [ ] **Step T4.1.3**: 跑 `mvn spring-boot:run -pl spring-ai-loom-agent-test` 启动后访问 `/actuator/metrics/loom.market.list.skills` 等,确认指标注册成功。

- [ ] **Step T4.1.4**: commit `feat(metrics): Micrometer counters+timers for market endpoints (M3+ T4)`。

---

### Task T4.2: rate limit on `/pull` / `/access` / `/reviews`

**Files:**
- Create: `cn/wubo/spring/ai/loom/agent/market/RateLimitFilter.java`(in-memory token bucket)
- Modify: `LoomAgentConfiguration.WebConfiguration` 注册 filter

- [ ] **Step T4.2.1**: 加 `bucket4j-core` 依赖。

- [ ] **Step T4.2.2**: 写 `RateLimitFilter` —— 给定 endpoint pattern + per-IP token bucket,5 req/sec 默认。

- [ ] **Step T4.2.3**: 注册 filter 只对 `POST /spring/ai/loom/market-{skill,knowledge}/{id}/{pull,access}` 与 `POST .../reviews` 生效。

- [ ] **Step T4.2.4**: 跑前端手动测试连发 6 个 pull,第 6 个返 429。

- [ ] **Step T4.2.5**: commit `feat(ratelimit): token bucket on mutating market endpoints (M3+ T4)`。

---

### Task T4.3: i18n 抽 hardcoded UI 字符串

**Files:**
- Create: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/i18n/zh-CN.json`、`en-US.json`(key-value 字典)
- Modify: `market-admin.js` / `app.js` / `market-skills.html` / `knowledge-market.html` —— 所有硬编码中文改为 `i18n('key')` helper

- [ ] **Step T4.3.1**: 找出所有硬编码中文字符串(grep `await-greep` 列表):"待审核"、"🏛️ 官方"、"拒绝原因"、"评分与评论"、"应用"、"复制"、"下载"……

- [ ] **Step T4.3.2**: 建 `i18n()` helper:按用户语言(`navigator.language`)返回对应字符串。

- [ ] **Step T4.3.3**: 在前端各调用点替换。

- [ ] **Step T4.3.4**: 手动测 —— 切换浏览器语言(en-US / zh-CN)看 banner 文本切换。

- [ ] **Step T4.3.5**: commit `feat(frontend): extract i18n keys for M0-M2 UI strings (M3+ T4)`。

---

## Phase T5 — 可移植 / spec drift

### Task T5.1: Flyway V1.0 拆 V1.0/V1.1/V1.2(可回退)

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/db/migration/V1.0__init.sql`
- Create: `V1.1__init_app_data.sql`(已经存在的 init data 移过来)
- Create: `V1.2__m0_market_upgrade.sql`(M0 spec § 4 的 5 ALTER + 5 新表)
- Create: `V1.3__t1_1_updated_at_and_varchar.sql`(T1.1 的 2 ADD + 3 MODIFY)

- [ ] **Step T5.1.1**: 把 `V1.0__init.sql` 当前末尾的 `-- ==== M0 market upgrade (spec § 4) ====` 大段移出,独立 `V1.2__m0_market_upgrade.sql`(注:`V1.1__init_app_data.sql` 已存在,不动)。再把 T1.1 的 ALTER 段做成 `V1.3`。

- [ ] **Step T5.1.2**: `V1.0` 只剩原始 schema。`V1.1` data。`V1.2` M0 schema。`V1.3` T1 schema。

- [ ] **Step T5.1.3**: 跑全 IT —— 全绿。

- [ ] **Step T5.1.4**: commit `refactor(schema): split V1.0 into V1.0 / V1.2 / V1.3 for revertability (M3+ T5)`。

---

### Task T5.2: spec drift fix(A3 / A10:422 → 403)

**Files:**
- Modify: `docs/superpowers/specs/2026-09-04-skill-knowledge-market-design.md` § 12 A3 与 A10
- Modify: `docs/superpowers/specs/2026-09-05-market-tech-debt-cleanup.md` ADR-T05 记录此变更

- [ ] **Step T5.2.1**: spec § 12 A3 改为:"`POST /admin/market-skills/{id}/reject` body `{comment}`,若 comment 缺失 → 返 `403 + 'reject needs comment'`"。A10 同步。

- [ ] **Step T5.2.2**: ADR-T05 注释说明:silent spec drift during M0 was identified at T22 final review;this spec now reflects actual runtime behavior。

- [ ] **Step T5.2.3**: commit `docs(spec): A3 + A10 status codes aligned to runtime (M3+ T5 ADR-T05)`。

---

### Task T5.3: H2 MERGE → 跨方言 upsert

**Files:**
- Modify: `KnowledgeTagService.addTags`(用 INSERT ... ON CONFLICT 或两步代替 MERGE INTO)
- Modify: `AbstractMarketReviewService.submit`(同)
- Modify: `DefaultMarketAnnouncementRepository.upsert`(同)

- [ ] **Step T5.3.1**: 重写 `addTags`:先 `SELECT 1 FROM ... WHERE (market_id, tag) = (?, ?)`;若 0 行 → `INSERT`;若 1 行 → `UPDATE` 跳过(或仅 `MERGE`)。两步 vs MERGE 取决于 H2 行为测试。

- [ ] **Step T5.3.2**: `submit` review 同。

- [ ] **Step T5.3.3**: `upsert` announcement 同。

- [ ] **Step T5.3.4**: 跑全 IT —— 全绿。

- [ ] **Step T5.3.5**: commit `refactor(sql): portable upsert across H2 + PostgreSQL dialects (M3+ T5 ADR-T05 partial)`。

---

## Phase T6 — 测试 cleanup

### Task T6.1: 提取 `LoomAgentTestUtil.safeRoute` 公共 helper

**Files:**
- Create: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/test/LoomAgentTestUtil.java`

- [ ] **Step T6.1.1**: 把 `MarketAcceptanceIT.safeRoute` helper 抽到 `LoomAgentTestUtil` 公共 utility。

- [ ] **Step T6.1.2**: 任何用了 `router.route(...)` + `.exchange(...)` 的 IT 用 `LoomAgentTestUtil.exchange(...)` 简化。

- [ ] **Step T6.1.3**: commit `refactor(test): extract LoomAgentTestUtil.safeRoute helper (M3+ T6)`。

---

### Task T6.2: MarketAcceptanceIT 覆盖异步 batched flush 路径

**Files:**
- Modify: `spring-ai-loom-agent-test/.../market/MarketAcceptanceIT.java`

- [ ] **Step T6.2.1**: A11 / A12 现有实现是手动 `batchedCounterService.flush()` 调;新增 `A11_async` test 通过 `@SpringBootTest` 的真实 `@Scheduled` 路径触发 `pull` 一次,等待 32s,然后断言 `pull_count > 0`。

- [ ] **Step T6.2.2**: 跑全 IT(包括 A11_async)—— 全绿。注意 32s 等待让单测慢,标记 `@Tag("slow")`,默认 test profile 跳过。

- [ ] **Step T6.2.3**: commit `test(acceptance): cover async batched flush path (M3+ T6)`。

---

## Verification — 端到端 + 整套回归

### Task T7.1: 全 IT 回归 + skill dropdown 真实 UUID 测试

**Files:**
- Modify: `docs/superpowers/specs/2026-09-05-market-tech-debt-cleanup.md` § 12 "Acceptance Criteria" 加 5 项 acceptance:

| ID | 验收用例 |
|---|---|
| AT1 | 真 UUID KB 创建 + admin 审核 → market_knowledge 真行 |
| AT2 | 真 UUID KB announcement → 200 + body 实际渲染 |
| AT3 | 真 UUID KB review → 201 + 真 review 行 |
| AT4 | 真 UUID KB stats → 200 + search_count 真累计 |
| AT5 | 真 UUID KB tag 添加 + 列表查 → 真 tag 行 |

- [ ] **Step T7.1.1**: 在 `MarketAcceptanceIT` 加 AT1-AT5 测试。

- [ ] **Step T7.1.2**: 跑全 suite:`mvn clean test -pl spring-ai-loom-agent-test -Dsurefire.failIfNoSpecifiedTests=false -Dgpg.skip=true`。

- [ ] **Step T7.1.3**: 写 `docs/superpowers/specs/2026-09-05-market-tech-debt-cleanup.md` § "Verification" 节记录:suite passed, AT1-AT5 passed, A1-A15 still passed。

- [ ] **Step T7.1.4**: commit `test(cleanup-verification): AT1-AT5 + full regression after M3+ cleanup`。

---

## Out of Scope (YAGNI / M4+)

- Skill tag 表(ADR-T06,M4 候选)
- 完整 i18n 多语言框架(本期只抽 key,不做 next-intl 之类的全栈框架)
- 分布式 rate limit(Redis-backed bucket4j,需要外部基础设施)
- OTel tracing / 完整 Prometheus exporter
- 自动 spec drift CI 检测(spec 文件在 PR 时手动复核)
- v1 service 完全删除(观察期后再判定)

---

## Risk & Rollback

| 风险 | 触发 | 缓解 |
|---|---|---|
| B1 schema migration 在已有 env 失败 | ALTER 已有行类型不兼容 | 项目策略:全新库 baseline 后手动迁移;老库保留 numeric mock 行 fallback |
| B1 接口重命名破坏下游消费者 | 唯一已知:`spring-ai-loom-agent-test`(就是本 plan),T1.6 覆盖 | commit 链稳定后再开分支 |
| 删除 String twins 后某个旧 KB router 漏改 | router 层有 N 处 ids | T1.4 grep `Long.parseLong` + 全 IT 跑通 |
| T5.1 多文件 Flyway 拆影响老库 | 老的 `flyway_schema_history` 看 V1.0 + V1.1 已有,V1.2 / V1.3 新加 | 老 baseline 路径需要 v1.2 / v1.3 也标 baseline |
| T4 Bucket4j state in-memory 重启丢 | 本地 bucket 状态丢 | in-memory 已声明;M5+ 走 Redis(M4 候选) |

---

## Quick Task Index

| Task | Phase | One-liner |
|---|---|---|
| T0.1 | T0 | CLAUDE.md + CHANGELOG 同步 |
| T0.2 | T0 | @EnableScheduling 自动启用 + BatchedCounterService javadoc |
| T1.1 | T1 | schema migration(updated_at × 2 + market_id × 3) |
| T1.2 | T1 | IMarketContentAdminService<K, M, U, R> 参数化 |
| T1.3 | T1 | 修子类,删 String twins |
| T1.4 | T1 | RouterIdParser + 替换 Long.parseLong |
| T1.5 | T1 | DefaultKnowledgeTool 删 NFE catch |
| T1.6 | T1 | MarketAnnouncementRepository 删 findOneByRawId |
| T1.7 | T1 | Acceptance test 迁真 UUID |
| T2.1 | T2 | 后端 list DTO embed announcement + tags |
| T2.2 | T2 | 前端删 listWithAnnouncements / listWithTags |
| T3.1 | T3 | v1 service shim policy + ADR |
| T3.2 | T3 | T8.7 buildAdminMarketRoutes helper |
| T4.1 | T4 | Micrometer metrics |
| T4.2 | T4 | rate limit on mutating endpoints |
| T4.3 | T4 | i18n 抽 hardcoded UI strings |
| T5.1 | T5 | Flyway 拆 V1.0 / V1.2 / V1.3 |
| T5.2 | T5 | spec drift A3/A10 422 → 403 |
| T5.3 | T5 | 跨方言 upsert |
| T6.1 | T6 | LoomAgentTestUtil.safeRoute |
| T6.2 | T6 | async batched flush 测试 |
| T7.1 | Verification | AT1-AT5 + 整套回归 |

Total: 22 tasks(17 implementation + 5 verification-adjacent), 估算 8-12 周。
