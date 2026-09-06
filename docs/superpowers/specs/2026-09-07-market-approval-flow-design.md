# 设计 — 市场审批流复活 + admin CRUD 接线(#4)

**日期**:2026-09-07 · **状态**:已与用户逐段确认 · **前置**:roadmap-2026-09-four-items.md §#4

## 1. 目标

把 2026-09-04 市场设计(spec §9.1 / ADR-002)中"名存实亡"的审批流做实:作者投稿进 PENDING、admin 从控制台新增/审批/编辑/下架;同时消灭 v1/v2 路由同路径双注册的语义分叉。技能与知识库两侧对称落地。

**非目标**:archive 浏览 UI(follow-up)、乐观锁、KB 物理版本化(ADR-002 defer 维持)。

## 2. 状态机(已拍板)

```
(create by author)──► PENDING ──admin approve──► APPROVED ──admin delete(级联)──► (deleted)
                        │  ▲                        ▲
                        │  └── admin create 直发 ────┘
                 admin reject(comment 必填,否则 400)
                        ▼
                     REJECTED ──author re-submit──► 旧行整行挪入 *_archive,主表新建 PENDING 行(新 id)
```

- APPROVED → PENDING:**禁止**(已发布资产不回退)。
- admin 新增:**直发 APPROVED**(admin 可信主路径,spec L396)。
- pull/access 仅 APPROVED 可拉(恢复校验,非 APPROVED → 403)。
- withdraw(作者撤回自己投稿):**任意状态可撤回**(维持现状实现,DELETE + 清 backlink);前端按状态区分文案(PENDING=撤回投稿 / APPROVED=下架并删除 / REJECTED=删除被拒记录)。

## 3. Schema(B1:append V1.0,清库重跑)

`spring-ai-loom-agent/src/main/resources/db/migration/V1.0__init.sql` 末尾追加(镜像主表全列 + archived_at;**主键沿用原 id 值,非自增**——归档保留原主键便于追溯;archive 只增不删):

```sql
-- 市场审批流(#4):REJECTED 行重投时旧行归档,主表 UNIQUE(author/username,name) 腾位
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

注:主表的 is_official/featured_rank/category/created_by_kind 等后补列**不进 archive**(它们只属于发布态资产;REJECTED 行这些列必为默认值,归档无信息量)。archive 表不加 FK/索引(只增不删、量小、本期无查询 UI)。

升级方式:遵守"只跑全新库"政策 → `rm -rf ~/.loom/datasource` + `target/test-ds` 重跑;现有 25 条测试技能丢弃(已确认)。

## 4. Service 层改动

### 4.1 `DefaultSkillMarketService`

| 方法 | 改动 |
|---|---|
| `submit(username, req)` L278-315 | ① 状态 `'APPROVED'`→`'PENDING'`,清 reviewed_at/reviewed_by/review_comment(投稿未审);② UPSERT 分支改造:同名旧行若 **status='REJECTED'** → 旧行 INSERT 进 `market_skill_archive` → DELETE 主表行 → INSERT 新 PENDING 行(新 id);非 REJECTED → 维持 UPSERT(UPDATE 回 PENDING + 清审核字段);③ backlink `user_skill.market_skill_id` 重写为新 id(现有 L311-313 逻辑保留,取新 marketId) |
| `pull(username, marketSkillId)` L375-412 | 恢复校验:`if (!STATUS_APPROVED.equals(m.status())) throw LoomAgentRuntimeException(403, "该技能未通过审批,暂不可拉取")` |
| `withdraw` L425-437 | 语义不变(DELETE+清 backlink),仅按 status 区分错误消息 |
| `adminCreate` L321-344 | 保留(v1 路由退役后由 v2 router 改调它,见 §5);默认 status 仍 APPROVED |
| `listMySubmitted` L417-421 | 不变(作者可见自己全部状态行,spec L395) |

### 4.2 `DefaultKnowledgeMarketService`

镜像同改:`submit(knowledgeId)` L286-321 → PENDING + REJECTED 重投归档(`loom_market_knowledge_archive`);`pull`/`access` 恢复 APPROVED 校验(L363-369 一带);`create()`(v2 admin 路径)L128-132 → **改落 APPROVED**(admin 直发,reviewed_at/by=当前 admin)。

### 4.3 `AbstractMarketAdminService` / v2 `create()`

- `approve/reject/setOfficial/setFeaturedRank/setCategory` **零改动**(已实现,reject comment 必填已强制)。
- v2 admin `create()` 语义改为 APPROVED:skill 侧 v2 router 改调 `adminCreate`(或 `create()` 参数化 status——**取前者**,避免动接口签名);KB 侧 `DefaultKnowledgeMarketService.create()` 直接改 SQL 落 APPROVED(该方法唯一调用方就是 v2 admin router)。

### 4.4 v2 admin `DELETE` → 级联

`AbstractMarketAdminService.delete` L274-276 裸 DELETE → 改为模板方法:基类 DELETE 前先调抽象钩子 `cascadeCleanup(K id)`;skill 子类实现 = `DELETE FROM user_skill WHERE market_skill_id=?` + `DELETE FROM role_skill WHERE market_skill_id=?`(对齐 v1 `adminDelete` L363-369);KB 子类实现 = 清 `loom_user_knowledge` / `loom_role_knowledge` 引用行(对齐 v1 KB admin router L4593 withdraw 级联语义)。

## 5. 路由收敛(FU-4 外科式退役,v2 唯一赢家)

`LoomAgentConfiguration` 中**删除以下 v1 注册**(每处留 `// FU-4/#4 外科式退役` 注释):

| v1 router bean | 退役的注册 | 保留者 |
|---|---|---|
| `loomAgentSkillMarketRouter`(L2357-2440 一带) | `POST /user/market-skills`(L2394)、`GET /user/market-skills`(L2414)、`DELETE /user/market-skills/{id}`(L2419)、`GET /market-skills/{id}`(L2360)、`POST /market-skills/{id}/pull`(L2377) | v2 `loomAgentMarketSkillRouter`(L3052-3330)对应腿;v2 若无 listMySubmitted 腿则**补注册**(GET/DELETE /user/market-skills 指向同一 service 方法) |
| `loomAgentSkillMarketAdminRouter`(L2446-2520) | 整个 bean(POST L2455 / PUT L2477 / DELETE L2499) | v2 `loomAgentMarketSkillAdminRouter`(L2573-3021);v2 POST 改调 `adminCreate`(§4.3);v2 DELETE 经级联模板(§4.4) |
| `loomAgentKnowledgeMarketAdminRouter`(L4580-4607) | 整个 bean(GET L4586 / DELETE L4593) | v2 `loomAgentMarketKnowledgeAdminRouter`(L3374-3793) |
| KB v1 user/public 路由(`/api/knowledge-market` 系,L4492-4574)+ `/api/knowledge/{id}/submit` | **精确退役,只删有 v2 等价物且前端已不调的腿**(已核实 app.js L61-67:list 已走 v2;`pull` / `my-submitted` / `withdraw` / `/api/knowledge/{id}/submit` 四条 **app.js 仍在调** → 保留注册,但其底层 `submit()`/`pull()` service 语义已被 §4.2 改为 PENDING/校验,自动受益) | v2 `loomAgentMarketKnowledgePublicRouter`(L3795+,含 `POST/DELETE /user/market-knowledge`、`/market-knowledge/{id}/pull`);**v2 `POST /user/market-knowledge`(L3913,调 `kbSvc.create`)语义与聊天侧分享不同**(要 name/description body;聊天分享只传 knowledgeId 走 v1 `/api/knowledge/{id}/submit`)→ 保留双腿,v2 腿的 `create()` 已被 §4.3 改为 admin 直发 APPROVED,**注意**:v2 user submit 腿若调同一 `create()` 会把"作者投稿"也变 APPROVED —— 实施时该腿必须改调 `kbSvc.submit(knowledgeId)`(PENDING 路径)或退役该腿(前端零调用,倾向退役并记 follow-up) |

v2 `PUT /admin/market-*/{id}`:确认 `MarketUpdateRequest` **无 status 字段**(已是),update 永不改 status——状态只走 approve/reject。

## 6. 前端改动

### 6.1 admin `market-skills.html/js` + `knowledge-market.html/js`(两侧对称)

- **新增按钮**(工具栏,刷新旁)→ 接线 `MarketAdmin.form({mode:'create', kind})`(market-admin.js L151-340,已写好零调用);SKILL 表单含 name/description/content/category;KB 表单含 name/description/category/official/rank(form() 已支持)。
- **PENDING 行操作列**:「通过」(confirm 弹层)→ `POST /admin/market-*/{id}/approve`;「拒绝」→ comment 输入 modal(必填,空则前端拦截提示)→ `POST .../reject` body `{comment}`。REJECTED 行显示已拒绝+理由(title 悬浮或详情面板)。
- **技能编辑弹窗补字段**:category / isOfficial 勾选 / featuredRank 数字(镜像 KB 弹窗 L122-146);PUT body 扩为 `{name,description,content,category,isOfficial,featuredRank}`(v2 `MarketUpdateRequest` 已支持这些字段)。
- **文案修正**:删除"不再新建""去掉审批流"等失真文案(html L50/70-73/89/147,KB L51/72-74/90);待审核 chip 文案保留。
- **共享方法**:`market-admin.js` 新增 `MarketAdmin.approve(kind,id)` / `MarketAdmin.reject(kind,id,comment)`(镜像 `updateMarketTags` 范式,401/403 跳登录)。

### 6.2 用户侧 `app.js`

- Skills modal「我的发布」tab:每行状态徽章 审核中(黄)/已通过(绿)/已拒绝(红,点击展开 review_comment);REJECTED 行「重新投稿」按钮 → 复用现有 `_showSubmitForm` 提交(后端 submit 自动归档+新建 PENDING 行)。
- 投稿成功 toast:"已提交,等待管理员审批"(文案已存在,现在代码行为终于一致)。
- 市场 tab pull 按钮:仅 APPROVED 行可见(公开列表本就只返回 APPROVED,零改动,防御性确认)。
- KB 共享 tab(`_renderShareTab` 一带):若 §5 退役了 v1 `/api/knowledge-market` 腿,fetch 路径切 v2 `/market-knowledge` 系;分享文案同步"提交后等待审批"。

### 6.3 `console.js` PENDING chip

零改动(M4 已改为服务端 `?status=PENDING&page=0&size=1` 读 total,审批流复活后开始真正计数)。

## 7. i18n

启用已备好的键:`market.admin.action.approve/reject/setOfficial/setFeatured`(zh/en 均已存在);新增 `market.admin.reject.commentRequired`(拒绝理由必填)、`market.status.pending/approved/rejected`(用户侧徽章,若已有复用)、`market.resubmit`(重新投稿)。

## 8. 测试

### 8.1 翻转既有断言

- `KnowledgeMarketIntegrationTest` L197-202 等:submit 后断言 `PENDING`(原 APPROVED);后续步骤补 `approve()` 再断言 APPROVED + pull 成功。L241/252 注释同步。
- `MarketAcceptanceIT`(A1-A15):过一遍,凡依赖"提交即上架"的验收腿改为两段式(submit→approve→pull)。
- `DefaultSkillReviewServiceIT` / `DefaultKnowledgeReviewServiceIT`:若 setup 依赖 submit→APPROVED 则同改(setup 直接调 approve 或 adminCreate)。
- 路由分发:镜像 `SkillListDispatchIT` 范式,新增/更新断言 POST/PUT/DELETE 只剩 v2 handler(退役后 RouterFunction 组合无歧义)。

### 8.2 新增 IT

| IT | 断言 |
|---|---|
| `MarketApprovalFlowIT`(skill+KB 参数化或双腿) | submit→PENDING;approve→APPROVED+reviewed_by/at;reject 无 comment→400;reject 带 comment→REJECTED+review_comment;pull REJECTED/PENDING→403 |
| `MarketResubmitArchiveIT` | REJECTED 行重投:archive 表落 1 行(原 id/原 comment 保留),主表新 id PENDING 行,backlink 指向新 id;archive 只增(二次重投再归档) |
| `AdminCreateApprovedIT` | v2 POST /admin/market-skills → APPROVED + reviewed_by=admin;KB 同 |
| `AdminDeleteCascadeIT` | v2 DELETE:skill 清 user_skill/role_skill 引用;KB 清 loom_user_knowledge/loom_role_knowledge |

### 8.3 回归门

阶段边界跑 market 全 IT(`-Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false`,清 `~/.loom/datasource`+`target/test-ds`)+ 383 unit;Chrome 手工验证:admin 新增→列表即 APPROVED;用户投稿→PENDING chip +1→approve→公开市场可见→另一用户 pull 成功;reject 带理由→作者"我的发布"见红色徽章+理由→重投→archive 落行(直查 H2)→新 PENDING。

## 9. 文档同步(并入本项,小切口)

- `docs/API.md` L800-805 / `docs/API.zh-CN.md` L489-590:反转"无审批流/approve 已移除"表格行 → 审批流端点表(approve/reject/submit→PENDING/pull 校验)+ archive 行为说明。
- `docs/CUSTOMIZATION.md` L393 "no approval flow" → 更新。
- `CLAUDE.md`:ISkillStorage 行及相关段落去掉"no approval flow — submit is direct APPROVED";数据层段落补 2 张 archive 表。
- 全面 md 对齐仍属 roadmap #2;概览图**不受本项影响**(不加新工具组、admin 控制台仍 5 区块),无需重生成。

## 10. 实施顺序(单 spec 内分阶段,每任务独立 commit)

1. **T1 schema+service**:V1.0 append archive 表;skill/KB submit→PENDING+归档重投;pull/access 恢复校验;v2 create→APPROVED;delete→级联模板。+ §8.2 新 IT。
2. **T2 路由收敛**:退役 v1 双注册(先 grep app.js/前端全部 fetch 腿逐一对齐 v2 再删);分发 IT。
3. **T3 admin 前端**:新增按钮+form() 接线、approve/reject 按钮+comment modal、技能编辑弹窗补字段、文案修正、market-admin.js 共享方法、i18n。
4. **T4 用户前端**:我的发布状态徽章+拒绝理由+重投;KB 共享 tab 路径/文案。
5. **T5 测试翻转+回归门+Chrome 验证**。
6. **T6 文档同步**(§9)+ roadmap 状态更新。

风险预案:T2 若发现前端仍依赖某 v1 腿且 v2 无对应 → 该腿暂保留并在 spec 追加 follow-up,不硬删断前端。
