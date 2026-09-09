# HTML 渲染截图工具(IHtmlRenderTool)交付记录 — 2026-09-10

> SDD 执行(spec → plan → 7 Tasks → 每任务双评审 → opus 最终全分支评审 → 1 fix 轮 + scoped re-review)
> Spec: `docs/superpowers/specs/2026-09-09-html-render-tool-design.md` · Plan: `docs/superpowers/plans/2026-09-09-html-render-tool.md`
> 分支 `dev`,commits `761f222..799c0ea`(plan commit `7b07d95` 之后 9 个)

## 交付物

**新 RBAC 工具 `renderHtmlFile`**(capability `tool_render`,工具组 10→11):LLM 先用既有 `writeFile` 写自包含单页 HTML,再调本工具用无头 Chromium(Playwright 1.50.0,lib 侧 optional 依赖 + `@ConditionalOnClass` 门控,无 yml enabled 开关)渲染成 PNG 存 `{fileBasePath}/{username}/prototypes/{name}-{ts}.png`,经 FileIdBridge(从 DefaultFileTool 抽取,逐字零行为变化)桥接 `usage='temp'` fileId,返回三行契约(渲染成功/预览链接/markdown 内嵌片段)。失败分支全部返回文本绝不抛异常(D8)。

| 组件 | 落点 |
|---|---|
| `RenderProperty` 配置(6 键)+ setRender 手动拷贝守卫 | `LoomAgentProperties` / `LoomAgentConfiguration`(T1 `761f222`) |
| `HtmlRenderEngine`:sql-forge 骨架移植 + 三级 Chromium 探测(chromium-path→默认缓存→channel)+ route abort/CSP 双保险网络屏蔽 + Semaphore(1) + IHDR 尺寸解析 | lib `tool/render`(T2 `f584198`) |
| `IHtmlRenderTool`(@ToolGroup render,RBAC)+ `DefaultHtmlRenderTool`(沙箱/白名单/存图/桥接)+ `FileIdBridge` 抽取 | lib(T3 `1a190a6`) |
| 双 bean 注册(destroyMethod="close")+ autoconfigure pom optional | autoconfigure(T4 `7e33ae6`) |
| `docs/provision-chromium.sh` + TOOLS×2/README×2/CLAUDE.md + generate.py 布局 11 TOOLS(PNG 重生成仍 deferred) | docs(T6 `7c9a679`+`17cc9fa`) |
| fix 轮:引擎生命周期加固(崩溃恢复关陈旧 holder/超时孤儿就地关/Error 路径 pw.close/null message 兜底)+ injectCsp 防 script 字符串诱骗 + provision 改 Playwright `install-deps`(Ubuntu 24.04 t64)+ timeout-seconds 语义纠偏 | T-final(`1ab0d5d`+`799c0ea`) |

**零 V1.0 schema/seed 改动**(spec 约束):`tool_render` 由 admin 在角色授权页动态发放;纯净种子态下 base 角色不含它(capabilities 可见、effEnabled=false)。

## 验证(三门 + 冒烟,全绿)

| 门 | 结果 |
|---|---|
| 单测(gate#1 `mvn clean install` scoped) | lib **191/0**(190+fix 轮新 CSP 单测)· test 模块 **435/0**(412 基线 +23:binding 3 / 工具契约分支 15 / RBAC 契约 4 / autoconfig 替换 1) |
| IT gate(gate#2,清 target/test-ds) | **131/0/3skip**(3 skip 全 pre-existing 环境跳过);`HtmlRenderEngineIT` **真跑 3/3**(真 Chromium:中文页 PNG magic+2880×1800 / 本机 HttpServer 探测外联 **0 命中** / fullPage ≥7900px) |
| 活体冒烟(gate#3,真 qwen3.8-max,清库 fresh) | 全链路 PASS:capabilities 现 `tool_render`(false)→ admin PUT 授权(true)→ LLM 自主 writeFile→renderHtmlFile → 服务端日志"Chromium 启动成功(Playwright 默认缓存)"二级探测命中 → PNG 2880×1800 IHDR 校验 + download 端点回 image/png 原字节 → LLM 回复含 markdown 片段;**冒烟后清库还原纯净种子态**(4/9 caps,base tools=[]) |

## 评审结论

- 每任务评审(T1-T6)全 Approved,0 Critical/Important;唯一 fix round 是 T6 文档 EN §2 对齐(2 行)。
- **最终全分支评审(opus):可合并**。跨切面抓到 2 个 Important(崩溃恢复 holder 泄漏 / provision 脚本 Ubuntu 24.04 t64 必坏)+ injectCsp 诱骗等 4 Minor —— 全部在 fix 轮落地,scoped re-review 逐项 ADDRESSED、DO-NOT-TOUCH 零触碰、并发窗口走查干净、provision.sh 提交 blob LF(CR=0)权威核验。
- 计划内裁决 R1-R5 + 执行期裁决(T4 lambda 编译修、T5 stale 报告数字、fix 轮 spec §3.3 修订)全程 ledger 记录。

## Follow-up 清单(待用户裁决,均非本特性阻塞项)

**安全/RBAC(pre-existing,最重要):**
1. **子任务/定时任务执行器缺 RBAC 过滤**(最终评审 finding 5):`DefaultSubTaskExecutor` 只排除 self-tools,未过 `visibleToolGroupsFor` → 未授权用户的定时/子任务可调 render/git/maven/compile,与 CLAUDE.md"子任务 tool 调用继承 user 角色"表述矛盾。class-wide 缺口,建议独立 ticket 修(一处过滤修全类)。
2. `file://` iframe 渲染 IT 缺口(评审建议:`<iframe src="file:///etc/passwd">` 渲染空白断言;当前靠 Chromium 自身策略,推理成立但无测试)。

**引擎硬化残余(评审 ACCEPT/FOLLOW-UP 档):**
3. M1 残余 TOCTOU:callable 恰在 `task.get` 超时前微秒级完成 → cancel 无效 → 孤儿窗口(re-review 观察项;概率极低,需 post-get reconciliation 才能根除)。
4. M4-M9 ACCEPT 档详见 ledger 归档(本文件末链接)——close/in-flight 无协调、`Paths.get(username)` try 外、`\p{Cntrl}` ASCII 域、engine bean 独立创建、契约测试 magic 4。

**pre-existing 卫生(与既往轮次合并裁决):**
5. `loomAgentProperties` 手动拷贝块仍漏 `subtask`/`schedule`(T1 评审证实,2bd0b5d 同类)。
6. `LoomAgentConfiguration` ~L4311 by-path router 处独立重复 `getOrCreateFileId`(可改走 FileIdBridge)。
7. `BatchedCounterServiceTest.concurrentIncrementDuringFlushDoesNotLoseUpdates` 时序 flake(T5 门复现一次,fix 轮前后各一次全绿;可能藏真实 drainBuffer 竞态,值得独立排查)。
8. 文档漂移:TOOLS.md TOC stale 重复块 / TOOLS.zh-CN.md body 编号跳变 / CLAUDE.md ToolConfiguration 行"10 个 bean 总是创建"列表实为 8+条件项(评审 nit)。
9. `.gitattributes` 加 `*.sh text eol=lf` 加固(provision.sh 当前 blob 已 LF)。
10. 概览图 PNG 重生成(需 DASHSCOPE_API_KEY;generate.py 布局已就绪 11 卡片)。

## 当前环境

8080 运行 HEAD(`799c0ea`)build、纯净种子态(Flyway fresh 2 migrations,wb04307201/123456,4/9 caps)。冒烟产物已清(datasource wipe + prototypes 删除)。

> SDD workspace(`.superpowers/sdd/2026-09-09-html-render-tool/`,含全程 ledger/评审包/报告)按惯例在干净收尾后删除;关键结论已固化于本文件。
