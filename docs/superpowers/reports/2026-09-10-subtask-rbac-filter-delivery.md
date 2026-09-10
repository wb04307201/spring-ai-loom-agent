# 子任务/定时任务 RBAC 过滤修复 交付记录 — 2026-09-10

> SDD 执行(spec → plan → 5 Tasks → 每任务评审 → 1 fix 轮 → opus 最终全分支评审)
> Spec: `docs/superpowers/specs/2026-09-10-subtask-rbac-filter-design.md`(D1-D8)· Plan: `docs/superpowers/plans/2026-09-10-subtask-rbac-filter.md`
> 分支 `dev`,实现 commits `a248bc5..d95c37f`(6 个;另 1 个 plan fence-fix docs commit)
> 性质:**安全修复**(pre-existing RBAC 绕过;IHtmlRenderTool 终审 finding 5 发现,独立循环修复)

## 缺陷与修复

**缺陷**:`DefaultSubTaskExecutor.doExecute` 把全量本地 embed 工具(仅剔 3 个自身工具)交给子任务/定时任务的 LLM,**不按角色授权过滤** —— 而 subtask/schedule 工具是 universal,任何用户可发起。未授权 `tool_git`/`tool_maven`/`tool_compile`/`tool_render` 的用户可经定时任务在无人值守时执行 git push / 任意构建 / 起 Docker 容器 / 起无头浏览器。同方法内 MCP 半边本来就有过滤,executor 类注释自述意图 "available to the user" —— embed 半边从未兑现。

**修复**(spec D1-D6 全落地):
1. `CapabilityService` 新增共享 helper:`toolGroupIdOf(Object)`(static)+ `filterEmbedToolsByCapabilityIds(List, Set)` —— 消除第三份反射过滤副本(`a248bc5`)
2. `DefaultChat` 内联 13 行过滤 → 一行 helper 调用,三分支等价性逐支核验(`2ffc2b2`)
3. executor 两步过滤:RBAC(`visibleToolGroupsFor` = 角色授权 ∪ universal)在前,既有 instanceof 递归守卫逐字不动在后;Caffeine 去重 WARN(512/1h,key=user+sorted-dropped,`putIfAbsent` 原子);`@Lazy CapabilityService` 破构造器环(`f7b49d6`)
4. `SubTaskRbacFilterIT`:真 DB `role_tool` 授权翻转 → `ArgumentCaptor` 捕 `spec.tools()` 断言 `IGitTool` 不在场/在场;正向 pin `ITimeTool` 防空过断言(fix 轮 `a1819cb`);兼作 @Lazy 破环启动门(上下文 2.389s 加载实证)
5. 文档三处同步:CLAUDE.md `ISubTaskExecutor` 行 + SUBTASK-SCHEDULER 双语 L11(把"与主对话相同工具访问"从假改真)(`d95c37f`)

## 验证(全绿)

| 门 | 结果 |
|---|---|
| 单测 | lib **195/0**(191+4 helper)· test 模块 **438/0**(435+3 executor) |
| IT gate(清 test-ds) | **132/0/3skip**(3 skip 全 pre-existing 环境跳过;SubTaskRbacFilterIT 真跑;HtmlRenderEngineIT 真 Chromium 3/3) |
| 最终评审(opus 全分支) | **可合并** — 0 Critical / 0 Important / 3 Minor(全 ACCEPT/FOLLOW-UP) |

**最终评审跨切面走查结论**(per-task 门看不到的):
- **安全完整性**:三条攻击路径(LLM 子任务 / 定时触发 / 重启 rehydration)全部收敛到同一个已过滤的 `doExecute`;全仓 `List<IEmbedTool>` 注入点穷举仅 3 个消费者(DefaultChat ✅ / executor ✅ / CapabilityService 仅反射列举不进 LLM);全仓 `chatClient.prompt()` 仅 2 个调用点;ChatClient builder 无 defaultTools 隐式泄漏 → **无第四个未过滤面**
- username 取自 ToolContext(框架注入)非 LLM 参数 → 无身份伪造面
- WARN 去重信号方向正确(撤销授权 → dropped 变大 → 新 key → WARN 一次);@Lazy 代理冷启动并发无竞态(singleton 创建同步 + ApplicationReadyEvent 时序);行为变更爆炸半径 = 只有越权能力被收回,无静默数据损坏角度(工具缺席 → Spring AI 不派发 → 任务如实 FAILED 进 `loom_schedule_execution`)

## Parked minors 终裁(opus)

M1(dropped List 重复 id)ACCEPT · M2(第二次遍历不并入 helper,保 DRY 契约)ACCEPT · @MockBean deprecated FOLLOW-UP(全仓批量迁移 @MockitoBean 时一起)· 新发现:`toLocalCapability` 对未标注 bean NPE(pre-existing,与 filter 的 fail-open 不对称)ACCEPT + FOLLOW-UP(契约测试锁"每个 IEmbedTool bean 必带 @ToolGroup")· WARN 去重单测只覆盖换 user 腿(换 dropped 集同代码路径)ACCEPT · zh-CN 文档 :3 链接 label 笔误(pre-existing)FOLLOW-UP

## Follow-up 清单(累计,待用户裁决优先级)

**安全加固类:**
1. lib 侧契约测试:每个库注册 `IEmbedTool` bean 必带 `@ToolGroup`(把隐式不变量变成受检;顺带暴露 toLocalCapability NPE 不对称)
2. `file://` iframe 渲染 IT(IHtmlRenderTool 终审建议)
3. 执行器层 ThreadLocal 嵌套深度守卫(本轮 D7 裁决 YAGNI,防假想的自定义 bean 绕过 instanceof)

**pre-existing 卫生类(与 IHtmlRenderTool 轮合并):**
4. `loomAgentProperties` 手动拷贝块漏 `subtask`/`schedule`(2bd0b5d 同类)
5. `LoomAgentConfiguration` by-path router 重复 `getOrCreateFileId`(可走 FileIdBridge)
6. `BatchedCounterServiceTest` 时序 flake(本轮零复现,仍值得独立排查)
7. `@MockBean` → `@MockitoBean` 全仓批量迁移
8. 文档漂移:TOOLS.md TOC stale 块 / TOOLS.zh-CN.md 编号跳变 / SUBTASK-SCHEDULER.zh-CN.md:3 链接 label / CLAUDE.md ToolConfiguration 行 bean 计数
9. `.gitattributes *.sh eol=lf`;概览图 PNG 重生成(待 DASHSCOPE_API_KEY,generate.py 布局已就绪)

## 环境

8080 实例仍运行 IHtmlRenderTool 轮 HEAD(`799c0ea`)build、纯净种子态 —— 本轮修复**未部署**到该实例(需要时重启即拾取 `d95c37f`)。test-ds 已清。SDD workspace 已按惯例删除。
