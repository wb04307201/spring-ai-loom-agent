# 市场审批流复活 + admin CRUD 接线(#4)实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把"名存实亡"的市场审批流做实 —— 作者投稿进 PENDING、admin 从控制台新增(直发 APPROVED)/审批(approve/reject 带理由)/编辑/下架,技能与知识库两侧对称;同时退役 v1/v2 同路径双注册,让 v2 成为唯一赢家。

**Architecture:** 后端 service 语义回正(submit→PENDING、pull/access 恢复 APPROVED 校验、admin create→APPROVED、delete→级联);新增 2 张 `*_archive` 表支撑"REJECTED 行重投时旧行归档、主表新建 PENDING 行"(解 UNIQUE(author,name) 冲突);前端接线已写好但零调用的 `MarketAdmin.form()` create 模式 + approve/reject 按钮 + 技能编辑弹窗补字段 + 用户侧"我的发布"状态徽章/重投;v1 重复路由注册按 FU-4 外科式退役。

**Tech Stack:** Spring Boot 3.x / Spring AI 1.1.8 / H2 + Flyway(单一 V1.0 fresh-init)/ RouterFunctions / 原生 DOM + 微模板(app.js 风格,无 Vue/React)/ JUnit5 + @SpringBootTest IT。

**Spec:** `docs/superpowers/specs/2026-09-07-market-approval-flow-design.md`(执行时 spec 与 plan 一并阅读;plan 从 spec 论证)

## Global Constraints

- **local dev only**:不 push、不 merge、不开 PR。每任务独立 commit,前缀 `feat:`/`fix:`/`refactor:`/`docs:`/`test:`。
- **CRLF 仓库**:写文件用 LF 即可(git 会转 CRLF);不要手动 unix2dos 制造 churn。
- **V1.0 append-only schema(B1)**:归档表 append 进 `V1.0__init.sql` 末尾;**不新增 V1.x 增量迁移文件**。升级 = 清库重跑(`rm -rf ~/.loom/datasource` + `spring-ai-loom-agent-test/target/test-ds`)。现有 25 条测试技能丢弃(已确认)。
- **多模块 stale-JAR 陷阱**:改了库模块(`spring-ai-loom-agent` / autoconfigure / starter)后,跑 test 模块 IT 之前**必须先** `mvn clean install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure,spring-ai-loom-agent-spring-boot-starter -am -Dgpg.skip=true -DskipTests`,否则 test 模块用的是旧 JAR。
- **IT 运行方式**:默认 `mvn test -pl spring-ai-loom-agent-test` **不跑 `*IT`**(无 failsafe)。IT gate 需显式 `mvn test -pl spring-ai-loom-agent-test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false`,且从**清空的** `~/.loom/datasource` + `target/test-ds` + `target/surefire-reports` 起跑。
- **commit message 结尾**:每条 commit 都带 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。
- **请求 DTO 字段(已核实,不可改签名)**:`MarketCreateRequest(name, description, content, category)`;`MarketUpdateRequest(name, description, content, category, isOfficial, featuredRank)` —— 两者**都无 status 字段**,所以 v2 PUT 天然不能改 status(符合 spec §5)。
- **既有契约(不可破坏)**:`AbstractMarketAdminService.create()`→PENDING 是**用户投稿路径**,被 `DefaultSkillMarketServiceIT.createAppendsRowToMarketSkill`(L24-35)锁定断言 PENDING。admin 直发 APPROVED 走**新方法 `createApproved()`**(Task 2),不动 `create()`。

---

### Task 1: 归档表 schema(B1 append V1.0)

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/db/migration/V1.0__init.sql`(末尾追加)
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/MarketApprovalFlowIT.java`(新建,本任务先建骨架验证表存在)

**Interfaces:**
- Produces: 表 `market_skill_archive(id BIGINT PK, name, description, content, author, status, submitted_at, reviewed_at, reviewed_by, review_comment, archived_at)` 与 `loom_market_knowledge_archive(id VARCHAR(36) PK, username, name, description, status, submitted_at, reviewed_at, reviewed_by, review_comment, archived_at)`。Task 3/5 的重投归档逻辑写入这两张表。

- [ ] **Step 1: 写失败测试(表存在性)**

新建 `MarketApprovalFlowIT.java`,先只放一个 schema 探针测试(后续任务往里加状态机测试):

```java
package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = LoomAgentTestApplication.class)
class MarketApprovalFlowIT {

    @Autowired JdbcTemplate jdbc;

    @Test
    void archiveTablesExist() {
        Integer skillCols = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_name='MARKET_SKILL_ARCHIVE'",
            Integer.class);
        Integer kbCols = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_name='LOOM_MARKET_KNOWLEDGE_ARCHIVE'",
            Integer.class);
        assertTrue(skillCols != null && skillCols >= 11, "market_skill_archive 应已建表");
        assertTrue(kbCols != null && kbCols >= 10, "loom_market_knowledge_archive 应已建表");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports
mvn clean install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure,spring-ai-loom-agent-spring-boot-starter -am -Dgpg.skip=true -DskipTests
mvn test -pl spring-ai-loom-agent-test -Dtest='MarketApprovalFlowIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL —— `archiveTablesExist` 断言失败(列数 0 / null,表不存在)。

- [ ] **Step 3: 追加归档表 DDL 到 V1.0 末尾**

打开 `spring-ai-loom-agent/src/main/resources/db/migration/V1.0__init.sql`,在文件**最末尾**追加(注意:V1.0 已有 `market_skill`/`loom_market_knowledge` 的 is_official/featured_rank/category/created_by_kind 等后补列,归档表**不含**这些发布态列):

```sql

-- =============================================================
-- #4 市场审批流:REJECTED 行重投时旧行归档表(只增不删,供追溯)
-- 主表 UNIQUE(author/username, name) 不动;重投 = 旧行挪进 archive + 主表新建 PENDING 行(新 id)
-- 归档行保留原主键值(非自增),便于与原行对应。
-- =============================================================
CREATE TABLE market_skill_archive (
  id BIGINT PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  description TEXT,
  content TEXT NOT NULL,
  author VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  submitted_at TIMESTAMP NOT NULL,
  reviewed_at TIMESTAMP NULL,
  reviewed_by VARCHAR(64) NULL,
  review_comment TEXT NULL,
  archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE loom_market_knowledge_archive (
  id VARCHAR(36) PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  name VARCHAR(200) NOT NULL,
  description TEXT,
  status VARCHAR(20) NOT NULL,
  submitted_at TIMESTAMP,
  reviewed_at TIMESTAMP,
  reviewed_by VARCHAR(64),
  review_comment TEXT,
  archived_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

- [ ] **Step 4: 跑测试确认通过**

```bash
rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports
mvn clean install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure,spring-ai-loom-agent-spring-boot-starter -am -Dgpg.skip=true -DskipTests
mvn test -pl spring-ai-loom-agent-test -Dtest='MarketApprovalFlowIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS(1 test green)。

- [ ] **Step 5: Commit**

```bash
git add spring-ai-loom-agent/src/main/resources/db/migration/V1.0__init.sql spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/MarketApprovalFlowIT.java
git commit -m "feat(market): add market_skill_archive + loom_market_knowledge_archive tables (#4 B1)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: admin 直发 APPROVED —— 两侧 `createApproved()`

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/IMarketContentAdminService.java`(接口加方法)
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/AbstractMarketAdminService.java`(默认抛 UnsupportedOperation,镜像 create())
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/skill/DefaultSkillMarketService.java`(override)
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/knowledge/DefaultKnowledgeMarketService.java`(override)
- Test: `MarketApprovalFlowIT.java`(加 adminCreateApproved 测试)

**Interfaces:**
- Consumes: `MarketCreateRequest(name, description, content, category)`。
- Produces: `M createApproved(String adminUsername, MarketCreateRequest req)` —— skill 落 `status='APPROVED', created_by_kind='ADMIN', reviewed_at=NOW, reviewed_by=adminUsername, category=req.category`,返回含新 BIGINT id 的 MarketSkill;KB 落 `status='APPROVED', created_by_kind='ADMIN', reviewed_at=NOW, reviewed_by=adminUsername, category=req.category`,新 UUID id,返回 MarketKnowledgeRecord。Task 6 的 v2 admin POST router 改调本方法。

- [ ] **Step 1: 写失败测试**

在 `MarketApprovalFlowIT.java` 追加(注入两个 service):

```java
    @Autowired cn.wubo.spring.ai.loom.agent.skill.DefaultSkillMarketService skillSvc;
    @Autowired cn.wubo.spring.ai.loom.agent.knowledge.DefaultKnowledgeMarketService kbSvc;

    @Test
    void adminCreateApprovedSkill() {
        long id = skillSvc.createApproved("admin1", new MarketCreateRequest(
            "adm-" + System.nanoTime(), "d", "c", "cat-x")).id();
        String status = jdbc.queryForObject("SELECT status FROM market_skill WHERE id=?", String.class, id);
        String kind = jdbc.queryForObject("SELECT created_by_kind FROM market_skill WHERE id=?", String.class, id);
        String by = jdbc.queryForObject("SELECT reviewed_by FROM market_skill WHERE id=?", String.class, id);
        assertEquals("APPROVED", status);
        assertEquals("ADMIN", kind);
        assertEquals("admin1", by);
    }

    @Test
    void adminCreateApprovedKnowledge() {
        String id = kbSvc.createApproved("admin1", new MarketCreateRequest(
            "admkb-" + System.nanoTime(), "d", null, "cat-y")).id();
        String status = jdbc.queryForObject("SELECT status FROM loom_market_knowledge WHERE id=?", String.class, id);
        assertEquals("APPROVED", status);
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest='MarketApprovalFlowIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败 —— `createApproved` 方法不存在。

- [ ] **Step 3: 接口加方法 + 基类默认实现**

`IMarketContentAdminService.java`,在 `create` 声明附近加:

```java
    /** admin 直接创建 → 落 APPROVED(可信主路径,绕过 PENDING 审批)。 */
    M createApproved(String adminUsername, MarketCreateRequest req);
```

`AbstractMarketAdminService.java`,在 `create`(L114-117)后加默认实现(镜像 create 的 UnsupportedOperation 范式):

```java
    @Override
    public M createApproved(String adminUsername, MarketCreateRequest req) {
        throw new UnsupportedOperationException("createApproved must be implemented by subclass");
    }
```

- [ ] **Step 4: skill override**

`DefaultSkillMarketService.java`,在 `create()`(L110 附近)后加。先用 jdbc 查重(同 author+name 已存在则抛 422,镜像 adminCreate L329-334 的查重):

```java
    @Override
    @org.springframework.transaction.annotation.Transactional
    public MarketSkill createApproved(String adminUsername, MarketCreateRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException("name 不能为空");
        }
        if (req.content() == null || req.content().isBlank()) {
            throw new cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException("content 不能为空");
        }
        Integer dup = jdbc.queryForObject(
            "SELECT COUNT(*) FROM market_skill WHERE author=? AND name=?",
            Integer.class, adminUsername, req.name());
        if (dup != null && dup > 0) {
            throw new cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException(422,
                "已存在同名 Skill: author=" + adminUsername + " name=" + req.name());
        }
        jdbc.update(
            "INSERT INTO market_skill (name, description, content, author, status, category, " +
                "created_by_kind, reviewed_at, reviewed_by) " +
                "VALUES (?, ?, ?, ?, 'APPROVED', ?, 'ADMIN', CURRENT_TIMESTAMP, ?)",
            req.name(), req.description(), req.content(), adminUsername, req.category(), adminUsername);
        Long id = jdbc.queryForObject(
            "SELECT MAX(id) FROM market_skill WHERE author=? AND name=?",
            Long.class, adminUsername, req.name());
        return get(id);
    }
```

- [ ] **Step 5: KB override**

`DefaultKnowledgeMarketService.java`,在 `create()`(L128-135)后加(注意 KB 无 content 列,忽略 req.content()):

```java
    @Override
    @org.springframework.transaction.annotation.Transactional
    public MarketKnowledgeRecord createApproved(String adminUsername, MarketCreateRequest req) {
        String marketId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update(
            "INSERT INTO loom_market_knowledge (id, username, name, description, category, status, " +
                "created_by_kind, reviewed_at, reviewed_by) " +
                "VALUES (?, ?, ?, ?, ?, 'APPROVED', 'ADMIN', CURRENT_TIMESTAMP, ?)",
            marketId, adminUsername, req.name(), req.description(), req.category(), adminUsername);
        return getById(marketId);
    }
```

- [ ] **Step 6: 跑测试确认通过**

```bash
mvn clean install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure,spring-ai-loom-agent-spring-boot-starter -am -Dgpg.skip=true -DskipTests
rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports
mvn test -pl spring-ai-loom-agent-test -Dtest='MarketApprovalFlowIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS(3 tests green:archiveTablesExist + adminCreateApprovedSkill + adminCreateApprovedKnowledge)。

- [ ] **Step 7: Commit**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/IMarketContentAdminService.java spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/AbstractMarketAdminService.java spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/skill/DefaultSkillMarketService.java spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/knowledge/DefaultKnowledgeMarketService.java spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/MarketApprovalFlowIT.java
git commit -m "feat(market): createApproved() — admin direct APPROVED bypass for skill + KB (#4)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: submit→PENDING + REJECTED 重投归档 + pull 恢复校验(skill)

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/skill/DefaultSkillMarketService.java`(`submit` L278-315、`pull` L375-412)
- Test: `MarketApprovalFlowIT.java`(加 submit/pull/重投归档测试)

**Interfaces:**
- Consumes: Task 1 的 `market_skill_archive` 表。
- Produces: `submit()` 返回 PENDING 行;对 REJECTED 同名旧行重投 → 旧行进 archive + 主表新 PENDING 行(新 id)+ backlink 指向新 id。`pull()` 对非 APPROVED 抛 `LoomAgentRuntimeException(403, ...)`。

- [ ] **Step 1: 写失败测试**

`MarketApprovalFlowIT.java` 追加:

```java
    @Test
    void submitLandsPending() {
        long id = skillSvc.submit("alice",
            new cn.wubo.spring.ai.loom.agent.model.MarketSkillSubmitRequest(
                "sub-" + System.nanoTime(), "d", "c")).id();
        String status = jdbc.queryForObject("SELECT status FROM market_skill WHERE id=?", String.class, id);
        assertEquals("PENDING", status);
        Object reviewedBy = jdbc.queryForObject("SELECT reviewed_by FROM market_skill WHERE id=?", Object.class, id);
        assertNull(reviewedBy, "投稿未审,reviewed_by 应为 null");
    }

    @Test
    void pullRejectsNonApproved() {
        long id = skillSvc.submit("alice",
            new cn.wubo.spring.ai.loom.agent.model.MarketSkillSubmitRequest(
                "pull-" + System.nanoTime(), "d", "c")).id();
        var ex = assertThrows(cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException.class,
            () -> skillSvc.pull("bob", id));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void resubmitRejectedArchivesOldRow() {
        String name = "resub-" + System.nanoTime();
        long id1 = skillSvc.submit("alice",
            new cn.wubo.spring.ai.loom.agent.model.MarketSkillSubmitRequest(name, "d", "c1")).id();
        skillSvc.reject(id1, "admin1", "内容不合规");
        // 重投同名
        long id2 = skillSvc.submit("alice",
            new cn.wubo.spring.ai.loom.agent.model.MarketSkillSubmitRequest(name, "d", "c2")).id();
        assertNotEquals(id1, id2, "重投应新建行(新 id)");
        String s2 = jdbc.queryForObject("SELECT status FROM market_skill WHERE id=?", String.class, id2);
        assertEquals("PENDING", s2);
        Integer archived = jdbc.queryForObject(
            "SELECT COUNT(*) FROM market_skill_archive WHERE id=?", Integer.class, id1);
        assertEquals(1, archived, "旧 REJECTED 行应进 archive");
        String arcComment = jdbc.queryForObject(
            "SELECT review_comment FROM market_skill_archive WHERE id=?", String.class, id1);
        assertEquals("内容不合规", arcComment);
        Integer mainStillHasOld = jdbc.queryForObject(
            "SELECT COUNT(*) FROM market_skill WHERE id=?", Integer.class, id1);
        assertEquals(0, mainStillHasOld, "旧 id 已从主表删除");
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest='MarketApprovalFlowIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL —— submitLandsPending(当前返回 APPROVED)、pullRejectsNonApproved(当前不校验)、resubmitRejectedArchivesOldRow(当前 UPSERT 覆盖)。

- [ ] **Step 3: 改 submit()(skill)**

`DefaultSkillMarketService.submit`(L278-315)整段替换。核心:① 命中同名旧行先判 status;② REJECTED → 归档+删主表+新建;③ 非 REJECTED → UPSERT 回 PENDING;④ 全新 → INSERT PENDING;⑤ backlink 重写:

```java
    @Override
    @Transactional
    public MarketSkill submit(String username, MarketSkillSubmitRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new LoomAgentRuntimeException("name 不能为空");
        }
        if (req.content() == null || req.content().isBlank()) {
            throw new LoomAgentRuntimeException("content 不能为空");
        }
        // 查同名旧行(可能不存在)
        Long existingId = null;
        String existingStatus = null;
        try {
            existingId = jdbc.queryForObject(
                "SELECT id FROM market_skill WHERE author=? AND name=? LIMIT 1",
                Long.class, username, req.name());
            existingStatus = jdbc.queryForObject(
                "SELECT status FROM market_skill WHERE id=?", String.class, existingId);
        } catch (org.springframework.dao.EmptyResultDataAccessException ignored) {
        }
        Long marketId;
        if (existingId != null && "REJECTED".equals(existingStatus)) {
            // REJECTED 重投:旧行整行归档 → 主表删 → 新建 PENDING 行(新 id)
            jdbc.update(
                "INSERT INTO market_skill_archive (id, name, description, content, author, status, " +
                    "submitted_at, reviewed_at, reviewed_by, review_comment) " +
                    "SELECT id, name, description, content, author, status, submitted_at, reviewed_at, " +
                    "reviewed_by, review_comment FROM market_skill WHERE id=?", existingId);
            jdbc.update("DELETE FROM market_skill WHERE id=?", existingId);
            jdbc.update(
                "INSERT INTO market_skill (name, description, content, author, status, created_by_kind) " +
                    "VALUES (?, ?, ?, ?, 'PENDING', 'USER')",
                req.name(), req.description(), req.content(), username);
            marketId = jdbc.queryForObject(
                "SELECT MAX(id) FROM market_skill WHERE author=? AND name=?",
                Long.class, username, req.name());
        } else if (existingId != null) {
            // 非 REJECTED(PENDING/APPROVED)同名 → UPSERT 回 PENDING + 清审核字段
            jdbc.update(
                "UPDATE market_skill SET description=?, content=?, status='PENDING', " +
                    "reviewed_at=NULL, reviewed_by=NULL, review_comment=NULL WHERE id=?",
                req.description(), req.content(), existingId);
            marketId = existingId;
        } else {
            jdbc.update(
                "INSERT INTO market_skill (name, description, content, author, status, created_by_kind) " +
                    "VALUES (?, ?, ?, ?, 'PENDING', 'USER')",
                req.name(), req.description(), req.content(), username);
            marketId = jdbc.queryForObject(
                "SELECT MAX(id) FROM market_skill WHERE author=? AND name=?",
                Long.class, username, req.name());
        }
        // backlink 重写为新 marketId(REJECTED 重投时指向新行)
        jdbc.update(
            "UPDATE user_skill SET market_skill_id=? WHERE username=? AND name=?",
            marketId, username, req.name());
        return get(marketId);
    }
```

- [ ] **Step 4: 改 pull()(skill)**

`DefaultSkillMarketService.pull`(L375-412),在 `MarketSkill m = get(marketSkillId);`(L376)后、现有"去掉 status 校验"注释处,插入校验:

```java
        if (!MarketSkill.STATUS_APPROVED.equals(m.status())) {
            throw new LoomAgentRuntimeException(403, "该技能未通过审批,暂不可拉取(status=" + m.status() + ")");
        }
```
删掉 L377 的 `// 去掉 status='APPROVED' 校验(提交即上架)` 注释。

- [ ] **Step 5: 跑测试确认通过**

```bash
mvn clean install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure,spring-ai-loom-agent-spring-boot-starter -am -Dgpg.skip=true -DskipTests
rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports
mvn test -pl spring-ai-loom-agent-test -Dtest='MarketApprovalFlowIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS(6 tests green)。

- [ ] **Step 6: Commit**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/skill/DefaultSkillMarketService.java spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/MarketApprovalFlowIT.java
git commit -m "feat(market): skill submit→PENDING + REJECTED re-submit archives old row + pull 403 guard (#4)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: submit→PENDING + 重投归档 + pull/access 校验(KB,镜像 Task 3)

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/knowledge/DefaultKnowledgeMarketService.java`(`submit` L286-325、`pull` L367-391、`listApproved` L265-272 已 APPROVED-only 无需改)
- Test: `MarketApprovalFlowIT.java`(加 KB 侧测试)

**Interfaces:**
- Consumes: Task 1 的 `loom_market_knowledge_archive` 表;`submit(knowledgeId)` 内部用 `UserContextHolder.getCurrentUser()` + `knowledge.list(username)` 校验所有权(已有)。
- Produces: KB `submit()` 返回 PENDING;REJECTED 重投归档;`pull()` 非 APPROVED 抛 403。**注意**:KB submit 入参是 `knowledgeId`(非 name/content),测试需先建一个 user knowledge。

- [ ] **Step 1: 写失败测试**

`MarketApprovalFlowIT.java` 追加(注入 IKnowledge 建测试 KB;KB submit 依赖 UserContextHolder,测试里用 service 层重载或直接设 holder —— 采用与 `KnowledgeMarketIntegrationTest` 相同手法。先查该测试如何设置当前用户,镜像之):

```java
    @Autowired cn.wubo.spring.ai.loom.agent.knowledge.IKnowledge knowledge;

    private String newKb(String user) {
        cn.wubo.spring.ai.loom.agent.user.UserContextHolder.setCurrentUser(user);
        try {
            // 已核实 IKnowledge.insert(name, description) 为 2 参,返回 KnowledgeRecord(.id())
            return knowledge.insert("kb-" + System.nanoTime(), "desc").id();
        } finally {
            cn.wubo.spring.ai.loom.agent.user.UserContextHolder.clear();
        }
    }

    @Test
    void kbSubmitLandsPending() {
        String user = "alice";
        String kbId = newKb(user);
        cn.wubo.spring.ai.loom.agent.user.UserContextHolder.setCurrentUser(user);
        try {
            String mid = kbSvc.submit(kbId).id();
            String status = jdbc.queryForObject(
                "SELECT status FROM loom_market_knowledge WHERE id=?", String.class, mid);
            assertEquals("PENDING", status);
        } finally {
            cn.wubo.spring.ai.loom.agent.user.UserContextHolder.clear();
        }
    }

    @Test
    void kbPullRejectsNonApproved() {
        String user = "alice";
        String kbId = newKb(user);
        cn.wubo.spring.ai.loom.agent.user.UserContextHolder.setCurrentUser(user);
        String mid;
        try { mid = kbSvc.submit(kbId).id(); }
        finally { cn.wubo.spring.ai.loom.agent.user.UserContextHolder.clear(); }
        var ex = assertThrows(cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException.class,
            () -> kbSvc.pull("bob", mid));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void kbResubmitRejectedArchivesOldRow() {
        String user = "alice";
        String kbId = newKb(user);
        cn.wubo.spring.ai.loom.agent.user.UserContextHolder.setCurrentUser(user);
        try {
            String mid1 = kbSvc.submit(kbId).id();
            kbSvc.reject(mid1, "admin1", "不合规");
            String mid2 = kbSvc.submit(kbId).id();   // 同 KB(同 name)重投
            assertNotEquals(mid1, mid2);
            Integer archived = jdbc.queryForObject(
                "SELECT COUNT(*) FROM loom_market_knowledge_archive WHERE id=?", Integer.class, mid1);
            assertEquals(1, archived);
        } finally {
            cn.wubo.spring.ai.loom.agent.user.UserContextHolder.clear();
        }
    }

    @Test
    void kbResubmitApprovedKeepsApprovedNoDemotion() {
        // T3 Ruling 的 KB 镜像:spec §2 APPROVED→PENDING 禁止 → 重投 APPROVED 行 = 同 id 就地更新 description
        String user = "alice";
        String kbId = newKb(user);
        cn.wubo.spring.ai.loom.agent.user.UserContextHolder.setCurrentUser(user);
        try {
            String mid1 = kbSvc.submit(kbId).id();
            kbSvc.approve(mid1, "admin1");
            String mid2 = kbSvc.submit(kbId).id();
            assertEquals(mid1, mid2, "APPROVED 重投 = 同 id 就地更新");
            String status = jdbc.queryForObject(
                "SELECT status FROM loom_market_knowledge WHERE id=?", String.class, mid2);
            assertEquals("APPROVED", status);
            String reviewedBy = jdbc.queryForObject(
                "SELECT reviewed_by FROM loom_market_knowledge WHERE id=?", String.class, mid2);
            assertEquals("admin1", reviewedBy, "审核字段不得被清空");
        } finally {
            cn.wubo.spring.ai.loom.agent.user.UserContextHolder.clear();
        }
    }
```

> 实施提示:`IKnowledge.insert` 与 `UserContextHolder` 的确切签名以现有 `KnowledgeMarketIntegrationTest`(L197-202 一带)为准;若 insert 签名不同,镜像该测试的建 KB 手法,不要臆造。

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest='MarketApprovalFlowIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL(kbSubmitLandsPending 返回 APPROVED、kbPullRejectsNonApproved 不校验、kbResubmit 未归档)。

- [ ] **Step 3: 改 KB submit()**

`DefaultKnowledgeMarketService.submit`(L286-325),把 UPSERT 段(L299-323)替换为镜像 Task 3 Step 3 的三分支(REJECTED 归档+删+新建 / 非 REJECTED UPSERT 回 PENDING / 全新 INSERT PENDING)。KB id 是 UUID:

```java
        // 查同名旧行
        String existingId = null;
        String existingStatus = null;
        try {
            existingId = jdbcTemplate.queryForObject(
                "SELECT id FROM loom_market_knowledge WHERE username=? AND name=? LIMIT 1",
                String.class, username, kb.name());
            existingStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM loom_market_knowledge WHERE id=?", String.class, existingId);
        } catch (EmptyResultDataAccessException ignored) {
        }
        String marketId;
        if (existingId != null && "REJECTED".equals(existingStatus)) {
            jdbcTemplate.update(
                "INSERT INTO loom_market_knowledge_archive (id, username, name, description, status, " +
                    "submitted_at, reviewed_at, reviewed_by, review_comment) " +
                    "SELECT id, username, name, description, status, submitted_at, reviewed_at, " +
                    "reviewed_by, review_comment FROM loom_market_knowledge WHERE id=?", existingId);
            jdbcTemplate.update("DELETE FROM loom_market_knowledge WHERE id=?", existingId);
            marketId = UUID.randomUUID().toString();
            jdbcTemplate.update(
                "INSERT INTO loom_market_knowledge (id, username, name, description, status, created_by_kind) " +
                    "VALUES (?, ?, ?, ?, 'PENDING', 'USER')",
                marketId, username, kb.name(), kb.description());
        } else if (existingId != null) {
            // 非 REJECTED 同名行(PENDING/APPROVED)→ 仅更新内容,状态与审核字段不动
            // (spec §2: APPROVED→PENDING 禁止;T3 Ruling 已定,KB 镜像同语义)
            jdbcTemplate.update(
                "UPDATE loom_market_knowledge SET description=? WHERE id=?",
                kb.description(), existingId);
            marketId = existingId;
        } else {
            marketId = UUID.randomUUID().toString();
            jdbcTemplate.update(
                "INSERT INTO loom_market_knowledge (id, username, name, description, status, created_by_kind) " +
                    "VALUES (?, ?, ?, ?, 'PENDING', 'USER')",
                marketId, username, kb.name(), kb.description());
        }
        return getById(marketId);
```

- [ ] **Step 4: 改 KB pull()(恢复 APPROVED 校验)**

`DefaultKnowledgeMarketService.pull`(L367-369),把"去掉 status 校验"注释处替换为:

```java
        if (!cn.wubo.spring.ai.loom.agent.model.MarketKnowledgeRecord.STATUS_APPROVED.equals(mk.status())) {
            throw new LoomAgentRuntimeException(403, "该知识库未通过审批,暂不可拉取(status=" + mk.status() + ")");
        }
```
(若 `MarketKnowledgeRecord.STATUS_APPROVED` 常量名不同,用字面量 `"APPROVED"` —— 已核实 L50 存在该常量。)KB `access()`(L407-428)**无需加校验**:它只对已订阅(loom_user_knowledge 有行)的用户自增 access_count,而订阅只能经 pull() 产生,pull() 已 APPROVED-gated → access 天然隔离,不加冗余校验。

- [ ] **Step 5: 跑测试确认通过**

```bash
mvn clean install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure,spring-ai-loom-agent-spring-boot-starter -am -Dgpg.skip=true -DskipTests
rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports
mvn test -pl spring-ai-loom-agent-test -Dtest='MarketApprovalFlowIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS(9 tests green)。

- [ ] **Step 6: 翻转既有 KB 测试断言**

`spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/knowledge/KnowledgeMarketIntegrationTest.java`:
- L199-202:`submitted.status()` 断言 `APPROVED` → 改 `PENDING`;`reviewedBy()` 断言 USER_A → 改为 `null`(投稿未审);在 pull/订阅步骤前补 `marketService.approve(submitted.id(), "admin1")`。
- L241 / L252 注释"用户提交直接 APPROVED,无需审批流" → 改为"用户提交进 PENDING,admin approve 后 APPROVED"。
- L253-256、L272、L287-290、L301 等凡依赖 submit 后即可 pull/订阅的腿,补 approve 步骤。
- 逐处改完后跑:`mvn test -pl spring-ai-loom-agent-test -Dtest='KnowledgeMarketIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false` → PASS。

- [ ] **Step 7: Commit**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/knowledge/DefaultKnowledgeMarketService.java spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/MarketApprovalFlowIT.java spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/knowledge/KnowledgeMarketIntegrationTest.java
git commit -m "feat(market): KB submit→PENDING + REJECTED re-submit archive + pull 403 guard; flip KB IT assertions (#4)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: admin DELETE 级联(模板方法 cascadeCleanup)

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/AbstractMarketAdminService.java`(`delete` L273-276 改模板)
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/skill/DefaultSkillMarketService.java`(override cascadeCleanup)
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/knowledge/DefaultKnowledgeMarketService.java`(override cascadeCleanup)
- Test: `MarketApprovalFlowIT.java`(加级联删除测试)

**Interfaces:**
- Produces: `protected abstract void cascadeCleanup(K id)`(基类抽象钩子);`delete(K id)` 改为先 `cascadeCleanup(id)` 再 DELETE 主表。skill 钩子清 `user_skill`+`role_skill`(market_skill_id=id);KB 钩子清 `loom_user_knowledge`+`loom_role_knowledge`(market_knowledge_id=id)。

- [ ] **Step 1: 写失败测试**

`MarketApprovalFlowIT.java` 追加:

```java
    @Test
    void adminDeleteCascadesSkillRefs() {
        long id = skillSvc.createApproved("admin1", new MarketCreateRequest(
            "del-" + System.nanoTime(), "d", "c", null)).id();
        // 造一个 user_skill 引用该 market_skill_id
        jdbc.update("INSERT INTO user_skill (username, name, description, content, source, market_skill_id, default_loaded, locked) " +
            "VALUES ('bob', 'del-ref', 'd', 'c', 'MARKET_PULLED', ?, TRUE, FALSE)", id);
        skillSvc.delete(id);
        Integer remaining = jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_skill WHERE market_skill_id=?", Integer.class, id);
        assertEquals(0, remaining, "delete 应级联清 user_skill 引用");
        Integer main = jdbc.queryForObject("SELECT COUNT(*) FROM market_skill WHERE id=?", Integer.class, id);
        assertEquals(0, main);
    }

    @Test
    void adminDeleteCascadesKbRefs() {
        String id = kbSvc.createApproved("admin1", new MarketCreateRequest(
            "delkb-" + System.nanoTime(), "d", null, null)).id();
        jdbc.update("INSERT INTO loom_user_knowledge (username, market_knowledge_id, source, locked) " +
            "VALUES ('bob', ?, 'MARKET_PULLED', FALSE)", id);
        kbSvc.delete(id);
        Integer remaining = jdbc.queryForObject(
            "SELECT COUNT(*) FROM loom_user_knowledge WHERE market_knowledge_id=?", Integer.class, id);
        assertEquals(0, remaining);
    }
```

> 实施提示:`user_skill` / `loom_user_knowledge` 的确切列以 V1.0 schema 为准(若有 NOT NULL 列遗漏,补上)。先 `grep -nE "CREATE TABLE user_skill|CREATE TABLE loom_user_knowledge" V1.0__init.sql` 核对列后再写 INSERT。

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest='MarketApprovalFlowIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL —— delete 当前裸删,remaining 引用仍为 1。

- [ ] **Step 3: 基类 delete 改模板 + 抽象钩子**

`AbstractMarketAdminService.java`,把 `delete`(L273-276)替换为:

```java
    /** 子类实现:删主表前清理 user_*/role_* 引用(级联)。 */
    protected abstract void cascadeCleanup(K id);

    @Override
    public void delete(K id) {
        cascadeCleanup(id);
        jdbc.update("DELETE FROM " + tableName() + " WHERE id=?", id);
    }
```

- [ ] **Step 4: skill override cascadeCleanup**

`DefaultSkillMarketService.java` 加(镜像现有 adminDelete L364-366 的级联 SQL):

```java
    @Override
    protected void cascadeCleanup(Long id) {
        jdbc.update("DELETE FROM user_skill WHERE market_skill_id=?", id);
        jdbc.update("DELETE FROM role_skill WHERE market_skill_id=?", id);
    }
```

- [ ] **Step 5: KB override cascadeCleanup**

`DefaultKnowledgeMarketService.java` 加(镜像 withdraw L346-347):

```java
    @Override
    protected void cascadeCleanup(String id) {
        jdbcTemplate.update("DELETE FROM loom_user_knowledge WHERE market_knowledge_id=?", id);
        jdbcTemplate.update("DELETE FROM loom_role_knowledge WHERE market_knowledge_id=?", id);
    }
```

> 注:`AbstractMarketAdminService` 的 jdbc 字段名(skill 子类用 `jdbc`,KB 子类历史用 `jdbcTemplate`)以各文件现有引用为准;若基类字段是 `jdbc`,KB override 内也用 `jdbc`(或 KB 类已有的 jdbcTemplate 别名)。实施时核对 KB 类顶部注入字段名。

- [ ] **Step 6: 跑测试确认通过**

```bash
mvn clean install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure,spring-ai-loom-agent-spring-boot-starter -am -Dgpg.skip=true -DskipTests
rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports
mvn test -pl spring-ai-loom-agent-test -Dtest='MarketApprovalFlowIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS(11 tests green)。

- [ ] **Step 7: Commit**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/AbstractMarketAdminService.java spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/skill/DefaultSkillMarketService.java spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/knowledge/DefaultKnowledgeMarketService.java spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/MarketApprovalFlowIT.java
git commit -m "feat(market): admin delete cascades user_*/role_* refs via cascadeCleanup hook (#4)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 6: 路由收敛 —— 退役 v1 双注册 + v2 admin POST 改 createApproved

**Files:**
- Modify: `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java`
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/SkillListDispatchIT.java`(扩展路由分发断言)+ 新 `MarketRouteDispatchIT.java`(可选,镜像范式)

**Interfaces:**
- Consumes: Task 2 `createApproved()`。
- Produces: 每条市场路径只剩一个 handler(v2)。v2 admin POST `/admin/market-skills`(L2606-2629)+ `/admin/market-knowledge`(L3413)改调 `svc.createApproved(username, body)`(APPROVED)。

- [ ] **Step 1: 先 grep 前端所有市场 fetch 腿(防断前端)**

```bash
grep -nE "market-skills|market-knowledge|api/knowledge-market|api/knowledge/.*submit" spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/*.js
```
记录每条前端调用的路径 + 方法。**已知事实**(调研):app.js 仍调 v1 `pull`(L64)、`my-submitted`(L66)、`withdraw`(L67)、`/api/knowledge/{id}/submit`(submitToMarket L65)→ **这 4 条 v1 腿保留注册**(其底层 service 语义已被 Task 3/4 改为 PENDING/校验,自动受益)。

- [ ] **Step 2: 写失败测试(admin POST → createApproved 唯一赢家)**

**关键**:现状 v1 `adminCreate`(声明在前)胜出,POST 已返回 APPROVED 但 `created_by_kind='USER'`(adminCreate INSERT 不含该列→走 DEFAULT 'USER');v2 `create()` 则落 PENDING。三者唯一可靠区分信号 = **`created_by_kind`**:v1 adminCreate→'USER'、v2 create→PENDING、目标 createApproved→**'ADMIN'**。故测试断言 created_by_kind='ADMIN'(响应 JSON 不含该字段,需查 DB —— SkillListDispatchIT 加 `@Autowired JdbcTemplate jdbc;`)。

`SkillListDispatchIT.java` 追加(harness 已核实:`TestRestTemplate` + `authHeaders` cookie,`@BeforeEach loginAsAdmin()` 以 wb04307201/123456 登录,L60-78):

```java
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("POST /admin/market-skills → 200 + APPROVED + created_by_kind=ADMIN (v2 createApproved 唯一赢家)")
    void adminPostSkillCreatesApproved() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, authHeaders.getFirst(HttpHeaders.COOKIE));
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        String name = "disp-adm-" + System.nanoTime();
        HttpEntity<String> req = new HttpEntity<>(
                "{\"name\":\"" + name + "\",\"description\":\"d\",\"content\":\"c\",\"category\":\"cat\"}", h);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/spring/ai/loom/admin/market-skills", HttpMethod.POST, req, String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "admin create must return 200; got " + resp.getStatusCode() + " body=" + resp.getBody());
        JsonNode body = MAPPER.readTree(resp.getBody());
        assertEquals("APPROVED", body.get("status").asText(),
                "admin create must land APPROVED");
        long id = body.get("id").asLong();
        String kind = jdbc.queryForObject(
                "SELECT created_by_kind FROM market_skill WHERE id=?", String.class, id);
        assertEquals("ADMIN", kind,
                "must be createApproved (created_by_kind=ADMIN), not v1 adminCreate (USER) nor v2 create (PENDING)");
    }
```

同时执行 Step 5 注记的 SkillListDispatchIT 改造(删 `v1Router` 字段 + 测试 3;测试 2 DisplayName 改 "(v2, ARRAY shape kept)")—— 放在同一 commit。

- [ ] **Step 3: 跑测试确认失败**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest='SkillListDispatchIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL —— 当前 v2 admin POST 调 `svc.create()`→PENDING(或 v1 adminCreate 抢先,语义不确定)。

- [ ] **Step 4: 修 v2 handler 语义(3 处,已核实)**

① skill v2 admin POST(L2618):`svc.create(username, body)` → `svc.createApproved(username, body)`。
② KB v2 admin POST(L3425,已核实该 bean 内 service 变量名为 `svc` 非 kbSvc):`svc.create(username, body)` → `svc.createApproved(username, body)`。
③ **skill v2 user POST `/user/market-skills`(L3135-3155)当前调 `svc.create()` —— 必须改调 `submit()`**(create() 无 REJECTED 重投归档、无 user_skill backlink 重写,直接接管 v1 会语义回退):

```java
                try {
                    return ServerResponse.ok().body(svc.submit(username,
                        new cn.wubo.spring.ai.loom.agent.model.MarketSkillSubmitRequest(
                            body.name(), body.description(), body.content())));
                } catch (...) // catch 块原样保留
```
④ **v2 public router 补注册 `GET /user/market-skills`**(已核实 v2 缺该腿,grep listMySubmitted 只在 v1 L2414/L4570;app.js L46/L575 依赖它且要求裸 ARRAY 形态):

```java
            // #4: v1 退役迁入 — listMySubmitted,返回裸 ARRAY(与 v1 形态一致,前端依赖)
            builder.GET("spring/ai/loom/user/market-skills", request -> {
                String username = UserContextHolder.getCurrentUser();
                return ServerResponse.ok().body(svc.listMySubmitted(username));
            });
```
(放入 v2 `loomAgentMarketSkillPublicRouter`,handler 里的 svc 变量名以该 bean 现有注入名为准。)

- [ ] **Step 5: 退役 v1 重复注册(保留前端仍调的腿)**

逐个删除以下 v1 注册,**每处留注释** `// #4 外科式退役: v2 唯一赢家(见 spec §5)`:

| 文件位置 | 动作 |
|---|---|
| `loomAgentSkillMarketAdminRouter` bean(L2446-2520 整 bean:POST L2455 / PUT L2477 / DELETE L2499) | **整 bean 删除**(v2 admin router L2573-3021 全覆盖)。同时删该 `@Bean` 方法签名 + javadoc |
| `loomAgentSkillMarketRouter` bean(L2357-2440 整 bean:`GET /market-skills/{id}` L2360、`POST pull` L2377、`POST /user/market-skills` L2394、`GET /user/market-skills` L2414、`DELETE /user/market-skills/{id}` L2419) | **整 bean 删除** —— Step 4 完成后 v2 public router(L3052-3330)已全覆盖:`GET {id}`(L3116)、`pull`(L3191)、user POST(改调 submit)、user GET(Step 4④ 补)、user DELETE(L3161,已核实同调 withdraw(username,id)) |
| `loomAgentKnowledgeMarketAdminRouter` bean(L4580-4607:GET L4586 / DELETE L4593) | **整 bean 删除**(v2 KB admin router L3374-3793 覆盖;级联语义由 Task 5 cascadeCleanup 补齐) |
| KB v1 `/api/knowledge-market` 系(L4492-4574)的 **list 腿** | 删(app.js L61 注释明确 v1 list 已不被 SPA 调用)。**pull/my-submitted/withdraw + `/api/knowledge/{id}/submit` 腿保留**(app.js L64-67 仍调;底层 service 语义已被 Task 4 改为 PENDING/校验,自动受益) |
| KB v2 `POST /user/market-knowledge`(L3913-3933,调 `kbSvc.create`) | **退役此腿**(前端零调用;body 形态 name/description 与聊天侧 submit(knowledgeId) 不符)→ 删除注册 + 记 follow-up。聊天侧投稿走保留的 v1 `/api/knowledge/{id}/submit`(→submit()→PENDING) |

**v1 service 方法善后(ADR-T03 shim 政策)**:整 bean 删除后 `ISkillMarketService.adminCreate/adminUpdate/adminDelete`(DefaultSkillMarketService L321/348/363)**零调用方** → 接口 + 实现标 `@Deprecated`(javadoc 注明"v1 路由已退役,保留 1 个 minor 版本,由 createApproved/update/delete 取代"),**不删除**(库公开接口,外部消费者可能调用)。`MarketSkillUpsertRequest` 同标 @Deprecated。

**SkillListDispatchIT 同步改造**(已核实它 `@Qualifier("loomAgentSkillMarketRouter")` 注入 v1 bean,L53):v1 bean 删除后该 IT 编译失败 → 删除 `v1Router` 字段 + 测试 3(`v1RouterNoLongerHoldsPublicListPath`,其 FU-4 使命已被本任务超越);测试 2 的 DisplayName "(v1-only, kept)" 改为 "(v2, ARRAY shape kept)";测试 1/2/4 保留(HTTP 层断言仍有效)。

- [ ] **Step 5b: T5 carry-forward —— base delete() 加 @Transactional**

T5 评审裁定:`AbstractMarketAdminService.delete()`(现 cascadeCleanup + DELETE 多语句)缺 `@Transactional`;T6 退役 v1 `adminDelete`(原本 @Transactional)后,v2 `delete()` 成为 admin 删除**唯一路径**,原子性缺口此刻才真正生效。故在 T6 顺手给 base `delete()` 加 `@Transactional`(确认/补 import `org.springframework.transaction.annotation.Transactional`)。此文件加入 T6 commit 的 staging。

- [ ] **Step 6: 路由回归 gate(全 IT 跑,但只修路由因失败;submit→PENDING 语义失败留给 T9)**

```bash
mvn clean install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure,spring-ai-loom-agent-spring-boot-starter -am -Dgpg.skip=true -DskipTests
rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports
mvn test -pl spring-ai-loom-agent-test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false
```

**T6 完成判据(controller ruling,见 ledger)**:
- **必须绿**:SkillListDispatchIT(含新 `adminPostSkillCreatesApproved` + 既有 list-shape 测试,且 v1Router 删除后能编译)、MarketApprovalFlowIT 13/13、以及所有**路由型** IT(SkillTagRoutesIT / SkillAdminMissingIdReturns404IT 等)无 404 / wrong-handler / dispatch 歧义。
- **逐个分类每个失败**:① 路由因(404、调错 handler、bean 删除导致 DI/编译断、dispatch 歧义)→ **T6 必须修**;② submit→PENDING 语义因(MarketAcceptanceIT 的 A-腿、各 *ReviewServiceIT 依赖"提交即上架")→ **不在 T6 修,报告里标注"T9-pending flip"**,T9 负责翻转。
- T6 在"零路由因失败"时即算完成,即便 MarketAcceptanceIT/review IT 因语义仍红。
- **背景**:T3/T4 从未跑过全量 gate,MarketAcceptanceIT/review IT 可能自 T3 起就红(submit→PENDING),与本任务路由改动无关 —— 报告里务必区分。

- [ ] **Step 7: Commit**

```bash
git add spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/market/AbstractMarketAdminService.java spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/SkillListDispatchIT.java
git commit -m "refactor(market): retire v1 duplicate route registrations, v2 sole winner; admin POST→createApproved; base delete @Transactional (#4)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 7: admin 前端 —— 新增按钮 + approve/reject + 技能编辑补字段

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/skill/DefaultSkillMarketService.java`(`update()` 补 isOfficial/featuredRank —— Step 0)
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/market-admin.js`(加共享 `approve()`/`reject()`)
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/market-skills.js`(新增按钮、approve/reject 按钮、编辑弹窗补字段)
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/market-skills.html`(工具栏加新增按钮、删失真文案、编辑弹窗加 category/official/rank 字段)
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/knowledge-market.js` + `knowledge-market.html`(新增按钮、approve/reject 按钮;KB 编辑弹窗已有 category/official/rank)
- Modify: i18n `zh-CN.json` / `en-US.json`(启用已备键 + 新增 reject.commentRequired)
- Test: `MarketApprovalFlowIT.java`(加 skillUpdatePersistsOfficialRank 测试)

**Interfaces:**
- Consumes: v2 admin 端点 `POST /admin/market-{kind}s`(createApproved)、`POST /admin/market-{kind}s/{id}/approve`、`POST .../reject`(body `RejectBody{comment}`,已核实)、`PUT /admin/market-{kind}s/{id}`(MarketUpdateRequest)、`MarketAdmin.form(kind,'create',null)`。
- Produces: `window.MarketAdmin.approve(kind, id)` / `window.MarketAdmin.reject(kind, id, comment)` 共享方法(镜像 `updateMarketTags` L745-778 范式:401/403 跳登录、错误抛出);`DefaultSkillMarketService.update()` 持久化 isOfficial/featuredRank(镜像 KB update)。

- [ ] **Step 0: 后端 —— skill update() 补 isOfficial/featuredRank(镜像 KB,TDD)**

预扫描发现:`DefaultSkillMarketService.update()`(L122-145)只动态 SET name/description/content/category,**忽略 isOfficial/featuredRank**;而 KB `update()`(DefaultKnowledgeMarketService L158-165)处理这两个字段。技能编辑弹窗要镜像 KB 持久化 official/rank,必须先补后端。

先写失败测试(`MarketApprovalFlowIT.java` 追加):
```java
    @Test
    void skillUpdatePersistsOfficialRank() {
        long id = skillSvc.createApproved("admin1", new MarketCreateRequest(
            "upd-" + System.nanoTime(), "d", "c", null)).id();
        skillSvc.update(id, new MarketUpdateRequest(null, null, null, null, true, 7));
        Boolean official = jdbc.queryForObject("SELECT is_official FROM market_skill WHERE id=?", Boolean.class, id);
        Integer rank = jdbc.queryForObject("SELECT featured_rank FROM market_skill WHERE id=?", Integer.class, id);
        assertEquals(true, official);
        assertEquals(7, rank);
    }
```
跑 `mvn test -pl spring-ai-loom-agent-test -Dtest='MarketApprovalFlowIT#skillUpdatePersistsOfficialRank' -Dsurefire.failIfNoSpecifiedTests=false`(先 `mvn clean install` 库三模块)→ FAIL(official 仍 false / rank 仍 null)。

改 `DefaultSkillMarketService.update()`:在 category 分支后、`sql.append(" WHERE id=?")` 前插入(镜像 KB update L158-165):
```java
        if (req.isOfficial() != null) {
            sql.append(", is_official=?");
            args.add(req.isOfficial());
        }
        if (req.featuredRank() != null) {
            sql.append(", featured_rank=?");
            args.add(req.featuredRank());
        }
```
重跑 → PASS。

- [ ] **Step 1: market-admin.js 加 approve/reject 共享方法**

在 `updateMarketTags`(L745-778)附近加(镜像其 fetch + 错误处理范式):

```javascript
  async function approve(kind, id) {
    if (kind !== "SKILL" && kind !== "KNOWLEDGE") {
      throw new Error("MarketAdmin.approve: unknown kind " + kind);
    }
    const url = ADMIN_API[kind] + "/" + encodeURIComponent(id) + "/approve";
    const resp = await fetch(url, {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
    });
    if (resp.status === 401 || resp.status === 403) {
      window.location.replace("/spring/ai/loom/index.html");
      return null;
    }
    if (!resp.ok) {
      let body = ""; try { body = await resp.text(); } catch (_) {}
      throw new Error("MarketAdmin.approve failed: HTTP " + resp.status + (body ? " — " + body : ""));
    }
    return resp.json();
  }

  async function reject(kind, id, comment) {
    if (kind !== "SKILL" && kind !== "KNOWLEDGE") {
      throw new Error("MarketAdmin.reject: unknown kind " + kind);
    }
    if (!comment || !comment.trim()) {
      throw new Error("REJECT_COMMENT_REQUIRED");
    }
    const url = ADMIN_API[kind] + "/" + encodeURIComponent(id) + "/reject";
    const resp = await fetch(url, {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
      body: JSON.stringify({ comment: comment.trim() }),
    });
    if (resp.status === 401 || resp.status === 403) {
      window.location.replace("/spring/ai/loom/index.html");
      return null;
    }
    if (!resp.ok) {
      let body = ""; try { body = await resp.text(); } catch (_) {}
      throw new Error("MarketAdmin.reject failed: HTTP " + resp.status + (body ? " — " + body : ""));
    }
    return resp.json();
  }
```
在文件末尾 `window.MarketAdmin = {...}`(L780-794)导出对象里加 `approve, reject,`。

> reject body 已核实:两侧 v2 reject handler(skill L2708 / KB L3507)解析 `cn.wubo.spring.ai.loom.agent.market.RejectBody(String comment)` → body 发 `{"comment":"..."}` 正确;handler 层已有"comment 空 → 400"防线,前端拦截是第二道保险。

- [ ] **Step 2: market-skills.html 工具栏加新增按钮 + 删失真文案**

工具栏(html L78-80,刷新按钮旁)加:
```html
<button id="create-skill-btn" class="primary-btn">+ 新增技能</button>
```
删除/改写失真文案:L50「(不再新建)」→「(admin 可新增/审批/编辑/下架)」;L70-73「不在控制台新建技能」整段删除;L89 编辑弹窗标题注释「(仅编辑现有技能,不再用于新建)」→「(编辑技能)」;L147「去掉审批拒绝备注 modal」→ 保留(本任务要加 reject comment modal)。
编辑弹窗(html L100-137)在 content 字段后**补 3 字段**(镜像 KB 弹窗 L122-146):
```html
<div class="form-group"><label>分类</label><input type="text" id="edit-category" class="form-input"/></div>
<div class="form-group"><label>精选排序</label><input type="number" id="edit-featured-rank" class="form-input" min="0" step="1"/></div>
<div class="form-group"><label><input type="checkbox" id="edit-official"/> 标记为官方</label></div>
```

- [ ] **Step 3: market-skills.js 接线新增 + approve/reject + 编辑补字段**

- 顶部事件绑定区(原 js L550 注释「去掉 create-skill-btn」处)恢复:
```javascript
  document.getElementById("create-skill-btn").addEventListener("click", async () => {
    const result = await MarketAdmin.form("SKILL", "create", null);
    if (result) { await loadList(); }
  });
```
> `MarketAdmin.form` 的 create 模式内部 POST 到 `ADMIN_API.SKILL`(L177-180),Task 6 已让该端点→APPROVED。form() 的 SKILL 分支当前不渲染 category 字段(L191 kbExtraFields 仅 KNOWLEDGE)—— 实施时给 SKILL 也加 category 输入(改 market-admin.js form() 的 contentField/kbExtraFields 逻辑:category 对 SKILL+KNOWLEDGE 都渲染,official/rank 仅 KNOWLEDGE 或都渲染 —— 按 spec §6.1「SKILL 表单含 category」)。
- 操作列(renderTable L206-210)按 status 加 approve/reject 按钮:PENDING 行显示「通过」「拒绝」:
```javascript
 const status = String(m.status || "").toUpperCase();
 const approvalBtns = status === "PENDING"
   ? `<button class="primary-btn approve-btn btn-sm" data-id="${m.id}" style="padding:4px 10px;font-size:12px;margin-right:4px;">通过</button>` +
     `<button class="delete-btn reject-btn btn-sm" data-id="${m.id}" style="margin-right:4px;">拒绝</button>`
   : "";
```
插入到操作列 td 开头。bindRowActions(L222+)加:
```javascript
 tableContainer.querySelectorAll(".approve-btn").forEach((btn) =>
   btn.addEventListener("click", async () => {
     if (!confirm("确认通过该技能?")) return;
     try { await MarketAdmin.approve("SKILL", parseInt(btn.getAttribute("data-id"))); await loadList(); }
     catch (e) { alert("通过失败: " + e.message); }
   }));
 tableContainer.querySelectorAll(".reject-btn").forEach((btn) =>
   btn.addEventListener("click", async () => {
     const comment = prompt("拒绝理由(必填):");
     if (comment === null) return;
     if (!comment.trim()) { alert("拒绝理由不能为空"); return; }
     try { await MarketAdmin.reject("SKILL", parseInt(btn.getAttribute("data-id")), comment); await loadList(); }
     catch (e) { alert("拒绝失败: " + e.message); }
   }));
```
- openEdit/saveEdit(js L471-521):openEdit 时把 `m.category`/`m.featuredRank`/`m.isOfficial` 回填到新加的 3 个输入框;saveEdit 的 PUT body(js L502)从 `{name,description,content,status:"APPROVED"}` 改为 `{name:undefined(禁用不改), description, content, category, isOfficial, featuredRank}`(**去掉 status** —— v2 PUT 不吃 status,状态只走 approve/reject)。

- [ ] **Step 4: knowledge-market.html/js 对称接线**

镜像 Step 2-3:工具栏加 `+ 新增知识库` 按钮(`MarketAdmin.form("KNOWLEDGE","create",null)`);PENDING 行加 approve/reject 按钮(`MarketAdmin.approve/reject("KNOWLEDGE", id)`,KB id 是 String 不 parseInt);删失真文案(html L51/72-74/90)。KB 编辑弹窗已有 category/official/rank(L122-146),saveEdit 确认 PUT body 不含 status。

- [ ] **Step 5: i18n**

`zh-CN.json` / `en-US.json`:确认 `market.admin.action.approve/reject/setOfficial/setFeatured` 已存在(调研:zh L13-16 / en L13-16);新增:
```json
"market.admin.reject.commentRequired": "拒绝理由不能为空",
"market.admin.create.skill": "+ 新增技能",
"market.admin.create.knowledge": "+ 新增知识库"
```
(en 对应英文)。若页面用硬编码中文(现有 admin 页多为硬编码),i18n 键仅作补充,不强制全替换 —— 与现有页面风格一致即可。

- [ ] **Step 6: 构建 + Chrome 手工验证**

```bash
mvn clean install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure,spring-ai-loom-agent-spring-boot-starter -am -Dgpg.skip=true -DskipTests
mvn spring-boot:run -pl spring-ai-loom-agent-test -Dgpg.skip=true
```
Chrome(登录 wb04307201/123456)验证:① admin 技能市场页「+ 新增技能」→ 填表保存 → 列表即出现 APPROVED 行;② 造一条 PENDING(用另一普通用户投稿或直插)→「通过」→ 变 APPROVED;③「拒绝」→ 弹理由 → 填 → 变 REJECTED 显示理由;④ 空理由被拦截;⑤ 技能编辑弹窗能改 category/official/rank 并回显;⑥ KB 页同样验证。用 `mcp__chrome-devtools__evaluate_script` 抓 `document.querySelectorAll('tbody tr').length` + console 错误数确认。

- [ ] **Step 7: Commit**

```bash
git add spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/market-admin.js spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/market-skills.js spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/market-skills.html spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/knowledge-market.js spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/knowledge-market.html spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/i18n/zh-CN.json spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/i18n/en-US.json
git commit -m "feat(frontend): admin market new/approve/reject buttons + skill edit category/official/rank fields (#4)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

> i18n 文件确切路径以 `grep -rl "market.admin.action.approve" spring-ai-loom-agent/src/main/resources` 结果为准。

---

### Task 8: 用户前端 —— "我的发布"状态徽章 + 拒绝理由 + 重新投稿

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js`(技能"我的发布"tab + KB 共享 tab)
- Modify: i18n `zh-CN.json`/`en-US.json`(status.pending/approved/rejected、resubmit)

**Interfaces:**
- Consumes: v2 `GET /user/market-skills`(listMySubmitted,返回含 status/reviewComment 的 MarketSkill)、`POST /user/market-skills`(submit→PENDING)、KB `GET /api/knowledge-market/my-submitted` + `POST /api/knowledge/{id}/submit`(保留的 v1 腿,语义已 PENDING)。
- Produces: 用户侧 UI 状态可视化。无后端接口变更。

- [ ] **Step 1: 定位"我的发布"渲染**

```bash
grep -nE "listMySubmitted|我的发布|mySubmitted|_statusLabel|审核中|已通过|已拒绝|REJECTED|PENDING" spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js
```
Read 命中区段(调研提示:技能"我的发布"在 `_showSubmitForm/handleSubmit` L5073-5123 附近;`_statusLabel` 已有 PENDING/APPROVED/REJECTED 映射 —— spec L331 提及)。确认现有渲染结构后再改。

- [ ] **Step 2: 写/改状态徽章 + 拒绝理由 + 重投按钮**

在"我的发布"每行渲染处:① 用 `_statusLabel(status)` 显示徽章(审核中黄/已通过绿/已拒绝红);② status===REJECTED 时追加显示 `reviewComment`(拒绝理由,灰色小字)+「重新投稿」按钮;③「重新投稿」复用现有 `_showSubmitForm`(预填 name/description/content)→ submit → 后端 Task 3 自动归档旧行 + 新建 PENDING 行。投稿成功 toast 文案确认为"已提交,等待管理员审批"(调研:app.js L4963/5080/5116 已有此文案,核对一致即可)。

```javascript
// 伪结构 —— 嵌入现有"我的发布"行渲染
const badge = `<span class="status-badge status-${String(m.status).toLowerCase()}">${_statusLabel(m.status)}</span>`;
const rejectInfo = String(m.status).toUpperCase() === "REJECTED"
  ? `<div class="reject-comment" style="color:var(--error-color);font-size:12px;">拒绝理由:${escapeHtml(m.reviewComment || "")}</div>` +
    `<button class="secondary-btn resubmit-btn" data-name="${escapeHtml(m.name)}">重新投稿</button>`
  : "";
```
resubmit-btn 绑定 → 打开 `_showSubmitForm` 预填该行内容。

- [ ] **Step 3: KB 共享 tab 文案 + 状态**

`_renderShareTab`(app.js L2764-2821):分享文案若说"提交即上架/直接 APPROVED"→ 改"提交后等待管理员审批";"我的发布"(KB,L2822-2879)同样加状态徽章 + REJECTED 拒绝理由显示(KB my-submitted 腿返回 MarketKnowledgeRecord 含 status/reviewComment)。

- [ ] **Step 4: i18n 补键**

```json
"market.status.pending": "审核中", "market.status.approved": "已通过", "market.status.rejected": "已拒绝",
"market.resubmit": "重新投稿", "market.reject.reason": "拒绝理由"
```
(en 对应)。若 `_statusLabel` 已硬编码中文映射,补 i18n 键即可,不强制重构。

- [ ] **Step 5: 构建 + Chrome 验证(双用户)**

```bash
mvn clean install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure,spring-ai-loom-agent-spring-boot-starter -am -Dgpg.skip=true -DskipTests
mvn spring-boot:run -pl spring-ai-loom-agent-test -Dgpg.skip=true
```
Chrome 验证:① 普通用户投稿技能 → "我的发布"显示"审核中"黄徽章;② admin 拒绝(带理由)→ 用户侧变"已拒绝"红徽章 + 显示理由 + 出现「重新投稿」;③ 点重新投稿 → 提交 → 回"审核中";④ 直查 H2 确认旧 REJECTED 行已进 `market_skill_archive`、主表新 PENDING 行;⑤ admin approve → 用户侧"已通过" + 公开市场可见 + 另一用户能 pull。

- [ ] **Step 6: Commit**

```bash
git add spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/i18n/zh-CN.json spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/i18n/en-US.json
git commit -m "feat(frontend): user my-submissions status badges + reject reason + resubmit (#4)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 9: 全量回归门 + 验收 IT 翻转

**Files:**
- Modify: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/MarketAcceptanceIT.java`(A1-A15 凡依赖"提交即上架"的腿补 approve)
- Modify: 任何因 submit→PENDING 而红的既有 IT(`DefaultSkillReviewServiceIT`/`DefaultKnowledgeReviewServiceIT` setup 若依赖 submit→APPROVED)
- Test: 跑全量 unit + IT

**Interfaces:**
- Consumes: 全部前序任务的 service/路由改动。
- Produces: 绿灯的回归基线。

- [ ] **Step 1: 跑全量 unit**

```bash
mvn clean install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure,spring-ai-loom-agent-spring-boot-starter -am -Dgpg.skip=true -DskipTests
mvn test -pl spring-ai-loom-agent-test
```
Expected: 383 unit PASS。若有红:逐个 Read 失败测试,判断是"测试断言旧 APPROVED 语义"(→改测试补 approve)还是"真 bug"(→修 service)。

- [ ] **Step 2: 跑全量 IT(清库)**

```bash
rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports
mvn test -pl spring-ai-loom-agent-test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 全 IT PASS(原 104 + 新增 MarketApprovalFlowIT 11 = ~115,3 skip)。

- [ ] **Step 3: 翻转 MarketAcceptanceIT**

Read `MarketAcceptanceIT.java`,定位 A1-A15 中所有 `submit(...)` 后直接 `pull(...)` / 断言 APPROVED 的腿。每处在 submit 与 pull 之间插入 `svc.approve(id, "admin1")`(skill)或对应 KB approve。断言 `status==APPROVED` 的改为先 approve 再断言。改完重跑该 IT → PASS。

- [ ] **Step 4: 翻转 review service IT(如需)**

`DefaultSkillReviewServiceIT` / `DefaultKnowledgeReviewServiceIT`:若 setUp 用 `submit()` 造 APPROVED 数据供评分,改为 `submit()` + `approve()` 两段,或直接 `createApproved()`。重跑 → PASS。

- [ ] **Step 5: 再跑全量 IT 确认全绿**

```bash
rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports
mvn test -pl spring-ai-loom-agent-test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 全 PASS。

- [ ] **Step 6: Commit**

```bash
git add spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/market/
git commit -m "test(market): flip acceptance + review ITs to two-phase submit→approve (#4)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 10: 文档同步(spec §9 小切口)+ roadmap 状态更新

**Files:**
- Modify: `docs/API.md`(L800-805)、`docs/API.zh-CN.md`(L489-590)
- Modify: `docs/CUSTOMIZATION.md`(L393)、`docs/CUSTOMIZATION.zh-CN.md`(对应行)
- Modify: `CLAUDE.md`(ISkillStorage 行 + 数据层段落)
- Modify: `docs/superpowers/roadmap-2026-09-four-items.md`(#4 状态 🔵/🟡→✅ + 落地记录)

**Interfaces:** 纯文档,无代码依赖。

- [ ] **Step 1: 反转 API 文档审批流描述**

`docs/API.md` L800-805 + `docs/API.zh-CN.md` L489-590:把"无审批流/提交即上架/approve 端点已移除/pending 已移除"全部反转 → 审批流存在:submit→PENDING、`POST /admin/market-*/{id}/approve`、`POST .../reject`(comment 必填)、pull 仅 APPROVED、admin 新增直发 APPROVED、REJECTED 重投归档(说明 `*_archive` 表)。端点表格补回 approve/reject 行。

- [ ] **Step 2: CUSTOMIZATION + CLAUDE.md**

`docs/CUSTOMIZATION.md` L393 "no approval flow" → "approval flow: submit→PENDING, admin approve/reject"。`CLAUDE.md`:ISkillStorage 行(L~95)去掉"no approval flow — submit is direct APPROVED";数据层段落(L~)的表清单补 `market_skill_archive` / `loom_market_knowledge_archive`;M3+/M4 段落若提"submit 直发 APPROVED"一并更新。

- [ ] **Step 3: roadmap 状态更新**

`docs/superpowers/roadmap-2026-09-four-items.md`:总排期表 #4 状态 🟡→✅;§#4 小节追加"落地记录"(各 Task commit 号、与 spec 的偏差若有、follow-up:archive 浏览 UI / KB v2 user-submit 腿退役后续)。

- [ ] **Step 4: grep 残留失真表述**

```bash
grep -rniE "无审批流|提交即上架|no approval flow|direct APPROVED|去掉审批" docs/ CLAUDE.md README.md README.zh-CN.md 2>/dev/null | grep -v -E "specs/|plans/|decisions/|roadmap-|\.superpowers/"
```
逐条核对:活文档(README/docs/CLAUDE)里的失真表述全部修正;specs/plans/decisions/roadmap/.superpowers 是历史快照,**保留原样**(它们记录当时事实)。

- [ ] **Step 5: Commit**

```bash
git add docs/API.md docs/API.zh-CN.md docs/CUSTOMIZATION.md docs/CUSTOMIZATION.zh-CN.md CLAUDE.md docs/superpowers/roadmap-2026-09-four-items.md
git commit -m "docs(market): sync API/CUSTOMIZATION/CLAUDE to revived approval flow + roadmap #4 done (#4)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## 收尾(全计划完成后)

- [ ] 全量回归门最后跑一次(unit 383 + IT ~115,清库),确认全绿。
- [ ] Chrome 端到端走查一遍完整审批闭环(投稿→PENDING→approve→公开→pull / reject→重投→archive)。
- [ ] 更新 roadmap #4 → ✅。
- [ ] **概览图**:本项(#4)不加新工具组、admin 控制台仍 5 区块 → **不影响概览图,无需重生成**(spec §9 已确认)。#1(工具组 8→9)+ #3(JVector→H2)才需重生成,届时提醒用户。
- [ ] 向用户汇报 #4 完成,询问是否继续 roadmap 下一项(#2 文档清理 / #3 H2 向量 / #1 AskUser)。

## Follow-ups(本期不做,记录)

- archive 浏览 UI(admin 查看历史归档的 REJECTED 行)。
- KB v2 `POST /user/market-knowledge` 腿退役后的彻底清理(若未来前端改用 v2 投稿路径)。
- 乐观锁 / 并发提交防护(spec §9.3 本期 last-write-wins)。
