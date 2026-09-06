# Skill + Knowledge Base 市场升级 — 设计稿

| | |
|---|---|
| **状态** | DRAFT |
| **作者** | Claude(协作产出) |
| **日期** | 2026-09-04 |
| **议题** | Skill 与知识库("KB")市场同步升级为可运营内容池 |
| **影响模块** | `spring-ai-loom-agent` + `spring-ai-loom-agent-spring-boot-autoconfigure` + 前端 `app.js` + `admin/*.html` |
| **Schema 落地** | V1.0 末尾追加(项目策略:全新库单文件 V1.0;老库 baseline + 数据迁移) |

---

## 1. TL;DR

把 skill 与 KB 两个市场体系**完全镜像**改造为同一形态:

- **三表模板** + 4 个公共列(`is_official / featured_rank / category / created_by_kind`,`status` 复用已有)+ 3 个附表(`stats / review / announcement`)
- **审批流**恢复(`PENDING → APPROVED / REJECTED`),作者投稿不再直发
- **service 抽象层**用 3 个泛型接口,skill / KB 各实现一遍;后台 CRUD / 审批 / 评分 / 统计共用
- **LLM 工具不变**:`ISkillTool` / `IKnowledgeTool` 不动;统计埋点(skill pull / KB search)在 service 层接管
- **阶段化发布** M0 / M1 / M2,YAGNI 留单清晰

---

## 2. 背景与目标

### 2.1 现状(摩擦)
- `market_skill.status` 字段虽然存在 PENDING / APPROVED / REJECTED 三态,但 **submit 端点直发 APPROVED**,审核成为空架子
- admin 控制台 `skills-market.html` **只能编辑 / 下架,不能新建**,运营能力不全
- 市场**没有官方标记、没有排序权重、没有分类、检索靠记忆**
- KB 市场同理,但 KB 还有自己的 `loom_role_knowledge` / `loom_user_knowledge` / `loom_market_knowledge`,**机制已是 skill 的影子**;两边各自演进的风险在涨
- 没有反馈回路(评分 / 评论 / 统计)

### 2.2 目标
1. **可运营**:admin 能从 UI 完成 skill 与 KB 的新建 / 编辑 / 审批 / 官方标记 / 排序 / 分类 / 下架
2. **可投稿**:作者能从聊天 UI 投稿,进入 PENDING 排队,admin 一键审
3. **可反馈**:用户能拉取 / 检索并评分评论,数据沉淀到 stat / review 表
4. **可演进**:未来加 `verified` / `archived` / `sponsored` 等机制,只动一处抽象

### 2.3 非目标(本期)
- A/B 实验灰度(⑨)
- 评分多维度(只做 1-5 单维)
- 评论点赞 / 子评论 / 评论举报
- 公开匿名评分
- 跨市场聚合推荐 dashboard(M2 之后)
- 评分编辑超过 1 次(本期限制 edit_count < 1)
- 自动 KB 文档评分("数据新鲜度"自动指标)

---

## 3. 核心设计决策

| 议题 | 决策 | 理由 |
|---|---|---|
| 路径分类 | **Architectural**(本 spec 用途) | 跨多层 / 多组件 / 多端点,非 bounded |
| 数据归一 | **单表 + 公共列**(方案 C) | schema 不拆,加列即可,扩展性最优 |
| 录入路径 | **B 方案**:admin 主路径 + author 投稿 PENDING | admin 控制力 + 生态丰富度兼具 |
| 镜像深度 | **完全镜像 8 项 + 对称差异**(stat / review 语义改名) | 稳定性(一套抽象两层实现) / 扩展性(加机制一两处) |
| 审核 role | **仅 ADMIN**(起步轻);`reviewed_by` 留口子给未来 reviewer | 避免一上来扩 role 表 |
| 标签形态 | **单 category + KB 多对多 tag 表**(M2 起;skill 侧对称 tag 已于 M4 2026-09-06 落地,见 § 4.2 注记) | 80% 场景单值够用,KB tag 是渐进增强 |
| 评分门槛 | **严**:KB review 必须 `loom_user_knowledge.access_count >= 1` | 避免没用过就评 |
| 评论实名 | **强制实名**(只显示 username) | 防刷、防报复 |
| 统计粒度 | **每次 +1**,批写缓冲(`flush_window=30s`) | 行为可观测,落盘聚合做后台 |

---

## 4. 数据模型

> 所有变更落地:`spring-ai-loom-agent/src/main/resources/db/migration/V1.0__init.sql` 末尾追加。

### 4.1 market_* 两表公共列(镜像加)

```sql
-- 公共列(两表都加)
ALTER TABLE market_skill          ADD COLUMN is_official     BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE loom_market_knowledge ADD COLUMN is_official     BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE market_skill          ADD COLUMN featured_rank   INT NOT NULL DEFAULT 0;
ALTER TABLE loom_market_knowledge ADD COLUMN featured_rank   INT NOT NULL DEFAULT 0;

ALTER TABLE market_skill          ADD COLUMN category        VARCHAR(64);
ALTER TABLE loom_market_knowledge ADD COLUMN category        VARCHAR(64);

ALTER TABLE market_skill          ADD COLUMN created_by_kind VARCHAR(16) NOT NULL DEFAULT 'USER';
ALTER TABLE loom_market_knowledge ADD COLUMN created_by_kind VARCHAR(16) NOT NULL DEFAULT 'USER';
```

### 4.2 KB 多对多 tag(M2 起)

```sql
CREATE TABLE loom_market_knowledge_tag (
  market_id BIGINT NOT NULL,
  tag       VARCHAR(64) NOT NULL,
  PRIMARY KEY (market_id, tag),
  FOREIGN KEY (market_id) REFERENCES loom_market_knowledge(id) ON DELETE CASCADE
);
CREATE INDEX idx_market_kb_tag ON loom_market_knowledge_tag(tag);
```

> **M4 注记(2026-09-06)**:skill 侧对称 tag 体系已落地(T4,原 tech-debt spec B4 "本期不做, M4 候选")—— `market_skill_tag`(`market_skill_id BIGINT` FK → `market_skill(id)` ON DELETE CASCADE)+ `idx_market_skill_tag` + `SkillTagService` + admin/public 路由 + list embed + `?tag=` 过滤。

### 4.3 Stat 表(语义改名)

```sql
CREATE TABLE market_skill_stats (
  market_skill_id  BIGINT  PRIMARY KEY,
  pull_count       BIGINT  NOT NULL DEFAULT 0,
  last_pulled_at   TIMESTAMP,
  FOREIGN KEY (market_skill_id) REFERENCES market_skill(id) ON DELETE CASCADE
);

CREATE TABLE loom_market_knowledge_stats (
  market_id        BIGINT  PRIMARY KEY,
  search_count     BIGINT  NOT NULL DEFAULT 0,
  last_searched_at TIMESTAMP,
  FOREIGN KEY (market_id) REFERENCES loom_market_knowledge(id) ON DELETE CASCADE
);
```

### 4.4 Review 表(同构 + edit_count)

```sql
CREATE TABLE market_skill_review (
  market_skill_id  BIGINT NOT NULL,
  username         VARCHAR(64) NOT NULL,
  rating           SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
  comment          TEXT,
  edit_count       SMALLINT NOT NULL DEFAULT 0,
  created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (market_skill_id, username),  -- 一人一评
  FOREIGN KEY (market_skill_id) REFERENCES market_skill(id) ON DELETE CASCADE
);
CREATE INDEX idx_market_skill_review ON market_skill_review(username);

CREATE TABLE loom_market_knowledge_review (
  market_id        BIGINT NOT NULL,
  username         VARCHAR(64) NOT NULL,
  rating           SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
  comment          TEXT,
  edit_count       SMALLINT NOT NULL DEFAULT 0,
  created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (market_id, username),
  FOREIGN KEY (market_id) REFERENCES loom_market_knowledge(id) ON DELETE CASCADE
);
CREATE INDEX idx_market_kb_review ON loom_market_knowledge_review(username);
```

> **KB review 门槛校验在应用层**(`SELECT COUNT(*) FROM loom_user_knowledge WHERE username=? AND market_id=? AND access_count>=1`),**无数据库触发器**。

### 4.5 Announcement 表(共享)

```sql
CREATE TABLE market_content_announcement (
  market_kind VARCHAR(16) NOT NULL,                       -- 'SKILL' | 'KNOWLEDGE'
  market_id   BIGINT NOT NULL,
  title       VARCHAR(128) NOT NULL,
  body        TEXT NOT NULL,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (market_kind, market_id)
);
```

> 公告的"置顶"语义 = 该市场行 `featured_rank = 999` + `announcement.title != NULL`。前端列表用 ORDER BY 把公告置顶。

### 4.6 数据库索引建议

```sql
CREATE INDEX idx_market_skill_official_rank   ON market_skill(is_official DESC, featured_rank DESC);
CREATE INDEX idx_market_kb_official_rank      ON loom_market_knowledge(is_official DESC, featured_rank DESC);
CREATE INDEX idx_market_skill_status_approved ON market_skill(status, is_official DESC, featured_rank DESC);
CREATE INDEX idx_market_kb_status_approved    ON loom_market_knowledge(status, is_official DESC, featured_rank DESC);
CREATE INDEX idx_market_skill_category        ON market_skill(category) WHERE category IS NOT NULL;
CREATE INDEX idx_market_kb_category           ON loom_market_knowledge(category) WHERE category IS NOT NULL;

-- KB review 门槛校验 SQL:SELECT ... WHERE username=? AND market_id=? AND access_count>=1
CREATE INDEX idx_user_knowledge_access_check ON loom_user_knowledge(username, market_id, access_count);
```

---

## 5. Service 层抽象

### 5.1 抽象接口(三组)

```java
// 公共 admin / 公共搜索
public interface IMarketContentAdminService<M, U, R> {
    Page<M> listPaged(MarketFilter filter);
    M getById(Long id);
    M create(String author, MarketCreateRequest req);   // user → PENDING, admin → APPROVED
    M update(Long id, MarketUpdateRequest req);
    void delete(Long id);                                // 级联清理
    M approve(Long id, String reviewer);
    M reject(Long id, String reviewer, String comment);  // comment 必填
    void setOfficial(Long id, boolean isOfficial, String reviewer);
    void setFeaturedRank(Long id, int rank, String reviewer);
    void setCategory(Long id, String category, String reviewer);
    Page<M> search(MarketSearchQuery query);              // SQL LIKE(name/desc)MVP
}

// 统计
public interface IMarketContentStatsService {
    void incrementStat(Long marketId, String kind);   // kind = PULL / SEARCH
    StatsRow getStats(Long marketId);
}

// 评分 / 评论
public interface IMarketContentReviewService {
    Page<ReviewRow> listReviews(Long marketId, int page, int size);
    ReviewRow submitReview(Long marketId, String username, ReviewSubmitRequest req);
    ReviewRow updateReview(Long marketId, String username, ReviewUpdateRequest req); // edit_count < 1 校验
    void deleteReviewAsAdmin(Long marketId, String username, String admin);
    RatingAggregate aggregateRating(Long marketId);
}
```

### 5.2 实现映射

| 接口 | SKILL 实现 | KB 实现 |
|---|---|---|
| `IMarketContentAdminService` | `DefaultSkillMarketService`(已存在,改造) | `DefaultKnowledgeMarketService`(已存在,改造) |
| `IMarketContentStatsService` | `DefaultSkillStatsService`(新建) | `DefaultKnowledgeStatsService`(新建) |
| `IMarketContentReviewService` | `DefaultSkillReviewService`(新建) | `DefaultKnowledgeReviewService`(新建) |

**类层级**:
```
AbstractMarketAdminService<M, U, R>(template method)
├── DefaultSkillMarketService extends AbstractMarketAdminService<MarketSkill, UserSkill, SkillReview>
└── DefaultKnowledgeMarketService extends AbstractMarketAdminService<MarketKnowledge, UserKnowledge, KnowledgeReview>

AbstractMarketStatsService<M>(template method)
├── DefaultSkillStatsService
└── DefaultKnowledgeStatsService

AbstractMarketReviewService<M>(template method)
├── DefaultSkillReviewService
└── DefaultKnowledgeReviewService
```

### 5.3 关键模板方法

`AbstractMarketAdminService` 提供的共享逻辑:
- `filterByStatusApproved(M, ...)`(只查 APPROVED,admin 模式例外)
- `validateReviewable(String reviewer)`(仅 ADMIN)
- `applySortPolicy(List<M>)`(按 `isOfficial DESC, featuredRank DESC, submittedAt DESC`)
- `cascadeOnDelete(M marketEntry)`(删 market 时级联清理 user_/role_/stats_/review)
- `enforceAuditTrail(M marketEntry, String action, String reviewer)`(记 audit,本期可暂不写表,只在 log)

子类只需实现:`extractId(M)` / `extractAuthor(M)` / `extractCreatedAt(M)` / `extractIsOfficial(M)` 等少量 query 适配。

---

## 6. REST 端点清单

> 全部用 `RouterFunctions`,无 Controller 注解。**路径后缀分流**(`/market-skills` vs `/market-knowledge`),**显式胜过动态 regex**。

### 6.1 公共(用户可调)

| Method | Path | 备注 |
|---|---|---|
| GET | `/spring/ai/loom/market-skills` | 分页列表(只 APPROVED) |
| GET | `/spring/ai/loom/market-skills/{id}` | 详情 |
| POST | `/spring/ai/loom/user/market-skills` | 作者投稿 → status=PENDING |
| DELETE | `/spring/ai/loom/user/market-skills/{id}` | 撤回(仅作者) |
| POST | `/spring/ai/loom/market-skills/{id}/pull` | 拉取(market entry → user entry) |
| POST | `/spring/ai/loom/market-skills/{id}/reviews` | 提交评分 / 评论 |
| GET | `/spring/ai/loom/market-skills/{id}/reviews` | 评论分页 |
| PUT | `/spring/ai/loom/market-skills/{id}/reviews/me` | 编辑自己的评论(edit_count < 1) |
| GET | `/spring/ai/loom/market-skills/{id}/stats` | 统计摘要 |

> KB 同路径全镜像,把 `skills` 替换 `knowledge`,**所有 endpoint 在 router 里并列写**。

### 6.2 admin

| Method | Path | 备注 |
|---|---|---|
| GET | `/spring/ai/loom/admin/market-skills` | 含 PENDING / APPROVED / REJECTED |
| POST | `/spring/ai/loom/admin/market-skills` | admin 创建(status=APPROVED) |
| PUT | `/spring/ai/loom/admin/market-skills/{id}` | admin 全字段更新 |
| DELETE | `/spring/ai/loom/admin/market-skills/{id}` | 删除(级联) |
| POST | `/spring/ai/loom/admin/market-skills/{id}/approve` | |
| POST | `/spring/ai/loom/admin/market-skills/{id}/reject` | body `{comment}` |
| PUT | `/spring/ai/loom/admin/market-skills/{id}/official` | body `{isOfficial: bool}` |
| PUT | `/spring/ai/loom/admin/market-skills/{id}/featured-rank` | body `{rank: int}` |
| PUT | `/spring/ai/loom/admin/market-skills/{id}/category` | body `{category}` |
| PUT | `/spring/ai/loom/admin/market-skills/{id}/announcement` | body `{title, body}` |
| DELETE | `/spring/ai/loom/admin/market-skills/{id}/announcement` | 撤销公告 |
| DELETE | `/spring/ai/loom/admin/market-skills/{id}/reviews/{username}` | admin 删评论 |

> KB 路径同镜像。**所有 admin 端点必须 admin 鉴权**(复用现有 `AuthenticationFilter.adminPathPatterns`)。

### 6.3 KB 独有

| Method | Path | 备注 |
|---|---|---|
| POST | `/spring/ai/loom/market-knowledge/{id}/access` | 用户发起检索,触发 search_count +1,记录 access_count |
| PUT | `/spring/ai/loom/admin/market-knowledge/{id}/tags` | M2 起,batch tags |

---

## 7. LLM 工具集成

### 7.1 现状(不动的部分)
- `ISkillTool` 接口不变:`getSkill(name)` / `createOrUpdateSkill(name, desc, content)`(依然 universal 工具)
- `IKnowledgeTool` 接口不变:`searchKnowledge(knowledgeId, query, topK?)`(依然 universal 工具)

### 7.2 改动点
- **`searchKnowledge` 实现里**:`kbMarketStatsService.incrementStat(marketId, "SEARCH")`(lazy,无副作用)
- **chat system prompt**:**已有**【技能】+【知识库】段,无需改动;`default_loaded` 继续生效(M0 不变,M1 后可让 admin 控制 default_loaded)
- **`createOrUpdateSkill`**:**行为不变**,只是 `user_skill.save()` 现在也写 `is_official=FALSE`(USER 自建默认)
- **新能力**(不在 LLM 工具范围,只对 admin 暴露):无新增工具

### 7.3 关键约束(继承现有)
- LLM 看不到市场列表(完全镜像已有设计:`buildDynamicSystemPrompt` 已注入 user 自己可见的 skill / KB,**不**注入市场数据,LLM 不直接浏览市场)
- `selectedSkillName` 走 user_skill 注入,作者改 user_skill 触发 fanout 同步规则**不变**
- 所有改动**不破坏** skill 通过 `/` picker 的交互链

---

## 8. 前端改造

### 8.1 用户聊天侧(`app.js` + `index.html`)

| 改动点 | 描述 |
|---|---|
| `state._skillListCache` | 不动 |
| Skills Modal **"市场" Tab** | 列表加 `is_official` 排序权重 + 官方徽章 🏛️;官方行置顶 |
| Skills Modal **"我的发布" Tab** | `_statusLabel` 已有 PENDING/APPROVED/REJECTED,在详情加 "审核中 / 已通过 / 已拒绝" 文案 + 显示 `review_comment`(若 REJECTED) |
| KB Modal | 同上(KB 模态复用同一组件 prop 切 `kind`) |
| SLASH picker | 不动;`/skill` / `/kb` 仍指向 user_skill / user_knowledge |

### 8.2 admin 侧

| 文件 | 改动 |
|---|---|
| `admin/skills-market.html` → 重命名 `admin/market-skills.html` | 表头列扩展:`作者 / 状态 / 官方 / 排序 / 分类 / Stat 摘要 / 评论数 / 操作` |
| `admin/knowledge-market.html`(新建) | 镜像 `market-skills.html`,`kind` prop 切 |
| `admin/skills-market.js` + `admin/knowledge-market.js` | 共享函数抽到 `admin/market-admin.js` |
| `admin/console.html`(首页) | 加"待审核"角标:`PENDING(skill) + PENDING(kb)` |

### 8.3 共享前端组件(`admin/market-admin.js`)

```js
marketList({ kind: 'SKILL' | 'KNOWLEDGE' });
  // 表头 / 分页 / 行渲染(带 🏛️ / ⭐ / 🏷️ / 📌 徽章)

marketForm({ mode: 'create' | 'edit' });
  // 复用模态:input(name/desc/content) + select(category) + checkbox(isOfficial) + input(featuredRank)

reviewList({ kind, marketId });
  // 评分星级 + 评论列表

approvalBadge({ status, reviewer, reviewedAt, comment });
```

> **不引入 Vue / React**,沿用项目原生 DOM 操作 + 微模板字符串(与现有 `app.js` 风格一致)。

---

## 9. 业务规则

### 9.1 状态机

```
                  ┌─────────────────────────────────────────────┐
                  │                  (create)                    │
                  ▼                                              │
              ┌───────┐    approve (admin)      ┌────────────┐  │
              │PENDING│───────────────────────► │  APPROVED  │  │
              └───────┘                         └─────┬──────┘  │
                  │                                   │         │
                  │ reject (admin, comment required)  │ admin    │
                  ▼                                   │ delete   │
              ┌───────┐                               │ (cascade)│
              │REJECTED│ ────────── author re-submit ─┘         │
              └───────┘                                          │
                                                                  ▼
                                                              (deleted)
```

- **PENDING → APPROVED**:`admin approve`;`reviewed_at = NOW()`,`reviewed_by = adminUsername`
- **PENDING → REJECTED**:`admin reject`,`comment` 必填(否则 400)
- **REJECTED → PENDING**:作者 re-submit,**新建 PENDING 行**(不更新旧 REJECTED 行);旧 REJECTED 行保留可追溯
- **APPROVED → PENDING**:**禁止**(已是已发布的资产,不回退)

### 9.2 权限矩阵

| 操作 | user(本人) | 其他 user | ADMIN |
|---|---|---|---|
| 查 PENDING 市场项 | ❌ | ❌ | ✅(`/admin/market-*`) |
| 查 APPROVED 市场项 | ✅(只读) | ✅(只读) | ✅ |
| 查 REJECTED 市场项 | ❌(自己可见) | ❌ | ✅ |
| 投 PENDING 投稿 | ✅(自己) | ❌ | ✅(直发 APPROVED) |
| 撤回自己投稿 | ✅(自己,PENDING/APPROVED) | ❌ | ✅ |
| 审批 approve | ❌ | ❌ | ✅ |
| 审批 reject | ❌ | ❌ | ✅ |
| 标记官方 | ❌ | ❌ | ✅ |
| 排序权重 | ❌ | ❌ | ✅ |
| 编辑分类 | 自己(只自己的 USER_CREATED) | ❌ | ✅ |
| 评分 | ✅(必须是 user_ 或 access 过) | ❌ | 永远可评(admin 自己评会记 username) |
| 评论编辑 | 自己,edit_count < 1 | ❌ | ❌ |
| 评论删除 | 自己可删自己 | ❌ | ✅ |
| 公告 | ❌ | ❌ | ✅ |
| **评分聚合** | — | — | **admin 自评不计入 `RatingAggregate.avg / count`**(只普通用户评分参与排序与展示;实施时在 `aggregateRating()` SQL 里 `WHERE username NOT IN (SELECT username FROM user_info WHERE type='ADMIN')` 或等价手段) |

### 9.3 边界行为

- **market 删除**(admin / user 撤回已发布):级联清理 `market_*_stats / market_*_review` + `user_*` 里 `market_*_id = id` 的行清空该指针(不是删 user_ 行,而是把 `market_*_id = NULL`,让 user_ 留作私人副本;by 现状 fanout 规则)
- **作者改 market entry**:不动 `market_*_id` 关联,**保留** version(本期不引入 version 字段,M2 之后加)
- **同名 name**:DB 层 `UNIQUE (author, name)`,UI 在创建前查重,422 + 文案
- **多市场同时改**:本期**无乐观锁**;并发改返回 last-write-wins,前端禁用提交按钮的简单防御

---

## 10. 统计 / 反馈回路

### 10.1 触发点

| Action | Stat 触发 |
|---|---|
| `POST /market-skills/{id}/pull` | `DefaultSkillStatsService.incrementStat(id, PULL)` |
| `searchKnowledge` 内部 | `DefaultKnowledgeStatsService.incrementStat(id, SEARCH)` |
| 评论 insert | aggregate 重算(实时,可缓存 30s) |

### 10.2 写入策略(避免热点行)

**每请求 +1** 触发高 QPS 时锁一个统计行 → 串行热点。**方案**:
- **in-memory ring buffer**:`ConcurrentHashMap<marketId, AtomicLong>` + 30s flush 周期
- flush 周期任务:`UPDATE market_*_stats SET count=count+?, last_*ed_at=NOW() WHERE market_id=?`(合并所有 delta)
- 进程启动时从 DB 装载初始值,退出前 flush 残留 buffer(用 `ApplicationListener<ContextClosedEvent>` 或 `@PreDestroy`)
- flush 失败:retry 一次后写 WARN log(本期不引入 outbox)

> 这个机制跟现有 `loom_tool_call_log` 等业务通用,可抽出 `BatchedCounterService` 复用。

---

## 11. 阶段化发布计划

### M0(4-5 天):核心 CRUD + 审批流 + 官方 / 排序 / 分类

**包含**:
1. Schema V1.0 末尾追加(§ 4 全部)
2. `AbstractMarketAdminService` + 两实现
3. 抽象层 3 个 service(只 admin / Stats / Review 还未写)
4. admin router 共 12 端点 × 2 表(skill + kb)= 24 admin 路由;公共端点 9 × 2 + KB 独有 1(access)= 19 公共路由;总计 ~43 端点
5. 前端 `market-admin.js` + `market-skills.html` / `market-knowledge.html`
6. 用户投稿改 PENDING
7. admin 控制台首页"待审核"角标

**验收**:
- admin 能从 UI 完整 CRUD 一个 skill / KB
- 作者从聊天 UI 投 PENDING,admin 一键审,刷前端列表显示
- 官方 / 排序 / 分类生效(在市场列表验证)
- 现有 Flyway 历史库 baseline 后能跑通

### M1(3-4 天):Stat / 评论 / 公告

**包含**:
1. `AbstractMarketStatsService` + 两实现
2. `AbstractMarketReviewService` + 两实现(严门槛校验)
3. `BatchedCounterService` 通用批写
4. 评论前端 UI(评分星 + 列表 + 编辑自己评论 ≤ 1 次)
5. 公告置顶

**验收**:
- skill pull_count,KB search_count 实时累计且性能不塌
- 用户评过分后再次评分被拒(消息清晰);KB 未 access 过的用户评被拒
- 公告显示置顶 + 标题 / 正文
- 评论编辑一次后再次 PUT 返回 422 + 文案

### M2(2-3 天):KB 多对多 tag + 跨市场搜索

**包含**:
1. `loom_market_knowledge_tag` 表 + admin 端点
2. 前端 KB 标签筛选 chip + admin 标签批量编辑
3. (可选)跨市场搜索 `/admin/market-search?q=...&kind=all`

**验收**:
- admin 给 KB 加多个 tag,用户可按 tag 筛选
- 删除 KB 级联删 tag 行

### YAGNI 留单
- 评分多维度 / 评论点赞 / 评论举报 / A/B 实验 / 推荐聚合

---

## 12. 验收标准(端到端)

| ID | 验收用例 | 期望 |
|---|---|---|
| A1 | user 在聊天 UI 投新 skill | market_skill 行 status=PENDING,admin UI 列表角标 +1 |
| A2 | admin approve 该 skill | market_skill.status=APPROVED + reviewed_by + reviewed_at;前端市场 Tab 出现 |
| A3 | admin reject without comment | 403 + "拒绝必须填评论" |
| A4 | admin reject with comment | market_skill.status=REJECTED + review_comment;作者详情页可见 |
| A5 | admin 标记某 skill is_official | 市场列表顶部 + 🏛️ 徽章 |
| A6 | admin 调整 featured_rank | MARKET list 排序按权重倒序生效 |
| A7 | admin 改 category | 市场列表可按 category 筛选 |
| A8 | user 评 skill | 评分生效;再次评 → 自动覆盖之前的(走 updateReview 而不是 insert);`updated_at` 刷新 |
| A9 | user 评 KB 但无 access | 403 + "请先访问过该知识库再评" |
| A10 | user 编辑自己的评论 | 第 1 次 OK;第 2 次 403 + "评论只能编辑一次" |
| A11 | skill pull +1 触发 stat | market_skill_stats.pull_count 在 30s 内聚合 +1 |
| A12 | KB search 触发 stat(高 QPS) | 不锁表,≤ 30s 聚合到 DB |
| A13 | admin 上公告 | 公告行置顶于市场列表顶部,标题 / 正文展示 |
| A14 | 用户改自己 USER_CREATED 强制 fanout | 该 skill 的所有 MARKET_PULLED 副本同步(content + description);**继承**现有 |
| A15 | 老库 baseline 后能正常工作 | 新装项目无影响;老库 Flyway history 加 `V1.0` baseline 行 |

---

## 13. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 老库 V1.x → V1.0 单文件迁移 | 项目既定策略:`flyway baseline` + 手动数据迁移;本期不解决 |
| V1.0 schema 末尾追加 ALTER 在已有 env 上失败 | 用 `ALTER TABLE ... ADD COLUMN ... DEFAULT ...` 兼容空表;老库先备份 |
| 抽象层 `AbstractMarketAdminService` 设计错误导致两层都受影响 | 暴露的公共方法由 spec 一一列出;**只**放确实共用的逻辑;KB 独有的字段在子类覆盖 |
| `BatchedCounterService` flush 失败导致数据丢失 | WARN log + 启动时再装载;不引入 outbox / MQ(后续补) |
| LLM 工具签名修改破坏旧测试 | LLM 工具**完全不动**;只有 searchKnowledge 内部多调一行 `incrementStat`,无 breaking change |
| admin 误删 market 级联清掉很多 user_skill | 已经在 fanout 现状中"删 market 行,user/market_skill_id 置 NULL",**本期保持现状不删 user_skill 行** |
| KB review 校验 SQL 性能 | `idx_user_knowledge_username` 已存在;加 `(market_id, username)` 复合键 索引 |
| 评论 / 评分被恶意刷 | UNIQUE(market_id, username) 防重评;edit_count 防编辑滥用;IP-rate-limit 留给上层 API gateway(本期不实装) |
| 阶段切分导致数据不一致 | M0 不引入 stat / review,M1 再加;每阶段发布前跑端到端测试 + DB migrate dry-run |

---

## 14. 决策记录(ADR)

| 编号 | 议题 | 取舍 | 决定 |
|---|---|---|---|
| ADR-001 | 数据归一 | 平铺 / 顶层 / 单表标签 / 不展示 | 单表 + 公共列(C) |
| ADR-002 | 录入路径 | 仅 admin / 仅 author / admin+author 审批 / 现状直发 | admin 主路径 + author PENDING(B) |
| ADR-003 | 镜像深度 | 完全镜像 / 部分镜像 | 完全镜像 + 对称差异 |
| ADR-004 | 审核 role | 仅 admin / 新 reviewer | 仅 admin,字段预留 |
| ADR-005 | 标签 | 单 category / 多对多 tag | 单 category + KB tag(M2 起;skill tag 已实现 M4 2026-09-06) |
| ADR-006 | KB 评论门槛 | 严 / 宽 | 严(access_count >= 1) |
| ADR-007 | 评论实名 | 强制 / 可选 | 强制实名 |
| ADR-008 | 统计粒度 | 每次 / 日聚 | 每次 +1 + 30s batch flush |
| ADR-009 | LLM 工具改动 | 改 / 不改 | 不改 |
| ADR-010 | 市场列表可见性 | admin 全部 vs 公共仅 APPROVED | 公共仅 APPROVED;admin 全可见 |

---

## 15. 后续路线图(本期外)

| 议题 | 优先级 | 时机 |
|---|---|---|
| `verified` 标识(管理员背书) | 低 | 加列 + UI,1 天 |
| 评分历史曲线 / Trend chart | 低 | 累计数据足够后 |
| 跨市场 dashboard | 中 | M2 后 |
| `sponsored` 投放(广告位) | 待定 | 产品定位 |
| 自动文档新鲜度(KB 上传时间自动衰减) | 低 | M2 后 |
| 多 reviewer role(职责分离) | 待定 | admin 压力上来时 |

---

## 16. 文件清单(本次改动)

### 后端 Java
- 新建:`cn/wubo/spring/ai/loom/agent/market/AbstractMarketAdminService.java`
- 新建:`cn/wubo/spring/ai/loom/agent/market/AbstractMarketStatsService.java`
- 新建:`cn/wubo/spring/ai/loom/agent/market/AbstractMarketReviewService.java`
- 新建:`cn/wubo/spring/ai/loom/agent/market/MarketFilter.java`、`MarketCreateRequest.java`、`MarketUpdateRequest.java`
- 新建:`cn/wubo/spring/ai/loom/agent/market/ReviewSubmitRequest.java`、`ReviewUpdateRequest.java`、`ReviewRow.java`、`RatingAggregate.java`、`StatsRow.java`
- 新建:`cn/wubo/spring/ai/loom/agent/market/BatchedCounterService.java`(通用)
- 改造:`cn/wubo/spring/ai/loom/agent/skill/DefaultSkillMarketService.java` extends AbstractMarketAdminService
- 改造:`cn/wubo/spring/ai/loom/agent/knowledge/DefaultKnowledgeMarketService.java` extends AbstractMarketAdminService
- 新建:`cn/wubo/spring/ai/loom/agent/skill/review/DefaultSkillReviewService.java`
- 新建:`cn/wubo/spring/ai/loom/agent/skill/stats/DefaultSkillStatsService.java`
- 新建:`cn/wubo/spring/ai/loom/agent/knowledge/review/DefaultKnowledgeReviewService.java`
- 新建:`cn/wubo/spring/ai/loom/agent/knowledge/stats/DefaultKnowledgeStatsService.java`
- 改造:`LoomAgentConfiguration.java` 增加 admin router / public market router 的镜像端点

### 前端
- 新建:`spring/ai/loom/market-admin.js`(共享 component)
- 改造:`spring/ai/loom/admin/skills-market.html` → `spring/ai/loom/admin/market-skills.html`
- 新建:`spring/ai/loom/admin/market-knowledge.html`
- 改造:`spring/ai/loom/app.js`:市场 Tab 排序 + 官方徽章 + 审核状态 + 评论列表

### 数据库
- `src/main/resources/db/migration/V1.0__init.sql` 末尾追加 § 4 全部 DDL

---

## 17. 开放问题(M3+)

1. KB 的 `default_loaded` 是否由 admin 控制?(目前每个 role 自己控制)
2. ~~KB 文件(物理)的版本化~~ — **本期不做,登记 M4 候选**(decision: ADR-002)
3. 是否加 `verified` 二级官方认证(需要在 ② 之上加列)
4. ~~公告是否对 `LOOM_VOICE` banner 集成?~~ — **已删除**(decision: 仓库内无 LOOM_VOICE 产品定义,显式移除该 open question)

—— END ——
