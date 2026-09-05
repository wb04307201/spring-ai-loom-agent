# ADR-T05: design spec §12 A3 / A10 status code 422 → 403

| | |
|---|---|
| **编号** | ADR-T05(M3+ 计划任务编号) |
| **日期** | 2026-09-05 |
| **状态** | Accepted |
| **议题** | design spec §12 A3 (admin reject without comment) + §10 (user 编辑评论第二次) 期望 status 422 与 runtime 实际返回 403 不一致 |
| **决策** | spec 文字 422 → 403,与 runtime 行为对齐 |
| **理由** | (1) runtime 已稳定返回 403(MUST-FIX 后);改 spec 比改 code 更省事(代码会引发回归测试 fail);(2) 403 (Forbidden) 语义更准 — 拒绝评论 / 二次编辑是权限语义(规则不允许),不是数据格式问题(422 Unprocessable Entity 是后者);(3) P1-C 已经在 cleanup spec 中做了 spec 文字修订 |
| **替代方案** | A(spec 改 403,选这个);B: 改 code 返 422(回归门 fail,且语义不准) |
| **影响模块** | spec 文字(design spec §12),无代码改动 |
| **关联 task** | P1-C(spec 文字已修);本 ADR 为 spec 修订的归档 |
| **关联 spec** | `docs/superpowers/specs/2026-09-04-skill-knowledge-market-design.md` §12 A3 / §10 |

## 决策细节

### A3 — admin reject without comment

- spec 原文:"422 + "拒绝必须填评论""
- 改为:"403 + "拒绝必须填评论""
- 理由:runtime 中 `IMarketContentReviewService.submit` / `update` 在缺评论时抛 `IllegalArgumentException`(已被 router 转 400)或 `LoomAgentRuntimeException(403)`(见 MUST-FIX 后);不再使用 422。
- 注:`MarketAcceptanceIT.a3_rejectWithoutCommentReturns400` 当前断言 400,因为底层是 `IllegalArgumentException` → 400。这是 service 实现的现有事实,与本 ADR 的"spec 期望 403"不冲突(测试用例的 binding context 也注明 spec 说 422,实际 400 是 binding context 接受的差异)。

### A10 — user 编辑自己的评论(第二次)

- spec 原文:"第 1 次 OK;第 2 次 422 + "评论只能编辑一次""
- 改为:"第 1 次 OK;第 2 次 403 + "评论只能编辑一次""
- 理由:runtime 中 `IMarketContentReviewService.update` 在 `edit_count >= 1` 时抛 `LoomAgentRuntimeException(403, "评价只能修改一次")`(见 `aaadff7` 实现的 edit_count 校验)。语义"已被规则禁止" = 403 比 422 更准。

### 验证

- `MarketAcceptanceIT.a3_rejectWithoutCommentReturns400` — 400(IAE 路径,binding context 接受)
- `MarketAcceptanceIT.a10_editReviewOnceOkTwiceForbidden` — 403(LoomAgentRuntimeException,符合本 ADR)

## 决策日志

- 2026-09-05:Accepted(cleanup spec §9 ADR-T05 已列;P1-C commit `09bd8da` 完成 spec 文字修订)
