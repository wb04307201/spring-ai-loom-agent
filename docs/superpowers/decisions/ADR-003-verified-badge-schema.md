# ADR-003: `verified` 二级官方认证 — 加 3 列 × 2 表

| | |
|---|---|
| **编号** | ADR-003 |
| **日期** | 2026-09-05 |
| **状态** | Accepted |
| **议题** | design spec §17 第 3 问 — 是否加 `verified` 二级官方认证? |
| **决策** | `market_skill` + `loom_market_knowledge` 各加 `verified` + `verified_by` + `verified_at` 三列;默认 `verified = is_official`(迁移时);admin `/admin` UI toggle |
| **理由** | (1) `verified` 语义是"经人审",与 `is_official`(系统预置官方源)是两个维度,混淆后未来拆更贵;(2) 默认 `verified = is_official` 让迁移时行为对用户透明;(3) admin 可独立 toggle,支持"预置官方但未审"或"非官方但已审"两种场景 |
| **替代方案** | A (加列,选这个);B: 与 `is_official` 自动联动(无 schema 改动,但失去"人审"维度);C: defer M4(本期缺功能) |
| **影响模块** | `spring-ai-loom-agent`(schema + admin service)、admin UI、market 列表前端 |
| **关联 task** | 新增(待 plan T0–T6 之外开 task,建议编号 T8.2) |
| **关联 spec** | `docs/superpowers/specs/2026-09-04-skill-knowledge-market-design.md` §17 第 3 问 |

## 决策细节

### Schema 变更

```sql
-- market_skill 加 3 列
ALTER TABLE market_skill
  ADD COLUMN verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE market_skill
  ADD COLUMN verified_by VARCHAR(64) NULL;
ALTER TABLE market_skill
  ADD COLUMN verified_at TIMESTAMP NULL;

-- loom_market_knowledge 加 3 列
ALTER TABLE loom_market_knowledge
  ADD COLUMN verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE loom_market_knowledge
  ADD COLUMN verified_by VARCHAR(64) NULL;
ALTER TABLE loom_market_knowledge
  ADD COLUMN verified_at TIMESTAMP NULL;
```

迁移语义:
- `verified = FALSE` 默认值
- 迁移脚本额外执行:`UPDATE market_skill SET verified = is_official WHERE is_official = TRUE;` 及 `loom_market_knowledge` 同理 — 让迁移后行为对用户透明(已 `is_official` 的自动 `verified`)

### 行为变更

- `verified = TRUE` 时,市场列表显示 🏛️ `verified` 徽章(与 `is_official` 徽章样式区分;新徽章需设计确认)
- `verified_by` = admin username
- `verified_at` = toggle 时间戳
- admin 在 `/admin/market-skills` 与 `/admin/market-knowledge` 列表行新增 `verified` toggle checkbox

### 与 `is_official` 的语义区别

| 维度 | `is_official` | `verified` |
|---|---|---|
| 来源 | 系统预置 / admin 标记 | admin 显式 toggle |
| 语义 | "本仓库官方推荐" | "经 admin 人审通过" |
| 自动联动 | 无 | 默认 = `is_official`(迁移时),之后独立 |
| 撤销 | admin 标记 false | admin 标记 false |

## API

- `PUT /admin/market-skills/{id}/verified` body `{verified: boolean}`
- `PUT /admin/market-knowledge/{id}/verified` body `{verified: boolean}`
- 列表 DTO 加 `verified` + `verified_by` + `verified_at` 字段(可能与 AT2 N+1 修复合并 — 见 plan T2.1)

## 回退策略

```sql
ALTER TABLE market_skill DROP COLUMN verified;
ALTER TABLE market_skill DROP COLUMN verified_by;
ALTER TABLE market_skill DROP COLUMN verified_at;
ALTER TABLE loom_market_knowledge DROP COLUMN verified;
ALTER TABLE loom_market_knowledge DROP COLUMN verified_by;
ALTER TABLE loom_market_knowledge DROP COLUMN verified_at;
```

数据可丢失;无外部依赖。

## 决策日志

- 2026-09-05:Accepted(用户确认选项 A — 加列)
