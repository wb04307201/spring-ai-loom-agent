# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
