# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
