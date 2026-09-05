# ADR-T05.3: portable upsert — DEFER per ADR-T05.1 (policy A)

| | |
|---|---|
| **编号** | ADR-T05.3(M3+ 计划任务编号) |
| **日期** | 2026-09-05 |
| **状态** | Deferred — superseded by ADR-T05.1 policy A |
| **议题** | cleanup plan T5.3: 3 处 H2 `MERGE INTO` 替换为 portable upsert |
| **决策** | **本期不替换**;H2 `MERGE INTO` 保留;3 个 call site 已识别并文档化,留给多 DB 迁移需求出现时再做 |
| **理由** | (1) T5.1 policy A 决策保留"项目只跑全新库 + 仅 H2"政策 — 没有 portable 跨 DB 的实际需求;(2) H2 `MERGE INTO` 在当前 H2 2.x 行为稳定,改写 SQL 风险/收益比不划算;(3) 跨方言 upsert 改写需要为每个方言做分支(H2 MERGE / PG `INSERT ... ON CONFLICT DO UPDATE` / MySQL `ON DUPLICATE KEY UPDATE`),本期不在 scope |
| **触发条件** | 当任一条件满足时,重新激活 T5.3:(a) 用户开始用 PG 或 MySQL;(b) 项目政策调整为接受已有实例的 schema 迁移;(c) H2 MERGE 出现回归 bug |
| **影响模块** | 当前无;3 个 site 已识别:`KnowledgeTagService` L~71, `AbstractMarketReviewService` L~84, `DefaultMarketAnnouncementRepository` L~57 |
| **关联 task** | T5.3 (deferred) |
| **关联 spec** | `docs/superpowers/specs/2026-09-05-market-tech-debt-cleanup.md` §10 ruling 3 (MERGE INTO upsert + edit_count 服务层校验) |

## 已识别的 portable upsert call sites

| File | L~ | Method | 当前 SQL 形态 |
|---|---|---|---|
| `spring-ai-loom-agent/.../knowledge/market/KnowledgeTagService.java` | 71 | `addTags` | `MERGE INTO loom_market_knowledge_tag KEY(market_id, tag) VALUES (...)` |
| `spring-ai-loom-agent/.../market/AbstractMarketReviewService.java` | 84 | `submit` | `MERGE INTO <table> USING ... ON DUPLICATE KEY UPDATE` style |
| `spring-ai-loom-agent/.../market/DefaultMarketAnnouncementRepository.java` | 57 | `upsert` | `MERGE INTO market_content_announcement ...` |

## 当重新激活时,推荐方案

### 方案 A: SELECT-then-INSERT/UPDATE(应用层)

```java
Optional<Row> existing = jdbc.query("SELECT 1 FROM <table> WHERE key = ?",
        rs -> rs.next() ? Optional.of(...) : Optional.empty(),
        key);
if (existing.isPresent()) {
    jdbc.update("UPDATE <table> SET ... WHERE key = ?", values, key);
} else {
    jdbc.update("INSERT INTO <table> (key, ...) VALUES (?, ...)", values);
}
```

- 优:跨方言无门槛
- 劣:2 round-trip,理论上有 race(并发的 2 个 thread 可能都走 INSERT 然后主键冲突)

### 方案 B: 数据库方言分发

```java
String sql = switch (jdbc.getMetaData().getDatabaseProductName()) {
    case "H2" -> "MERGE INTO ...";
    case "PostgreSQL" -> "INSERT ... ON CONFLICT (...) DO UPDATE SET ...";
    case "MySQL" -> "INSERT ... ON DUPLICATE KEY UPDATE ...";
    default -> throw new IllegalStateException("unsupported DB: " + ...);
};
```

- 优:单 round-trip,各方言最优形态
- 劣:代码复杂度 + 测试矩阵扩张

### 推荐

如果未来真要 portable,先用方案 A(应用层 + race-acceptable)。只有当 A 出现性能问题(race 频发或 2 round-trip 显著)时再升级到方案 B。

## 决策日志

- 2026-09-05:Deferred(ADR-T05.1 policy A 决策后 T5.3 失去紧迫性)
