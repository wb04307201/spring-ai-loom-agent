# ADR-002: KB 物理文件 versioning 推 M4 评估

| | |
|---|---|
| **编号** | ADR-002 |
| **日期** | 2026-09-05 |
| **状态** | Accepted |
| **议题** | design spec §17 第 2 问 — KB 文件(物理)的版本化怎么处理? |
| **决策** | 本期(M3+)不做,登记 M4 候选 |
| **理由** | (1) 当前 KB 文件上传路径已稳定,无版本化需求场景;(2) 选项 B(full history 表)需要新表 + UI + 切换逻辑,工作量大;(3) 选项 C(单列 version INT)语义弱(无回滚能力);(4) M3+ scope 已重,新功能优先级低 |
| **替代方案** | A (本期不做,选这个);B: 新建 `loom_kb_file_version` 表 full history + UI 切版本;C: `loom_file.version INT` 单列,旧文件不保留 |
| **影响模块** | 无 |
| **关联 task** | 无 |
| **关联 spec** | `docs/superpowers/specs/2026-09-04-skill-knowledge-market-design.md` §17 第 2 问 |

## 决策细节

本期 KB 文件路径不引入任何 version 概念 — 上传同名文件按现有 `IUpload` 重名规则加序号(`file.txt → file(1).txt → file(2).txt`),无版本概念。

## M4 待评估

- 是否需要 git LFS 风格 object 存储?
- KB 内容变更时是 append-only(每次新版本)还是 in-place 覆盖?
- 历史版本 UI 切版本复杂度?
- 旧文件物理清理策略?

## 决策日志

- 2026-09-05:Accepted(用户确认选项 A — M4 defer)
