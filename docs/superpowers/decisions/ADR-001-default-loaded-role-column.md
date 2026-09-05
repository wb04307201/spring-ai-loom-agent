# ADR-001: KB `default_loaded` 由 admin 在 `loom_role_knowledge` 加列控制

| | |
|---|---|
| **编号** | ADR-001 |
| **日期** | 2026-09-05 |
| **状态** | Accepted |
| **议题** | design spec §17 第 1 问 — KB 的 `default_loaded` 是否由 admin 控制? |
| **决策** | 加 `loom_role_knowledge.default_loaded BOOLEAN` 列,admin 在 `/admin/roles/{code}/knowledge` 页 toggle |
| **理由** | (1) per-role toggle 比 per-user 粒度更省事(role 数量 << user 数量);(2) admin UI 已存在 KB 授权表,加 toggle 复用现有页面;(3) 决策一致 — 同一 role 内所有 user 共享 default_loaded |
| **替代方案** | B: 加 `loom_user_knowledge.default_loaded`(粒度更细,但 admin UI 复杂 + 行数膨胀);C: defer M4(本期缺功能) |
| **影响模块** | `spring-ai-loom-agent`(schema + role service)、admin UI、`ChatRequestRecord` 默认值计算 |
| **关联 task** | 新增(待 plan T0–T6 之外开 task,建议编号 T8.1) |
| **关联 spec** | `docs/superpowers/specs/2026-09-04-skill-knowledge-market-design.md` §17 第 1 问 |

## 决策细节

### Schema 变更

```sql
ALTER TABLE loom_role_knowledge
  ADD COLUMN default_loaded BOOLEAN NOT NULL DEFAULT FALSE;
```

迁移语义:`default_loaded = FALSE` 为默认;admin 可在 role 授权页单独 toggle 每个 KB 的 default_loaded。

### 行为变更

- 当前:每个 role 自己控制(无 default 概念)
- 修后:`default_loaded = TRUE` 时,该 role 下的所有 user 自动拥有该 KB(无需 user 单独授权)
- `ChatRequestRecord.enabledKnowledgeIds` 计算时,`default_loaded` 与 user 显式授权 OR(优先 explicit,缺省 fallback 到 default_loaded)

### Admin UI

在 `/admin/roles/{code}/knowledge` 现有 toggle 表上加一列 `默认加载` checkbox;与现有 `启用` 区分:
- `启用` = role 可见该 KB(已有)
- `默认加载` = role 下 user 默认自动获得(新增)

### API

- `PUT /admin/roles/{code}/knowledge/{kbId}` 请求体加 `default_loaded: boolean` 字段
- `GET /admin/roles/{code}/knowledge` 响应 DTO 加 `default_loaded: boolean`

## 回退策略

```sql
ALTER TABLE loom_role_knowledge DROP COLUMN default_loaded;
```

无外部依赖,可单行 DDL 回退。

## 决策日志

- 2026-09-05:Accepted(用户确认选项 A)
