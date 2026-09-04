# Skill + KB 市场升级 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 skill 与知识库市场升级为可运营内容池(author 投稿 → admin 审批 → 评分 / 评论 / 统计 / 公告),并保持两层架构完全镜像。

**Architecture:** 单 `market_*` 表 + 4 个公共列(`is_official / featured_rank / category / created_by_kind`)。Java 端 3 套泛型接口(`IMarketContentAdmin/Stats/Review`)+ 共享 abstract base + 2 套实现(SKILL/KB)。前端共享 `market-admin.js` component,skill / KB 各一个 admin HTML。

**Tech Stack:** Spring Boot 3.5 + Spring AI 1.x + JdbcTemplate + H2 + Flyway + RouterFunctions(backend)。原生 DOM + 局部组件风格 JS(无 Vue / React,沿用项目)。H2 SQL(dialect H2 2.3)。

**Spec:** `docs/superpowers/specs/2026-09-04-skill-knowledge-market-design.md`

---

## Global Constraints

1. **Java 17+**,Maven 多模块,`mvn clean install -Dgpg.skip=true` 全程绿
2. **Spring AI 1.x** + **Spring Boot 3.5.x**,`@ToolGroup(defaultGranted=true)` 用于 universal 工具
3. **数据库方言 H2 2.3**(项目默认 H2 文件库),Flyway `V1.0__init.sql` 末尾追加,只接受全新库
4. **所有 service bean 必须 `@ConditionalOnMissingBean`**,允许用户整套替换
5. **LLM 工具签名不变**;`ISkillTool` / `IKnowledgeTool` 不动,只能 service 层注入
6. **HTTP RouterFunctions 风格**,无 `@RestController` 注解(REST 端点除 SSE 外)
7. **所有 admin 端点必须校验 ADMIN role**(复用 `AuthenticationFilter.adminPathPatterns`)
8. **schema 沿用项目策略**:V1.0 单一文件;老库需 baseline + 重置
9. **代码命名沿用项目惯例**:`DefaultXxx` 实现 + `IXxx` 接口;路由表名 = 数据库物理名
10. **前端无新框架**;JS 用 DOM + 模板字符串;依赖库用现有 marked.js 路线
11. **commit 习惯**:每完成 1 个 step 的"impl + test pass"立即 commit(`feat:`,`test:`,`refactor:`,`docs:` 前缀)
12. **本期 YAGNI**:不引 outbox / MQ;不引乐观锁;不改 LLM 工具签名

---

## File Structure

### 新增 / 修改的 backend Java 文件

```
spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/
├── market/                                            ← 新包
│   ├── MarketContentColumns.java                      T2 字段常量(本计划任务编号)
│   ├── MarketContentStatus.java                       T2 enum
│   ├── MarketFilter.java                              T2 分页查询参数
│   ├── MarketCreateRequest.java                       T2 创建请求
│   ├── MarketUpdateRequest.java                       T2 更新请求
│   ├── ReviewSubmitRequest.java                       T16 评论请求
│   ├── ReviewUpdateRequest.java                       T16 评论更新
│   ├── ReviewRow.java                                 T16 评论 row
│   ├── RatingAggregate.java                          T16 聚合
│   ├── StatsRow.java                                  T15 统计 row
│   ├── MarketAnnouncement.java                        T19 公告 row
│   ├── IMarketContentAdminService.java                T3 抽象接口
│   ├── IMarketContentStatsService.java                T15 抽象接口
│   ├── IMarketContentReviewService.java               T16 抽象接口
│   ├── AbstractMarketAdminService.java                T4 公共实现
│   ├── AbstractMarketStatsService.java                T15 公共实现
│   ├── AbstractMarketReviewService.java               T16 公共实现
│   └── BatchedCounterService.java                     T14 通用批写 helper
├── skill/market/                                      ← 新子包(skill 端)
│   ├── DefaultSkillMarketService.java                 T5(改造现有 DefaultSkillMarketService)
│   ├── DefaultSkillStatsService.java                  T15
│   └── DefaultSkillReviewService.java                 T16
├── knowledge/market/                                  ← 新子包(KB 端)
│   ├── DefaultKnowledgeMarketService.java            T6
│   ├── DefaultKnowledgeStatsService.java             T15
│   └── DefaultKnowledgeReviewService.java            T16
└── (现有 skills / knowledge 目录保持)
```

### 新增 / 修改的 frontend 文件

```
spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/
├── app.js                                             T13 修改(market tab 排序 + 徽章)
├── admin/
│   ├── market-admin.js                                T11 共享 component
│   ├── market-skills.html                             T12 新(从 skills-market.html 改造)
│   ├── market-skills.js                               T12 新(从 skills-market.js 改造)
│   ├── market-knowledge.html                          T12 新
│   ├── market-knowledge.js                            T12 新
│   └── console.html                                   T13 加待审核角标
```

### 新增 / 修改的 test 文件

```
spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/
├── MarketSchemaTest.java                              T1 schema validation
├── AbstractMarketAdminServiceTest.java                T4 mock JDBC 测 CRUD / 审批
├── DefaultSkillMarketServiceIT.java                   T5 skill 集成
├── DefaultKnowledgeMarketServiceIT.java               T6 KB 集成
├── BatchedCounterServiceTest.java                     T14 内存 flush 测试
├── DefaultSkillStatsServiceIT.java                    T15 skill stats
├── DefaultSkillReviewServiceIT.java                   T16 skill 评论 + edit_count
├── DefaultKnowledgeReviewServiceIT.java               T16 KB 评论 + 严门槛
└── MarketAcceptanceIT.java                            T21 端到端验收 A1-A15
```

### 数据库

```
spring-ai-loom-agent/src/main/resources/db/migration/
└── V1.0__init.sql                                     T1 末尾追加
```

### Config

```
spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/
└── LoomAgentConfiguration.java                         T7-T10, T17 router 段扩展
```

---

## Phase M0 — Core CRUD + Approval Flow

### Task 1: Schema migration (V1.0 末尾追加)

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/db/migration/V1.0__init.sql` (在文件最末尾追加新段;用 `-- ==== M0 market upgrade ====` 注释分隔)
- Create: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/MarketSchemaTest.java`

**Interfaces:**
- Consumes: 已有的 `market_skill` / `loom_market_knowledge` / `user_*` / `role_*` / `*_chat_memory` 表
- Produces: 同名表 + 新公共列 + 3 个新附表

- [ ] **Step 1.1: 写失败测试 — schema 校验**

```java
package cn.wubo.spring.ai.loom.agent.market;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MarketSchemaTest {

    @Autowired JdbcTemplate jdbc;

    private boolean columnExists(String table, String column) {
        Integer cnt = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE UPPER(TABLE_NAME)=UPPER(?) AND UPPER(COLUMN_NAME)=UPPER(?)",
            Integer.class, table, column);
        return cnt != null && cnt > 0;
    }

    @Test
    void marketSkillHasNewColumns() {
        assertTrue(columnExists("market_skill", "is_official"));
        assertTrue(columnExists("market_skill", "featured_rank"));
        assertTrue(columnExists("market_skill", "category"));
        assertTrue(columnExists("market_skill", "created_by_kind"));
    }

    @Test
    void marketKnowledgeHasNewColumns() {
        assertTrue(columnExists("loom_market_knowledge", "is_official"));
        assertTrue(columnExists("loom_market_knowledge", "featured_rank"));
        assertTrue(columnExists("loom_market_knowledge", "category"));
        assertTrue(columnExists("loom_market_knowledge", "created_by_kind"));
    }

    @Test
    void newTablesExist() {
        Integer s = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME)='MARKET_SKILL_STATS'",
            Integer.class);
        Integer r = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME)='MARKET_SKILL_REVIEW'",
            Integer.class);
        Integer kbs = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME)='LOOM_MARKET_KNOWLEDGE_STATS'",
            Integer.class);
        Integer kbr = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME)='LOOM_MARKET_KNOWLEDGE_REVIEW'",
            Integer.class);
        Integer ann = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME)='MARKET_CONTENT_ANNOUNCEMENT'",
            Integer.class);
        assertEquals(1, s);   assertEquals(1, r);
        assertEquals(1, kbs); assertEquals(1, kbr);
        assertEquals(1, ann);
    }

    @Test
    void userKnowledgeAccessCheckIndexExists() {
        Integer idx = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE UPPER(TABLE_NAME)='LOOM_USER_KNOWLEDGE' AND UPPER(INDEX_NAME)='IDX_USER_KNOWLEDGE_ACCESS_CHECK'",
            Integer.class);
        assertEquals(1, idx);
    }
}
```

- [ ] **Step 1.2: 运行测试,确认失败**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest=MarketSchemaTest
```

Expected:`mktSkillHasNewColumns` 等失败(`column not found`)。

- [ ] **Step 1.3: 把 spec § 4 全部 SQL 追加到 `V1.0__init.sql` 末尾**

打开文件,在最末尾追加如下段(以 `-- ==== M0 market upgrade ====` 开头):

```sql
-- ==== M0 market upgrade (spec § 4) ====

-- 公共列(skill + knowledge 两表都加)
ALTER TABLE market_skill          ADD COLUMN is_official     BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE loom_market_knowledge ADD COLUMN is_official     BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE market_skill          ADD COLUMN featured_rank   INT NOT NULL DEFAULT 0;
ALTER TABLE loom_market_knowledge ADD COLUMN featured_rank   INT NOT NULL DEFAULT 0;

ALTER TABLE market_skill          ADD COLUMN category        VARCHAR(64);
ALTER TABLE loom_market_knowledge ADD COLUMN category        VARCHAR(64);

ALTER TABLE market_skill          ADD COLUMN created_by_kind VARCHAR(16) NOT NULL DEFAULT 'USER';
ALTER TABLE loom_market_knowledge ADD COLUMN created_by_kind VARCHAR(16) NOT NULL DEFAULT 'USER';

-- 附表
CREATE TABLE market_skill_stats (
  market_skill_id BIGINT PRIMARY KEY,
  pull_count BIGINT NOT NULL DEFAULT 0,
  last_pulled_at TIMESTAMP,
  FOREIGN KEY (market_skill_id) REFERENCES market_skill(id) ON DELETE CASCADE
);

CREATE TABLE market_skill_review (
  market_skill_id BIGINT NOT NULL,
  username VARCHAR(64) NOT NULL,
  rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
  comment TEXT,
  edit_count SMALLINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (market_skill_id, username),
  FOREIGN KEY (market_skill_id) REFERENCES market_skill(id) ON DELETE CASCADE
);
CREATE INDEX idx_market_skill_review ON market_skill_review(username);

CREATE TABLE loom_market_knowledge_stats (
  market_id BIGINT PRIMARY KEY,
  search_count BIGINT NOT NULL DEFAULT 0,
  last_searched_at TIMESTAMP,
  FOREIGN KEY (market_id) REFERENCES loom_market_knowledge(id) ON DELETE CASCADE
);

CREATE TABLE loom_market_knowledge_review (
  market_id BIGINT NOT NULL,
  username VARCHAR(64) NOT NULL,
  rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
  comment TEXT,
  edit_count SMALLINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (market_id, username),
  FOREIGN KEY (market_id) REFERENCES loom_market_knowledge(id) ON DELETE CASCADE
);
CREATE INDEX idx_market_kb_review ON loom_market_knowledge_review(username);

CREATE TABLE market_content_announcement (
  market_kind VARCHAR(16) NOT NULL,
  market_id BIGINT NOT NULL,
  title VARCHAR(128) NOT NULL,
  body TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (market_kind, market_id)
);

-- 索引建议(性能)
CREATE INDEX idx_market_skill_official_rank   ON market_skill(is_official DESC, featured_rank DESC);
CREATE INDEX idx_market_kb_official_rank      ON loom_market_knowledge(is_official DESC, featured_rank DESC);
CREATE INDEX idx_market_skill_status_approved ON market_skill(status, is_official DESC, featured_rank DESC);
CREATE INDEX idx_market_kb_status_approved    ON loom_market_knowledge(status, is_official DESC, featured_rank DESC);
CREATE INDEX idx_market_skill_category        ON market_skill(category);
CREATE INDEX idx_market_kb_category           ON loom_market_knowledge(category);

CREATE INDEX idx_user_knowledge_access_check ON loom_user_knowledge(username, market_id, access_count);
```

- [ ] **Step 1.4: 跑测试 → PASS**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest=MarketSchemaTest
```

Expected:4 tests pass。

> **已知 issue**:H2 的 `INFORMATION_SCHEMA.INDEXES` 在 2.3 改了列名。Step 1.1 测试如失败,改成查 `INDEX_NAME` 来自 `INFORMATION_SCHEMA.INDEXES`(`UPPER(TABLE_NAME)` 配 `UPPER(INDEX_NAME)`)。或在测试里改用 `jdbc.queryForList("SHOW INDEX FROM loom_user_knowledge", ...)` 验证。

- [ ] **Step 1.5: 验证 main app 启动无误**

```bash
rm -rf ~/.loom/datasource
mvn spring-boot:run -pl spring-ai-loom-agent-test
```

预期:启动 OK,`/h2-console` 可见三张新表 + 公共列 + access_check index。

- [ ] **Step 1.6: commit**

```bash
git add spring-ai-loom-agent/src/main/resources/db/migration/V1.0__init.sql \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/MarketSchemaTest.java
git commit -m "feat(schema): add is_official / featured_rank / category / created_by_kind + stats / review / announcement tables (M0)"
```

---

### Task 2: Market domain types (DTOs / enum / column constants)

**Files:**
- Create (all under `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/`):
  - `MarketContentStatus.java`(enum)
  - `MarketContentColumns.java`(字段常量 final class)
  - `MarketFilter.java`(分页 / 过滤 record)
  - `MarketCreateRequest.java`(record)
  - `MarketUpdateRequest.java`(record)

**Interfaces:**
- Consumes: 无
- Produces: `MarketContentStatus.PENDING/APPROVED/REJECTED`、`MarketCreateRequest(name, description, content, category)`、`MarketUpdateRequest(name?, description?, content?, category?, isOfficial?, featuredRank?)`、`MarketFilter(page, size, status?, category?, query?, sortBy?)`

- [ ] **Step 2.1: 创建 enum + 常量类**

```java
// MarketContentStatus.java
package cn.wubo.spring.ai.loom.agent.market;

public enum MarketContentStatus {
    PENDING, APPROVED, REJECTED;

    public static MarketContentStatus from(String s) {
        if (s == null || s.isBlank()) return null;
        return MarketContentStatus.valueOf(s.trim().toUpperCase());
    }
}
```

```java
// MarketContentColumns.java
package cn.wubo.spring.ai.loom.agent.market;

public final class MarketContentColumns {
    private MarketContentColumns() {}
    public static final String IS_OFFICIAL     = "is_official";
    public static final String FEATURED_RANK   = "featured_rank";
    public static final String CATEGORY        = "category";
    public static final String CREATED_BY_KIND = "created_by_kind";
    public static final String STATUS          = "status";
    public static final String REVIEWED_AT     = "reviewed_at";
    public static final String REVIEWED_BY     = "reviewed_by";
    public static final String REVIEW_COMMENT  = "review_comment";
}
```

- [ ] **Step 2.2: 创建 3 个 DTO record**

```java
// MarketFilter.java
package cn.wubo.spring.ai.loom.agent.market;

import java.util.List;

public record MarketFilter(
    int page, int size,
    MarketContentStatus status,
    String category,
    String query,           // SQL LIKE on name/description
    String sortBy           // e.g. "official_rank" | "submitted_at"
) {
    public MarketFilter {
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 20;
        if (sortBy == null || sortBy.isBlank()) sortBy = "official_rank";
    }
}
```

```java
// MarketCreateRequest.java
package cn.wubo.spring.ai.loom.agent.market;

public record MarketCreateRequest(
    String name,
    String description,
    String content,
    String category
) {}
```

```java
// MarketUpdateRequest.java
package cn.wubo.spring.ai.loom.agent.market;

public record MarketUpdateRequest(
    String name,            // for rename; null = no change
    String description,
    String content,
    String category,
    Boolean isOfficial,
    Integer featuredRank
) {}
```

- [ ] **Step 2.3: 写基础单元测试**

```java
// spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/market/MarketContentStatusTest.java
package cn.wubo.spring.ai.loom.agent.market;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketContentStatusTest {
    @Test
    void fromCaseInsensitive() {
        assertEquals(MarketContentStatus.PENDING, MarketContentStatus.from("pending"));
        assertEquals(MarketContentStatus.APPROVED, MarketContentStatus.from("APPROVED"));
        assertEquals(MarketContentStatus.REJECTED, MarketContentStatus.from("  rejected  "));
    }
    @Test
    void fromNullOrBlankReturnsNull() {
        assertNull(MarketContentStatus.from(null));
        assertNull(MarketContentStatus.from(""));
        assertNull(MarketContentStatus.from(" "));
    }
}
```

- [ ] **Step 2.4: 跑测试 + commit**

```bash
mvn test -pl spring-ai-loom-agent -Dtest=MarketContentStatusTest
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/ \
        spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/market/MarketContentStatusTest.java
git commit -m "feat(market): add MarketContentStatus enum + column constants + DTOs (M0 T2)"
```

---

### Task 3: `IMarketContentAdminService<M, U, R>` 接口

**Files:**
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/IMarketContentAdminService.java`

**Interfaces:**
- Consumes: `MarketCreateRequest` / `MarketUpdateRequest` / `MarketFilter`(来自 T2)
- Produces: 接口方法签名 —— `Page<M> listPaged(MarketFilter)` / `M getById(Long)` / `M create(String author, MarketCreateRequest)` / `M update(Long, MarketUpdateRequest)` / `void delete(Long)` / `M approve(Long id, String reviewer)` / `M reject(Long id, String reviewer, String comment)` / `void setOfficial(Long, boolean, String reviewer)` / `void setFeaturedRank(Long, int, String reviewer)` / `void setCategory(Long, String, String reviewer)` / `Page<M> search(MarketSearchQuery)` / `M getRawById(Long)` / `Long insertMarketEntry(M, String author, MarketContentStatus)` (template method 给 abstract base)

注意:**M 泛型**接口,skill 用 `MarketSkill` / KB 用 `MarketKnowledge`,**不**用 raw 类型。

- [ ] **Step 3.1: 创建接口文件**

```java
// IMarketContentAdminService.java
package cn.wubo.spring.ai.loom.agent.market;

import org.springframework.data.domain.Page;

public interface IMarketContentAdminService<M, U, R> {

    Page<M> listPaged(MarketFilter filter);

    M getById(Long id);

    M create(String author, MarketCreateRequest req);

    M update(Long id, MarketUpdateRequest req);

    void delete(Long id);

    M approve(Long id, String reviewer);

    M reject(Long id, String reviewer, String comment);

    void setOfficial(Long id, boolean isOfficial, String reviewer);

    void setFeaturedRank(Long id, int rank, String reviewer);

    void setCategory(Long id, String category, String reviewer);

    /** 全字段搜索(MVP 用 SQL LIKE)。 */
    Page<M> search(String query, String category, int page, int size);
}
```

- [ ] **Step 3.2: `Page` 抽象**

项目目前没有 Spring Data 依赖;直接定义一个简单 record:

```java
// Page.java (in market package)
package cn.wubo.spring.ai.loom.agent.market;

import java.util.List;

public record Page<T>(List<T> items, long total, int page, int size) {
    public static <T> Page<T> of(List<T> items, long total, int page, int size) {
        return new Page<>(items, total, page, size);
    }
}
```

(放在同目录 `Page.java`。)

- [ ] **Step 3.3: 编译验证**

```bash
mvn compile -pl spring-ai-loom-agent
```

Expected:无 error。

- [ ] **Step 3.4: commit**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/
git commit -m "feat(market): IMarketContentAdminService<M,U,R> interface (M0 T3)"
```

---

### Task 4: `AbstractMarketAdminService<M, U, R>` 公共实现

**Files:**
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/AbstractMarketAdminService.java`
- Create: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/AbstractMarketAdminServiceTest.java`

**Interfaces:**
- Consumes: `IMarketContentAdminService<M,U,R>`(T3) / `JdbcTemplate` / `MarketContentStatus` enum(T2) / `MarketContentColumns` 常量(T2)
- Produces:abstract hook `String tableName()`、`List<String> tableColumns()`、`List<Object> rowToParams(M entry)`、`M resultSetToEntry(ResultSet)`、`String approvedByColumn()` 等供子类 override。其他 admin 方法(`approve` / `reject` / `setOfficial` 等)在此 base 实现

**设计要点**:
- `approve` SQL:`UPDATE <table> SET status='APPROVED', reviewed_at=NOW(), reviewed_by=? WHERE id=?`
- `reject` SQL:同上 + `review_comment=?`
- `setOfficial` 等单列 update
- `listPaged` 根据 `MarketFilter.sortBy`:
  - `official_rank`:`ORDER BY is_official DESC, featured_rank DESC, submitted_at DESC`
  - `submitted_at`:`ORDER BY submitted_at DESC`
- `search` 跨 name/description LIKE

- [ ] **Step 4.1: 写失败测试 —— mock JdbcTemplate**

```java
// AbstractMarketAdminServiceTest.java
package cn.wubo.spring.ai.loom.agent.market;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class AbstractMarketAdminServiceTest {
    static class FakeEntry {
        final Long id; final String name; final MarketContentStatus status;
        FakeEntry(Long id, String name, MarketContentStatus status) {
            this.id = id; this.name = name; this.status = status;
        }
    }

    static class TestSvc extends AbstractMarketAdminService<FakeEntry, Void, Void> {
        TestSvc(JdbcTemplate jdbc) { super(jdbc); }
        @Override protected String tableName() { return "market_skill"; }
        @Override protected FakeEntry rowMapper() {
            return (rs, n) -> new FakeEntry(rs.getLong("id"), rs.getString("name"),
                MarketContentStatus.from(rs.getString("status")));
        }
        @Override protected Long extractId(FakeEntry e) { return e.id; }
    }

    JdbcTemplate jdbc;
    TestSvc svc;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        svc = new TestSvc(jdbc);
    }

    @Test
    void approveUpdatesStatus() {
        when(jdbc.update(startsWith("UPDATE market_skill SET status='APPROVED'")), any(Object[].class))
            .thenReturn(1);
        svc.approve(5L, "admin1");
        verify(jdbc).update(startsWith("UPDATE market_skill SET status='APPROVED'"), any(Object[].class));
    }

    @Test
    void rejectRequiresComment() {
        assertThrows(IllegalArgumentException.class,
            () -> svc.reject(5L, "admin1", ""));
        assertThrows(IllegalArgumentException.class,
            () -> svc.reject(5L, "admin1", null));
    }

    @Test
    void setOfficialUpdatesColumn() {
        when(jdbc.update(startsWith("UPDATE market_skill SET is_official=")), any(Object[].class))
            .thenReturn(1);
        svc.setOfficial(5L, true, "admin1");
        verify(jdbc).update(eq("UPDATE market_skill SET is_official=? WHERE id=?"), eq(true), eq(5L));
    }
}
```

- [ ] **Step 4.2: 跑测试,确认失败**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest=AbstractMarketAdminServiceTest
```

Expected:`approveUpdatesStatus` 等 fail(`class not found`)。

- [ ] **Step 4.3: 实现 AbstractMarketAdminService 公共方法**

```java
// AbstractMarketAdminService.java
package cn.wubo.spring.ai.loom.agent.market;

import org.springframework.data.domain.PageImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

public abstract class AbstractMarketAdminService<M, U, R> implements IMarketContentAdminService<M, U, R> {

    protected final JdbcTemplate jdbc;

    protected AbstractMarketAdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    protected abstract String tableName();
    protected abstract RowMapper<M> rowMapper();
    protected abstract Long extractId(M entry);

    @Override
    public MarketContentStatus currentStatus(M entry) {
        // 子类可覆盖;默认 throw,要求子类实现
        return currentStatusImpl(entry);
    }

    protected MarketContentStatus currentStatusImpl(M entry) {
        throw new UnsupportedOperationException();
    }

    @Override
    public M approve(Long id, String reviewer) {
        jdbc.update(
            "UPDATE " + tableName() + " SET status='APPROVED', reviewed_at=CURRENT_TIMESTAMP, reviewed_by=? WHERE id=?",
            reviewer, id);
        return getById(id);
    }

    @Override
    public M reject(Long id, String reviewer, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("reject 必须填评论(comment 必填)");
        }
        jdbc.update(
            "UPDATE " + tableName() + " SET status='REJECTED', reviewed_at=CURRENT_TIMESTAMP, reviewed_by=?, review_comment=? WHERE id=?",
            reviewer, comment, id);
        return getById(id);
    }

    @Override
    public void setOfficial(Long id, boolean isOfficial, String reviewer) {
        jdbc.update("UPDATE " + tableName() + " SET is_official=? WHERE id=?", isOfficial, id);
    }

    @Override
    public void setFeaturedRank(Long id, int rank, String reviewer) {
        jdbc.update("UPDATE " + tableName() + " SET featured_rank=? WHERE id=?", rank, id);
    }

    @Override
    public void setCategory(Long id, String category, String reviewer) {
        jdbc.update("UPDATE " + tableName() + " SET category=? WHERE id=?", category, id);
    }

    @Override
    public Page<M> listPaged(MarketFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(tableName());
        appendCommonFilters(sql, filter);
        sql.append(" ORDER BY ");
        switch (filter.sortBy()) {
            case "official_rank" -> sql.append("is_official DESC, featured_rank DESC, submitted_at DESC");
            case "submitted_at"  -> sql.append("submitted_at DESC");
            default              -> sql.append("is_official DESC, featured_rank DESC, submitted_at DESC");
        }
        sql.append(" LIMIT ").append(filter.size()).append(" OFFSET ").append(filter.page() * filter.size());
        List<M> rows = jdbc.query(sql.toString(), rowMapper());
        long total = countWith(filter);
        return new Page<>(rows, total, filter.page(), filter.size());
    }

    private void appendCommonFilters(StringBuilder sql, MarketFilter filter) {
        sql.append(" WHERE 1=1");
        if (filter.status() != null) sql.append(" AND status='").append(filter.status().name()).append("'");
        if (filter.category() != null && !filter.category().isBlank())
            sql.append(" AND category='").append(filter.category().replace("'", "''")).append("'");
        if (filter.query() != null && !filter.query().isBlank()) {
            String q = "%" + filter.query().replace("'", "''") + "%";
            sql.append(" AND (name LIKE '").append(q).append("' OR description LIKE '").append(q).append("')");
        }
    }

    private long countWith(MarketFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(tableName());
        appendCommonFilters(sql, filter);
        Long n = jdbc.queryForObject(sql.toString(), Long.class);
        return n == null ? 0L : n;
    }

    @Override
    public Page<M> search(String query, String category, int page, int size) {
        return listPaged(new MarketFilter(page, size, MarketContentStatus.APPROVED, category, query, "official_rank"));
    }

    @Override
    public void delete(Long id) {
        jdbc.update("DELETE FROM " + tableName() + " WHERE id=?", id);
    }
}
```

> 上面 `MarketContentStatus currentStatus(M)` 在 `IMarketContentAdminService` 加入:

```java
// 在 IMarketContentAdminService.java 加一行:
MarketContentStatus currentStatus(M entry);
```

- [ ] **Step 4.4: 修测试,跑通**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest=AbstractMarketAdminServiceTest
```

Expected:3 tests pass。

- [ ] **Step 4.5: commit**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/AbstractMarketAdminService.java \
        spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/IMarketContentAdminService.java \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/AbstractMarketAdminServiceTest.java
git commit -m "feat(market): AbstractMarketAdminService with approve/reject/setOfficial/setFeaturedRank (M0 T4)"
```

---

### Task 5: `DefaultSkillMarketService extends AbstractMarketAdminService`

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/skill/DefaultSkillMarketService.java`(已存在,改造)
- Create: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/DefaultSkillMarketServiceIT.java`

**Interfaces:**
- Consumes: `AbstractMarketAdminService<MarketSkill, UserSkill, SkillReview>`(T4)、`MarketSkill` 表结构(T1)
- Produces: 完整的 `IMarketContentAdminService<MarketSkill, UserSkill, SkillReview>` 实现,override `tableName = "market_skill"`、`rowMapper()`、`extractId(MarketSkill)` 等

- [ ] **Step 5.1: 写失败 IT**

```java
// DefaultSkillMarketServiceIT.java
package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = LoomAgentTestApplication.class)
class DefaultSkillMarketServiceIT {

    @Autowired DefaultSkillMarketService svc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createAppendsRowToMarketSkill() {
        long id = svc.create("alice", new MarketCreateRequest(
            "name-" + System.nanoTime(),
            "desc",
            "content",
            "category-x"
        )).id();
        assertTrue(id > 0);
        String status = jdbc.queryForObject("SELECT status FROM market_skill WHERE id=?", String.class, id);
        assertEquals("PENDING", status);
    }

    @Test
    void approveFlipsStatus() {
        long id = svc.create("alice", new MarketCreateRequest(
            "appr-" + System.nanoTime(), "d", "c", null
        )).id();
        svc.approve(id, "admin1");
        String s = jdbc.queryForObject("SELECT status FROM market_skill WHERE id=?", String.class, id);
        assertEquals("APPROVED", s);
        String reviewedBy = jdbc.queryForObject("SELECT reviewed_by FROM market_skill WHERE id=?", String.class, id);
        assertEquals("admin1", reviewedBy);
    }

    @Test
    void rejectWithoutCommentThrows() {
        long id = svc.create("alice", new MarketCreateRequest(
            "rej-" + System.nanoTime(), "d", "c", null
        )).id();
        assertThrows(IllegalArgumentException.class, () -> svc.reject(id, "admin1", ""));
    }

    @Test
    void setOfficialTogglesFlag() {
        long id = svc.create("alice", new MarketCreateRequest(
            "off-" + System.nanoTime(), "d", "c", null
        )).id();
        svc.setOfficial(id, true, "admin1");
        Boolean official = jdbc.queryForObject("SELECT is_official FROM market_skill WHERE id=?", Boolean.class, id);
        assertEquals(true, official);
    }
}
```

- [ ] **Step 5.2: 运行,确认失败**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest=DefaultSkillMarketServiceIT
```

Expected:`NoSuchBeanDefinitionException: DefaultSkillMarketService`(改造后才有)。

- [ ] **Step 5.3: 改造 `DefaultSkillMarketService`**

打开现有 `DefaultSkillMarketService.java`,改造如下(若项目用 `@Component`,保留):

```java
// 在原 DefaultSkillMarketService.java 头部:
import cn.wubo.spring.ai.loom.agent.market.*;
import cn.wubo.spring.ai.loom.agent.market.MarketContentStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.List;

// 改成 extends AbstractMarketAdminService<MarketSkill, UserSkill, SkillReview>
@Service
public class DefaultSkillMarketService extends AbstractMarketAdminService<MarketSkill, UserSkill, SkillReview> {

    private final JdbcTemplate jdbc;
    public DefaultSkillMarketService(JdbcTemplate jdbc) {
        super(jdbc);
        this.jdbc = jdbc;
    }

    @Override protected String tableName() { return "market_skill"; }

    @Override protected RowMapper<MarketSkill> rowMapper() {
        return (rs, n) -> MarketSkill.from(rs);   // 由 MarketSkill 新增的静态工厂方法
    }

    @Override protected Long extractId(MarketSkill e) { return e.id(); }

    @Override
    public MarketSkill create(String author, MarketCreateRequest req) {
        // 子类自定义 create 路径:user 直发改 PENDING(基类模板)
        jdbc.update(
            "INSERT INTO market_skill (name, description, content, author, status, created_by_kind) " +
            "VALUES (?, ?, ?, ?, 'PENDING', 'USER')",
            req.name(), req.description(), req.content(), author);
        Long id = jdbc.queryForObject(
            "SELECT id FROM market_skill WHERE author=? AND name=? ORDER BY id DESC LIMIT 1",
            Long.class, author, req.name());
        return getById(id);
    }

    @Override
    public MarketSkill update(Long id, MarketUpdateRequest req) {
        StringBuilder sql = new StringBuilder("UPDATE market_skill SET updated_at=CURRENT_TIMESTAMP");
        List<Object> args = new java.util.ArrayList<>();
        if (req.name() != null)        { sql.append(", name=?");        args.add(req.name()); }
        if (req.description() != null) { sql.append(", description=?"); args.add(req.description()); }
        if (req.content() != null)     { sql.append(", content=?");     args.add(req.content()); }
        if (req.category() != null)    { sql.append(", category=?");    args.add(req.category()); }
        sql.append(" WHERE id=?");
        args.add(id);
        jdbc.update(sql.toString(), args.toArray());
        return getById(id);
    }

    @Override
    public MarketSkill getById(Long id) {
        return jdbc.queryForObject(
            "SELECT * FROM market_skill WHERE id=?", rowMapper(), id);
    }
}
```

- [ ] **Step 5.4: 给 `MarketSkill` 加 `from(ResultSet)` 静态方法**

`MarketSkill.java` 是 record,加 companion:

```java
public record MarketSkill(Long id, String name, String description, String content,
                          String author, String status, ...) { // 实际字段以现有 record 为准

    public static MarketSkill from(ResultSet rs) throws SQLException {
        return new MarketSkill(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("content"),
            rs.getString("author"),
            rs.getString("status"),
            rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toLocalDateTime(),
            rs.getString("reviewed_by"),
            rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toLocalDateTime(),
            rs.getString("review_comment"),
            rs.getBoolean("is_official"),
            rs.getInt("featured_rank"),
            rs.getString("category"),
            rs.getString("created_by_kind")
        );
    }
}
```

> 如果 `MarketSkill` 的 record 字段少于上面清单,用 `rs.getObject(...)` 跳过缺失列;具体字段以 `MarketSkill.java` 实际 declaration 为准。

- [ ] **Step 5.5: 跑测试 → PASS**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest=DefaultSkillMarketServiceIT
```

Expected:4 tests pass。

- [ ] **Step 5.6: commit**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/skill/DefaultSkillMarketService.java \
        spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/MarketSkill.java \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/DefaultSkillMarketServiceIT.java
git commit -m "feat(skill): DefaultSkillMarketService extends AbstractMarketAdminService (M0 T5)"
```

---

### Task 6: `DefaultKnowledgeMarketService` (KB 端镜像)

**Files:**
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/knowledge/market/DefaultKnowledgeMarketService.java`
- Create: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/DefaultKnowledgeMarketServiceIT.java`

**Interfaces:**
- Consumes: 与 T5 镜像,`tableName = "loom_market_knowledge"`
- Produces: `IMarketContentAdminService<MarketKnowledge, UserKnowledge, KnowledgeReview>`

- [ ] **Step 6.1: 写 IT 镜像 T5 的 4 个测试**

```java
// DefaultKnowledgeMarketServiceIT.java  (结构同 T5,只换类名 + 表名)
// 把所有 market_skill 替换为 loom_market_knowledge
// 把 DefaultSkillMarketService 替换为 DefaultKnowledgeMarketService
```

(实际写在 `spring-ai-loom-agent-test/.../market/DefaultKnowledgeMarketServiceIT.java`,结构同 T5)

- [ ] **Step 6.2: 实现镜像 Skill 版**

```java
// DefaultKnowledgeMarketService.java
package cn.wubo.spring.ai.loom.agent.knowledge.market;

@Service
public class DefaultKnowledgeMarketService
        extends AbstractMarketAdminService<MarketKnowledge, UserKnowledge, KnowledgeReview> {

    public DefaultKnowledgeMarketService(JdbcTemplate jdbc) { super(jdbc); }

    @Override protected String tableName() { return "loom_market_knowledge"; }

    @Override protected RowMapper<MarketKnowledge> rowMapper() {
        return (rs, n) -> MarketKnowledge.from(rs);
    }

    @Override protected Long extractId(MarketKnowledge e) { return e.id(); }

    @Override
    public MarketKnowledge create(String author, MarketCreateRequest req) {
        jdbc.update(
            "INSERT INTO loom_market_knowledge (name, description, content, author, status, created_by_kind) " +
            "VALUES (?, ?, ?, ?, 'PENDING', 'USER')",
            req.name(), req.description(), req.content(), author);
        Long id = jdbc.queryForObject(
            "SELECT id FROM loom_market_knowledge WHERE author=? AND name=? ORDER BY id DESC LIMIT 1",
            Long.class, author, req.name());
        return getById(id);
    }

    // update / getById 镜像 T5
}
```

- [ ] **Step 6.3: 给 `MarketKnowledge` 加 `from(ResultSet)`**(T5 后已有先例)

- [ ] **Step 6.4: 跑测试 → PASS**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest=DefaultKnowledgeMarketServiceIT
```

- [ ] **Step 6.5: commit**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/knowledge/market/ \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/DefaultKnowledgeMarketServiceIT.java
git commit -m "feat(knowledge): DefaultKnowledgeMarketService mirrors skill (M0 T6)"
```

---

### Task 7: Admin market router — SKILL 12 端点

**Files:**
- Modify: `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java`(`WebConfiguration` 静态类,加 `loomAgentMarketSkillAdminRouter()`)

**Interfaces:**
- Consumes: `DefaultSkillMarketService`(T5)、`AuthenticationFilter` admin 鉴权
- Produces: `RouterFunction<ServerResponse>` bean 名为 `loomAgentMarketSkillAdminRouter`

12 端点清单见 spec § 6.2。

- [ ] **Step 7.1: 写 endpoint 集成 smoke test**

```java
// spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/MarketSkillAdminRouterIT.java
package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = LoomAgentTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarketSkillAdminRouterIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    String base;
    RestTemplate http = new RestTemplate();

    @BeforeEach
    void setup() {
        // 这里假设当前测试用 alice (普通 user / non-admin)
        // admin-only 路由预期 401/403;测公共端点供下面 task 复用
        base = "http://localhost:" + port;
    }

    @Test
    void adminEndpointRejectsNonAdmin() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> req = new HttpEntity<>("{}", h);
        try {
            http.exchange(base + "/spring/ai/loom/admin/market-skills", HttpMethod.GET, req, String.class);
            fail("expected 401");
        } catch (org.springframework.web.client.HttpClientErrorException ex) {
            assertTrue(ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403);
        }
    }
}
```

- [ ] **Step 7.2: 跑测试,确认失败**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest=MarketSkillAdminRouterIT
```

Expected:`expected 401` 但路由不存在 → 404。

- [ ] **Step 7.3: 在 `LoomAgentConfiguration.WebConfiguration` 加 router**

完整实现 12 个端点。下面是 admin base 模板,逐个 `andRoute(...)` 即可,不省略 / 不写"..."。

```java
@Bean
public RouterFunction<ServerResponse> loomAgentMarketSkillAdminRouter(DefaultSkillMarketService svc) {
    RequestPredicate adminBase = path("/spring/ai/loom/admin/market-skills");
    RequestPredicate id = path("/spring/ai/loom/admin/market-skills/{id}");

    return route(GET(adminBase),
            req -> {
                MarketFilter filter = new MarketFilter(
                    parsePageOr(req, "page", 0),
                    parsePageOr(req, "size", 50),
                    MarketContentStatus.from(req.param("status").orElse(null)),
                    req.param("category").orElse(null),
                    req.param("query").orElse(null),
                    req.param("sortBy").orElse("official_rank")
                );
                return ok().body(svc.listPaged(filter));
            })
        .andRoute(POST(adminBase),
            req -> req.bodyToMono(MarketCreateRequest.class)
                .flatMap(b -> ok().body(BodyInserters.fromValue(svc.create("admin", b)))))
        .andRoute(PUT(id),
            req -> req.pathVariable("id").equals("") ? badRequest().build()
                : req.bodyToMono(MarketUpdateRequest.class)
                    .flatMap(b -> ok().body(BodyInserters.fromValue(svc.update(Long.valueOf(req.pathVariable("id")), b)))))
        .andRoute(DELETE(id),
            req -> { svc.delete(Long.valueOf(req.pathVariable("id"))); return ok().build(); })
        .andRoute(POST(id.and(path("/approve"))),
            req -> { Long idL = Long.valueOf(req.pathVariable("id"));
                     return ok().body(BodyInserters.fromValue(svc.approve(idL, currentUser(req)))); })
        .andRoute(POST(id.and(path("/reject"))),
            req -> req.bodyToMono(RejectBody.class)
                .flatMap(b -> ok().body(BodyInserters.fromValue(
                    svc.reject(Long.valueOf(req.pathVariable("id")), currentUser(req), b.comment()))))
                .switchIfEmpty(ServerResponse.badRequest().bodyValue("reject needs comment")))
        .andRoute(PUT(id.and(path("/official"))),
            req -> req.bodyToMono(OfficialBody.class).flatMap(b -> {
                svc.setOfficial(Long.valueOf(req.pathVariable("id")), b.isOfficial(), currentUser(req));
                return ok().build();
            }))
        .andRoute(PUT(id.and(path("/featured-rank"))),
            req -> req.bodyToMono(FeaturedRankBody.class).flatMap(b -> {
                svc.setFeaturedRank(Long.valueOf(req.pathVariable("id")), b.rank(), currentUser(req));
                return ok().build();
            }))
        .andRoute(PUT(id.and(path("/category"))),
            req -> req.bodyToMono(CategoryBody.class).flatMap(b -> {
                svc.setCategory(Long.valueOf(req.pathVariable("id")), b.category(), currentUser(req));
                return ok().build();
            }))
        .andRoute(PUT(id.and(path("/announcement"))),
            req -> req.bodyToMono(AnnouncementBody.class).flatMap(b -> {
                announcementRepo.upsert("SKILL", Long.valueOf(req.pathVariable("id")), b.title(), b.body());
                return ok().build();
            }))
        .andRoute(DELETE(id.and(path("/announcement"))),
            req -> { announcementRepo.delete("SKILL", Long.valueOf(req.pathVariable("id"))); return ok().build(); })
        .andRoute(DELETE(id.and(path("/reviews/{u}"))),
            req -> { reviewService.deleteAsAdmin(Long.valueOf(req.pathVariable("id")), req.pathVariable("u")); return ok().build(); });
}
```

> `currentUser(req)` 复用项目内已有 helper(从 cookie / context 读 username);`announcementRepo` 在 T19 引入,本 Task 用 method stub 即可,但 router 必须完全可编译。

- [ ] **Step 7.3a: 加 rejectBody / OfficialBody / FeaturedRankBody / CategoryBody / AnnouncementBody 5 个 record**(放 market 包作为 `RejectBody` etc.)

- [ ] **Step 7.4: 加 admin auth filter 规则**

修改现有 `AuthenticationFilter` 配置 `auth.adminPathPatterns` 加入 `/spring/ai/loom/admin/market-skills/**`。具体值在 `LoomAgentConfiguration.InfrastructureConfiguration` 默认值里。

- [ ] **Step 7.5: 跑测试 → PASS**

- [ ] **Step 7.6: commit**

```bash
git commit -am "feat(router): admin market-skill 12 endpoints (M0 T7)"
```

---

### Task 8: Admin market router — KB 12 镜像端点

**Files:**
- Modify: `spring-ai-loom-agent-spring-boot-autoconfigure/.../LoomAgentConfiguration.java`

**Interfaces:**
- Consumes: `DefaultKnowledgeMarketService`(T6)
- Produces: `loomAgentMarketKnowledgeAdminRouter` bean

- [ ] **Step 8.1 - 8.6**:完整镜像 T7,把 `loomAgentMarketSkillAdminRouter` 内每个 endpoint 把 `market-skill` → `market-knowledge`、`svc = skill svc` → `svc = kb svc`

- [ ] **Step 8.7: 抽公共 builder(DRY)**

```java
private RouterFunctions.Builder marketAdminRoutes(String prefix, IMarketContentAdminService<?, ?, ?> svc) {
    // 抽出来后两套 router 调用
}
```

**重写**:此步前先 commit 当前 router;后面再 refactor 拆公共 builder,作为单独 commit。

- [ ] **Step 8.8: 跑 OK + commit**

```bash
git commit -am "feat(router): admin market-knowledge 12 mirror endpoints (M0 T8)"
```

---

### Task 9: Public market router — SKILL 9 端点

**Files:**
- Modify: `spring-ai-loom-agent-spring-boot-autoconfigure/.../LoomAgentConfiguration.java`

**Interfaces:**
- Consumes: `DefaultSkillMarketService`(T5)
- Produces: `loomAgentMarketSkillPublicRouter` bean,挂在 `/spring/ai/loom/`

9 端点见 spec § 6.1。

- [ ] **Step 9.1-9.5**:逐端点实现,**所有路由都要 `/spring/ai/loom/` 前缀**(已经 AuthenticationFilter exclude 列表外,会被强制 user 登录)。

- [ ] **Step 9.6: commit**

```bash
git commit -am "feat(router): public market-skill 9 endpoints (M0 T9)"
```

---

### Task 10: Public market router — KB 9 + 1 端点

**Files:**
- Modify: `spring-ai-loom-agent-spring-boot-autoconfigure/.../LoomAgentConfiguration.java`

**Interfaces:**
- Consumes: `DefaultKnowledgeMarketService`(T6)
- Produces: `loomAgentMarketKnowledgePublicRouter` bean

镜像 T9 + 1 个独有 `POST /market-knowledge/{id}/access`,这个以后在 T15 接入 `kbStatsService.incrementStat`。

- [ ] **Step 10.1-10.6**:同 T9

- [ ] **Step 10.7: commit**

```bash
git commit -am "feat(router): public market-knowledge 10 endpoints incl access (M0 T10)"
```

---

### Task 11: Frontend shared `market-admin.js`

**Files:**
- Create: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/market-admin.js`

**Interfaces:**
- Consumes: 暴露 `window.MarketAdmin` 命名空间,4 个函数:`list(kind)`、`form(kind, mode, entry)`、`reviewList(kind, marketId)`、`approvalBadge(status, reviewer, reviewedAt, comment)`
- Produces:DOM 节点 on `<div id="admin-market-root">`

- [ ] **Step 11.1: 写最小 stub**

```js
// market-admin.js
window.MarketAdmin = {
  list: function (kind) { /* TODO: render table */ },
  form: function (kind, mode, entry) { /* TODO: render form */ },
  reviewList: function (kind, marketId) { /* TODO */ },
  approvalBadge: function (status, reviewer, reviewedAt, comment) { /* TODO */ }
};
```

- [ ] **Step 11.2: 手动 smoke** —— 打开 `admin/market-skills.html`(在 T12 引入此 JS)看 console 不报错

- [ ] **Step 11.3: commit**

```bash
git commit -am "feat(admin): market-admin.js shared namespace (M0 T11)"
```

---

### Task 12: Frontend `market-skills.html` + `market-skills.js`

**Files:**
- Rename: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/skills-market.html` → `market-skills.html`(包 git mv,保留历史)
- Rename: `admin/skills-market.js` → `admin/market-skills.js`
- Modify: 表头列扩 spec § 8.2

- [ ] **Step 12.1: 跑现有 admin 测试** 确认无 regression

- [ ] **Step 12.2: 把 list 列改用 `MarketAdmin.list('SKILL')`** —— 接受 kind 参数,内部 fetch `/spring/ai/loom/admin/market-skills?...`

- [ ] **Step 12.3: 加"待审核 (N)" 顶部红色 chip**(读 admin list 第一项 N)

- [ ] **Step 12.4: 跑测试 + 手动 UI 验证 + commit**

```bash
git add -A
git commit -am "feat(admin): market-skills.html - sort/official/category/feature columns (M0 T12)"
```

---

### Task 13: Frontend `market-knowledge.html` + `market-knowledge.js`

**Files:**
- Create: `admin/market-knowledge.html`
- Create: `admin/market-knowledge.js`

镜像 T12,`kind = 'KNOWLEDGE'`。

- [ ] **Step 13.1-13.4**:完整镜像 T12

- [ ] **Step 13.5: commit**

```bash
git commit -am "feat(admin): market-knowledge.html mirror of market-skills (M0 T13)"
```

---

### Task 14: Frontend `app.js` + `console.html` 用户侧更新

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js`
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/console.html`

**Interfaces:**
- 消费 spec § 8.1 / § 8.3 列举的改动

- [ ] **Step 14.1: 写失败/已坏的现有功能 sanity check** —— 跑 `mvn test -pl test -Dtest=ChatTest` 确认 app.js 没破坏

- [ ] **Step 14.2: 改 `app.js` 市场 Tab 排序**

```js
// Skills Modal "市场" Tab 渲染函数 listMarketSkills,改成:
const list = await fetch("/spring/ai/loom/market-skills?...&sortBy=official_rank");
list.sort((a, b) => {
  if (a.is_official !== b.is_official) return b.is_official - a.is_official;
  return b.featured_rank - a.featured_rank;
});
```

- [ ] **Step 14.3: 加官方徽章 `🏛️` 在 is_official=true 行**

- [ ] **Step 14.4: "我的发布" Tab 详情加 `review_comment` 展示**(当 status=REJECTED)

- [ ] **Step 14.5: KB modal 同样改动**

- [ ] **Step 14.6: `console.html` 加待审核角标**

```js
// /admin/console.html 的初始化函数,加:
fetch("/spring/ai/loom/admin/market-skills?size=1").then(r => {
  // 头信息里读 total
});
```

(实际上要后端返回 total → 修改 admin router 加 `?countOnly=true` 或者返回 header `X-Total-Count`。本期简化:不在 console.html 显示数字 chip,只显示 0 / 有;具体数字留给 M1)

- [ ] **Step 14.7: 手动 UI 跑 + commit**

```bash
git commit -am "feat(frontend): user market tab sort + official badge + reject comment (M0 T14)"
```

---

## Phase M1 — Stats / Reviews / Announcements

### Task 15: `BatchedCounterService` 通用批写

**Files:**
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/BatchedCounterService.java`
- Create: `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/market/BatchedCounterServiceTest.java`

**Interfaces:**
- Produces:`void increment(String tableSuffix, String keyColumn, Long key, String countColumn, String lastColumn)` —— 通用

- [ ] **Step 15.1: 写失败测试**

```java
class BatchedCounterServiceTest {
    @Test
    void incrementsAreBufferedAndFlushed() {
        BatchedCounterService svc = new BatchedCounterService();
        // 直接注入 mock flush consumer,避免起 Spring
        java.util.concurrent.atomic.AtomicInteger flushCount = new java.util.concurrent.atomic.AtomicInteger();
        svc.setFlushExecutor((updates) -> { flushCount.incrementAndGet(); return java.util.concurrent.CompletableFuture.completedFuture(null); });
        svc.increment("market_skill_stats", "market_skill_id", 5L, "pull_count", "last_pulled_at");
        svc.increment("market_skill_stats", "market_skill_id", 5L, "pull_count", "last_pulled_at");
        svc.flush();
        assertEquals(1, flushCount.get());
    }
}
```

- [ ] **Step 15.2: 实现 class**

```java
public class BatchedCounterService {
    private final ConcurrentHashMap<String, AtomicLong> buffer = new ConcurrentHashMap<>();
    private FlushExecutor executor = new JdbcFlushExecutor();

    public interface FlushExecutor { CompletableFuture<Void> flush(List<Update> updates); }

    static class Update {
        final String table; final String keyCol; final Long key;
        final String cntCol; final Long delta; final String lastCol;
        Update(...) { ... }
    }

    public void increment(String table, String keyCol, Long key,
                          String cntCol, String lastCol) {
        String bk = table + ":" + keyCol + ":" + key + ":" + cntCol;
        buffer.computeIfAbsent(bk, k -> new AtomicLong()).incrementAndGet();
    }

    public void flush() {
        List<Update> updates = buffer.entrySet().stream()
            .map(e -> /* build Update */).toList();
        buffer.clear();
        if (!updates.isEmpty()) executor.flush(updates);
    }

    // ScheduledExecutorService 每 30s 自动 flush
}
```

- [ ] **Step 15.3: 跑测试 + commit**

- [ ] **Step 15.4: 在 `LoomAgentConfiguration` 注册 `@Scheduled` 定时 30s flush**

- [ ] **Step 15.5: commit**

```bash
git commit -am "feat(market): BatchedCounterService with 30s flush + on shutdown flush (M1 T15)"
```

---

### Task 16: Stats service

**Files:**
- Create: `IMarketContentStatsService.java`、`AbstractMarketStatsService.java`、`StatsRow.java`、`DefaultSkillStatsService.java`、`DefaultKnowledgeStatsService.java`

**Interfaces:**
- `void incrementStat(Long marketId, String kind)` —— `PULL` / `SEARCH`
- `StatsRow getStats(Long marketId)`

- [ ] **Step 16.1-16.5**:写测试 → 跑 fail → impl → run pass → commit(模式同 T5)

- [ ] **Step 16.6: 在 router T7/T8 admin 端加 `PUT /admin/market-{kind}s/{id}/stats-reset`**(admin 重置)**

- [ ] **Step 16.7: 在 router T9/T10 public 端 `GET /market-{kind}s/{id}/stats`**

- [ ] **Step 16.8: Wire `DefaultKnowledgeTool.searchKnowledge` → `kbStatsService.incrementStat(marketId, "SEARCH")`(spec § 7.2)**

```java
// DefaultKnowledgeTool.java (existing file modify):
public List<Document> searchKnowledge(@ToolParam String knowledgeId, ...) {
    // 已有检索实现
    List<Document> docs = kbVectorStore.similaritySearch(...);
    // M1 起加:
    knowledgeStatsService.incrementStat(Long.parseLong(knowledgeId), "SEARCH");
    return docs;
}
```

- [ ] **Step 16.9: 在 `LoomAgentConfiguration` 注册 `@Bean DefaultKnowledgeStatsService` / `DefaultKnowledgeReviewService`**(已有 bean wiring 模式,沿用)

- [ ] **Step 16.10: 跑测试 → commit**

```bash
git commit -am "feat(stats): IMarketContentStatsService impl + endpoints + searchKnowledge wire (M1 T16)"
```

---

### Task 17: Review service (含严门槛 + edit_count)

**Files:**
- Create: `IMarketContentReviewService.java`、`AbstractMarketReviewService.java`、`ReviewRow.java`、`RatingAggregate.java`、`ReviewSubmitRequest.java`、`ReviewUpdateRequest.java`、`DefaultSkillReviewService.java`、`DefaultKnowledgeReviewService.java`

**Interfaces:**
- `Page<ReviewRow> listReviews(Long marketId, int page, int size)`
- `ReviewRow submit(Long marketId, String username, int rating, String comment)`
- `ReviewRow update(Long marketId, String username, int rating, String comment)` —— `edit_count < 1` 校验
- `void deleteAsAdmin(Long marketId, String username)`
- `RatingAggregate aggregate(Long marketId)` —— 返回 `{count, avg, sum}`,**admin 自评不计**

KB 端严门槛:`SELECT 1 FROM loom_user_knowledge WHERE username=? AND market_id=? AND access_count>=1`,无记录 → 抛 403 with 文案"请先访问过该知识库再评"。

- [ ] **Step 17.1: 写 `IMarketContentReviewService` + 抽象**

- [ ] **Step 17.2: 实现 SKILL 端**

- [ ] **Step 17.3: 实现 KB 端 + 严门槛 + 写 IT**

```java
@Test
void kbReviewRequiresPriorAccess() {
    // 创建一条 market kbb 但不 access
    long id = kbSvc.create("alice", new MarketCreateRequest("t-" + nano, "d", "c", null)).id();
    assertThrows(LoomAgentRuntimeException.class,
        () -> kbReview.submit(id, "bob", 5, "good"));
}
```

- [ ] **Step 17.4: `aggregate` SQL 排除 admin**(spec § 9.2 第 5 行)

```java
public RatingAggregate aggregate(Long marketId) {
    // 通过 JdbcTemplate 查 user_info.type IS DISTINCT FROM 'ADMIN'
    return jdbc.queryForObject(
        "SELECT COUNT(*), AVG(rating) FROM market_skill_review r " +
        "JOIN user_info u ON u.username = r.username " +
        "WHERE r.market_skill_id=? AND u.type <> 'ADMIN'",
        (rs, n) -> new RatingAggregate(rs.getLong(1),
            rs.getBigDecimal(2) == null ? 0.0 : rs.getBigDecimal(2).doubleValue()),
        marketId);
}
```

- [ ] **Step 17.5: 跑 + commit**

```bash
git commit -am "feat(review): IMarketContentReviewService + 2 impls + KB 严门槛 (M1 T17)"
```

---

### Task 18: Review / Announcement endpoints + admin delete

**Files:**
- Modify: `LoomAgentConfiguration.WebConfiguration`

端点:
- public:`POST /market-{kind}s/{id}/reviews`、`GET /market-{kind}s/{id}/reviews`、`PUT /market-{kind}s/{id}/reviews/me`
- admin:`DELETE /admin/market-{kind}s/{id}/reviews/{username}`
- admin:`PUT /admin/market-{kind}s/{id}/announcement` body `{title, body}`、`DELETE /admin/market-{kind}s/{id}/announcement`

- [ ] **Step 18.1: 写 IT(同模式 T7/T8)**

- [ ] **Step 18.2: 实现 router**

- [ ] **Step 18.3: 跑 + commit**

```bash
git commit -am "feat(router): review + announcement endpoints (M1 T18)"
```

---

### Task 19: Frontend review / announcement UI

**Files:**
- Modify: `app.js`、`admin/market-admin.js`、`admin/market-skills.html`、`admin/market-knowledge.html`

- [ ] **Step 19.1: `MarketAdmin.reviewList(kind, marketId)`** —— fetch `GET /market-{kind}s/{id}/reviews`,渲染星级 + 评论 + username + 时间

- [ ] **Step 19.2: 用户侧 `app.js` Skills/KB Modal 详情面板加 "评分与评论" section**(fetch `GET /market-skills/{id}/reviews`,提交表单 `POST`)

- [ ] **Step 19.3: admin 端 review 列显示 + "删除评论" 按钮**

- [ ] **Step 19.4: 公告 UI:admin 加 "发布公告" / "撤销公告" 按钮;用户市场 Tab 顶部插入公告 banner**(取 `/market-{kind}s` 同时 fetch 关联 `/admin/market-{kind}s/{id}/announcement` 或新端点)

> 为减小前端复杂度,可加 `GET /market-{kind}s` 返回 DTO 嵌入 `announcementTitle / announcementBody`(MCP 后端 join)。在 T18 router 加 join SQL。

- [ ] **Step 19.5: 跑 + commit**

```bash
git commit -am "feat(frontend): review + announcement UI (M1 T19)"
```

---

## Phase M2 — KB Tags + Cross-Market

### Task 20: KB tag schema + service + endpoints

**Files:**
- Schema(已 T1 完成,本 task 不再改)
- Create: `cn/wubo/spring/ai/loom/agent/knowledge/market/KnowledgeTagService.java`
- Modify: `DefaultKnowledgeMarketService.java` 添加 tag 方法
- Modify: `LoomAgentConfiguration.java`

端点:
- `PUT /admin/market-knowledge/{id}/tags` body `{tags: [...]}` —— batch upsert
- `GET /market-knowledge/{id}/tags`
- 用户侧 `GET /market-knowledge?tag=xxx` 过滤

- [ ] **Step 20.1: 写失败测试**

- [ ] **Step 20.2: 实现 `KnowledgeTagService`(`addTags` / `removeTag` / `listTags` / `findByTag`)**

- [ ] **Step 20.3: router**

- [ ] **Step 20.4: 跑 + commit**

```bash
git commit -am "feat(kb): tag 多对多 + endpoints (M2 T20)"
```

---

### Task 21: Frontend KB tag UI

**Files:**
- Modify: `app.js`、`admin/market-admin.js`、`admin/market-knowledge.html`

- [ ] **Step 21.1: 市场 KB 列表加 tag chip**

- [ ] **Step 21.2: 点 chip 触发 `GET /market-knowledge?tag=xxx`**

- [ ] **Step 21.3: admin 编辑 tag(批量添加 + 删除)**

- [ ] **Step 21.4: 跑 + commit**

```bash
git commit -am "feat(frontend): KB tag filter + admin tag batch edit (M2 T21)"
```

---

## Verification

### Task 22: 端到端验收 (spec § 12 A1-A15)

**Files:**
- Create: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/MarketAcceptanceIT.java`

- [ ] **Step 22.1: 写 15 个 `@Test` 对应 A1-A15**

A1 — user 提交 → PENDING(admin list 含)
A2 — admin approve → APPROVED + reviewed_by
A3 — reject without comment → 422
A4 — reject with comment → REJECTED + 详情页可见
A5 — setOfficial(true) → 市场列表顶部 + 🏛️ 徽章(后端验证 is_official=true)
A6 — setFeaturedRank 倒序生效
A7 — setCategory → 按 category 过滤生效
A8 — 评 skill → insert / 第二次评 → update(updated_at 刷新)
A9 — 评 KB 无 access → 403 + 文案"请先访问过该知识库再评"
A10 — 编辑评论 1 次 OK / 2 次 422
A11 — pull 触发 stat(skill pull_count 在 30s 内聚合)
A12 — KB search 触发 stat(高 QPS 不锁)
A13 — admin 上公告 → 公告行置顶
A14 — 改 USER_CREATED fanout(MARKET_PULLED 副本同步)继承现有
A15 — 老库 baseline 后能正常工作(本期不能验证老库,只验证新装环境 OK)

- [ ] **Step 22.2: 跑全部**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest=MarketAcceptanceIT
```

Expected:15 tests pass。

- [ ] **Step 22.3: 全量回归**

```bash
mvn clean test -Dgpg.skip=true
```

Expected:全绿(若现有 ChatTest 等失败,立即修)。

- [ ] **Step 22.4: 启动 main app 手动跑 spec § 12 表里所有 case**

```bash
rm -rf ~/.loom/datasource
mvn spring-boot:run -pl spring-ai-loom-agent-test
# 浏览器打开 http://localhost:8080/spring/ai/loom/
# 跑 A1-A15
```

- [ ] **Step 22.5: commit**

```bash
git commit -am "test(acceptance): end-to-end A1-A15 from spec § 12 (M2 T22)"
```

---

## Out of Scope (YAGNI)

- 评分多维度(只 1-5 单维)
- 评论点赞 / 子评论
- 评论举报 / admin 审
- 跨市场聚合 dashboard
- A/B 实验灰度
- `verified` 二级官方标识(M3+)
- `sponsored` 投放(M3+)
- 多 reviewer role(M3+)

---

## Risk & Rollback

| 风险 | 触发 | 缓解 |
|---|---|---|
| 老库 baseline 后 ALTER 失败 | 已有 `flyway_schema_history` 表的项目 | spec § 13 已说明;生产前必须 `rm -rf ~/.loom/datasource` 重启 |
| AbstractMarketAdminService 设计错 | T4 测试 fail | T4 mock JdbcTemplate;不需要真实 DB |
| BatchedCounterService 数据丢失 | flush 崩溃 | WARN log + 启动装载机制;无 outbox |
| LLM 工具签名破坏 | 不应有 | spec § 7 明确不动 |
| admin 误删 fanout | 当前现状 | 已声明"删 market,user_*_id=NULL",不删 user_* 行 |

---
