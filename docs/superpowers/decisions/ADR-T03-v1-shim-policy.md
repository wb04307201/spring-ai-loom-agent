# ADR-T03: v1 market service interface shim retention policy

| | |
|---|---|
| **编号** | ADR-T03(M3+ 计划任务编号) |
| **日期** | 2026-09-05 |
| **状态** | Accepted |
| **议题** | v1 service interface 路径如何处理?`ISkillMarketService` / `IKnowledgeMarketService` 还在被多个 caller 使用 |
| **决策** | 保留 1 个 minor version,加 `@Deprecated` + javadoc 指向 v2 generic `IMarketContentAdminService<K,M,U,R>`;当 call-site grep 引用计数 = 0 时正式删除 v1 接口 |
| **理由** | (1) 突然删除会引发外部 caller(本仓库 + 用户 consumer)的 breaking change;(2) `IMarketContentAdminService<K,M,U,R>` 是 v2 唯一抽象,所有 caller 长期应该迁过去;(3) 1 minor version 给 caller 迁移窗口 |
| **替代方案** | A(选这个);B:立刻删除 v1,所有 caller 强制同步迁移(高风险);C:无限期保留(技术债) |
| **影响模块** | `cn.wubo.spring.ai.loom.agent.skill.ISkillMarketService`, `cn.wubo.spring.ai.loom.agent.knowledge.IKnowledgeMarketService` |
| **关联 task** | T3.1 |
| **关联 spec** | `docs/superpowers/specs/2026-09-05-market-tech-debt-cleanup.md` §9 ADR-T03 |

## 决策细节

### v1 / v2 路径

| 版本 | 接口 / 实现 | 备注 |
|---|---|---|
| v1 | `ISkillMarketService` / `IKnowledgeMarketService` | 老接口,返回 MarketSkill / MarketKnowledgeRecord |
| v2 | `IMarketContentAdminService<K,M,U,R>` via `AbstractMarketAdminService` + `DefaultSkillMarketService` / `DefaultKnowledgeMarketService` | M3+ T1.2/T1.3 引入的泛型抽象 |

### 保留策略

- 1 个 minor version 期间 `ISkillMarketService` / `IKnowledgeMarketService` 仍可调用
- 加 `@Deprecated(forRemoval = true, since = "1.3.0")` + javadoc 指向 v2
- impl class (`DefaultSkillMarketService`) 仍 `implements ISkillMarketService` — 因为方法签名兼容,IDE 警告即可
- call-site grep 引用计数随 release notes 下降;最终 = 0 才删除 v1 接口
- 监控:`grep -r "ISkillMarketService\|IKnowledgeMarketService" src/ | wc -l`

### 删除条件

满足以下全部时删除 v1 接口文件:
- [ ] call-site grep 计数 = 0
- [ ] 1 个 minor version 窗口已过(下次 minor release)
- [ ] CHANGELOG 已通知 removal

### 回退方案

如果 1 minor version 后仍有 call-site,延长保留期(再 1 minor);不做"必须删"的硬截止。

## 决策日志

- 2026-09-05:Accepted(M3+ T3.1 落地;`@Deprecated` 已加)
