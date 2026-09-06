# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added (M4 T5–T7, 2026-09-06)
- Admin skill-market page gains a tag column + batch tag-edit modal (T5, mirror of the KB admin page); the user SPA skill-market tab gains tag filter chips — combined with the keyword search in one `.kb-tag-filter-bar` — plus per-row tag chips and detail-panel tags (T6)
- Both market tabs gain a **sort dropdown** (官方优先 `official_rank` / 最新提交 `submitted_at` / 评分最高 `rating`; disabled while a tag filter is active) + search-result highlighting (`.search-hit mark`); the KB tab gains a keyword search box (parity with the skill tab); new i18n keys `market.sort.official` / `market.sort.newest` / `market.sort.rating` (T7)

### Added (M4 T4, 2026-09-06)
- Skill tag system — mirrors the v1.2.0 KB tag system on the skill side: `market_skill_tag` table (BIGINT `market_skill_id` FK → `market_skill(id)` ON DELETE CASCADE + `idx_market_skill_tag`), `SkillTagService` (`addTags`/`removeTag`/`replaceTags` with transactional rollback + `listTags` + `findByTag`/`findByAllTags` AND-intersection queries), admin `PUT`/`GET /spring/ai/loom/admin/market-skills/{id}/tags`, public `GET /spring/ai/loom/market-skills/{id}/tags`, and repeatable `?tag=` filter on the public skill list. `DefaultSkillMarketService.listPaged` now embeds `tags` (single batch SELECT, no N+1), and the `?tag=` path returns rows enriched with **both** tags and announcement via the new public `enrich()` (see Fixed (M4 T4) below)

### Added (M4 T3, 2026-09-06)
- Market admin/public v2 list endpoints (`listPaged`) now support `sortBy=rating` — orders by review aggregate (NULL-rated rows last), excluding ADMIN self-reviews (same rule as `aggregate()`); the review-aggregate LEFT JOIN is unconditional, so every `listPaged` row now carries `avgRating` / `ratingCount` / `isOfficial` / `featuredRank`

### Added (M4 T2, 2026-09-06)
- Skill market tab upgraded from client-side filtering to **server-side `?query=` search** (300ms debounce, Enter immediate, in-flight sequence guard against out-of-order responses); **both** market tabs gain 「加载更多」 load-more pagination (page size 20; new i18n keys `market.load.more` / `market.load.end`). The KB market tab's default branch switches from v1 `/spring/ai/loom/api/knowledge-market` (1-based) to v2 `/spring/ai/loom/market-knowledge` (0-based `Page{items,total,page,size}`)

### Changed (M4 T1–T2, 2026-09-06 — API-visible)
- `GET /spring/ai/loom/market-skills` (public list) and `GET /spring/ai/loom/admin/market-skills` (admin list) now return the v2 `Page{items,total,page,size}` shape — the v1 ARRAY list-route registrations were **deleted** from `LoomAgentConfiguration` so the v2 Page handlers are the sole winners (FU-4 surgical retirement). All other v1 skill routes survive: submit (auto-APPROVED), pull, admin POST/PUT/DELETE-cascade, `GET /spring/ai/loom/user/market-skills` (listMySubmitted — stays a bare ARRAY), and `GET .../{id}`
- `.secondary-btn` + `:hover` moved from `admin/console.css` to the shared base `style.css` (the `index.html` load-more button needs it; admin cascade preserved)

### Changed (M4 T3, 2026-09-06 — API-visible)
- `MarketSkill` record gains 5 components (`avgRating`, `ratingCount`, `isOfficial`, `featuredRank`, `tags`) and `MarketKnowledgeRecord` gains 4 (`avgRating`, `ratingCount`, `isOfficial`, `featuredRank`) — **source-breaking** for external constructor call sites of these `@ConditionalOnMissingBean`-adjacent DTOs (same precedent as the `IMarketContentReviewService<K>` entry above); JSON serialization impact is additive (new nullable fields)
- `AbstractMarketAdminService` gains two abstract hooks `reviewTable()` / `reviewIdColumn()` (code constants only — injection-safe); external subclasses must implement them (same precedent as T1.2/T1.5)

### Fixed (M4 T4/T7, 2026-09-06)
- Skill `?tag=` filter path now returns rows with tags + announcement embedded via `DefaultSkillMarketService.enrich()` — fixes the FU-1-style degradation **on the skill side** (the KB `?tag=` FU-1 remains open). v1 `listApproved()` intentionally does NOT embed tags (v1 semantics preserved)
- `app.js` `escapeHtml` hardened to also escape `"` and `'` — closes a tag `data-tag` attribute-context injection in the user SPA. **Residual (known open):** admin pages (`market-admin.js` / `knowledge-market.js` / `market-skills.js`) keep their own `escapeHtml` copies, still quote-unescaped — out of scope for M4, future hardening

### Changed (M3+ residual closure, 2026-09-06 — API-visible)
- `IMarketContentReviewService` is now generic `IMarketContentReviewService<K>` (Skill = `Long`, KB = `String` UUID); the String/Long twin overloads and `parseMarketIdOrThrow` are removed — **source-breaking** for external implementers of this `@ConditionalOnMissingBean` extension point (same precedent as T1.2/T1.5 in v1.2.0)
- `ReviewRow` is now `ReviewRow<K>` (`marketId` typed by `K`)
- `MarketAnnouncementRepository` is String-native on `market_id` (the shared `market_content_announcement.market_id` column is VARCHAR(36) for both kinds); Long overloads removed — **source-breaking** for external implementers
- `MarketAnnouncement.marketId` is now `String`; public `GET .../announcement` responses serialize `marketId` as a JSON string (was number; no internal consumers)
- KB `GET /reviews` with a UUID now returns `200` + empty page (was `404` via graceful-degradation); KB `GET /announcement` with no row returns `204` (was `404`)
- KB market list DTO now embeds `tags` (single batch SELECT) and `announcementTitle`/`announcementBody` — the latter was silently broken since v1.2.0 because `DefaultKnowledgeMarketService.marketKind()` returned `"KB"` while announcements are stored under `"KNOWLEDGE"` (join never matched); fixed with red-green coverage

### Fixed
- Intermittent `评价 upsert 失败:行未写入` / A13 announcement `500` failures — root cause was `Long` JDBC binds against VARCHAR(36) `market_id` columns; eliminated structurally by the type alignments above (no KB market path binds Long anymore)
- Acceptance ITs de-conditionalized: `a13` / `A12` router legs assert strict `200` via `route()` (direct-DB fallbacks removed); new positive-path KB router ITs (`a13kb` PUT announcement, `a16b` POST review, `a17b` GET announcement, `A18` cross-kind poison-row)
- `IMarketContentStatsService` injection sites in `LoomAgentConfiguration` parameterized `<Long>`/`<String>` (was raw types; compile-time safety, no behavior change)
- (2026-09-06, found by full Chrome UI test) `market-admin.js` admin write-endpoint builders pluralized `market-${kind}s` → KB admin "发布公告"/"删除评价" hit `/admin/market-knowledges/...` and 404'd (backend KB routes are singular `market-knowledge`); builders now derive from the existing `ADMIN_API[kind]` map — spec FU-6
- (2026-09-06, found by full Chrome UI test) `i18n/i18n.js` fetched dicts via document-relative `../i18n/*.json`, which 404s from `index.html` (sits beside `i18n/`, not one level down like `admin/*.html`) → dictionaries never loaded on the main page and announcement banners rendered the raw key `market.admin.announcement.badge`; dict URLs now resolve relative to the script's own location — spec FU-7

### Added (2026-09-06, round-2 Chrome UI test)
- Skill modal market tab gains a keyword **search** bar (filters by name/description/author), mirroring the knowledge-space market tab. Client-side filtering, since the public skill list runs the v1 `listApproved()` twin (FU-4, ignores `?query=`) and skills have no tag system (B4 deferred). Reuses the shared `kb-tag-filter-bar`/`-input` classes so no new CSS was required. *(Superseded within the same day by M4: search upgraded to server-side `?query=` (T2), skill tag system landed (T4), and the v1 list routes were retired (T1) — see the M4 sections above.)*

### Fixed (2026-09-06, round-2 Chrome UI + style-detection test)
- Shared market-UI CSS (announcement banner / review widget / star rating / tag filter / form primitives `primary-btn`/`form-input`/`type-badge`/`modal-footer`) was defined only in `admin/console.css`, which `index.html` never loads → the knowledge-space modal's market tab rendered **completely unstyled** in the chat page. Moved these blocks to the shared base `style.css` (loaded by both `index.html` and admin pages); `console.css` keeps admin-only chrome (sidebar/tables/layout/`secondary`+`delete`-btn/`btn-sm`/`tag-edit`/`approval`/`pending-chip`). Relative rule order preserved so the admin cascade is unchanged.
- Authored rules that existed in **no** stylesheet: file-manager tree (`file-tree`/`tree-*`), `detail-section-content`, `market-reviews-slot`, `review-list-wrap`, `delete-skill-btn`, `mcp-checkbox`/`mcp-item-text`.
- Style-detection (runtime unstyled-class scan) found 3 latent gaps: `.ks-sidebar-list` (CSS defined only `.ks-sidebar`, never matched → KB sidebar lost width/border/scroll), `.skill-item.disabled` (unauthorized capability items had no dim/not-allowed styling), `.conv-state-label` (added nowrap).
- `i18n/i18n.js`: `I18N.t(key, fallback)` — the 2nd arg is now treated as fallback text when it isn't a supported locale (callers pass their intended fallback; previously `t()` echoed the raw key when dicts were empty). Also `ready()` is now invoked at parse time — nothing called it before, so dict caches stayed empty and any early render showed raw `dot.path` keys (e.g. the announcement badge). With fallback support, a pre-ready render degrades to readable text.
- Skill market empty-state copy fixed from "市场暂无知识库" to "市场暂无技能".

## [v1.2.0] - 2026-09-04

### Added
- Market admin: 审批流 (`PENDING` / `APPROVED` / `REJECTED`) — author submissions enter a queue; admin approves / rejects from the UI with optional comment
- Market admin: `is_official` / `featured_rank` / `category` columns on both `market_skill` and `loom_market_knowledge` for ranking and categorization
- Stat tracking: `pull_count` (skill) + `search_count` (KB) via `BatchedCounterService` with 30s flush + shutdown hook (`@PreDestroy` fallback)
- Review service: 5-star rating + comment + `edit_count` gate; KB reviews require `loom_user_knowledge.access_count >= 1` (严门槛)
- Announcement table + per-row banner for admin broadcasts on the public detail view
- KB tag system: many-to-many `loom_market_knowledge_tag` table with filter UI + admin batch edit
- Public + admin v2 market routers mirroring the skill / KB sides (12 + 12 admin endpoints, 5 + 6 public)
- User market tab: sort by official / rank / created_at; reject comment input; official badge
- `IMarketContentAdminService<M, U, R>` abstraction shared by `DefaultSkillMarketService` and `DefaultKnowledgeMarketService` (mirror 8 endpoints + symmetric deltas)

### Changed
- Frontend admin market tab consolidated into a single `market-admin.js` namespace; `market-skills.html` and `market-knowledge.html` mirror each other for the M0 admin UI

### Known Limitations (remediated in M3+ cleanup)
- See CLAUDE.md "M0/M1/M2 Market Upgrade" section for the 24-item tech-debt catalogue being staged through T0–T6 + verification phases
