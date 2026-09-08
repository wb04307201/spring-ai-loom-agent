# AskUser 全面测试报告(2026-09-08,第二轮)

> 触发:用户要求"再规划一次全面的测试 —— 单元 + Chrome + 样式检测 + 其他维度"
> 范围:AskUser 深测 + 全应用回归抽查(用户拍板)
> 环境:Windows + Chrome DevTools MCP,真实 DashScope qwen3.8-max,全新库(`/tmp/loom-test-ds`),端口 8090,MCP 客户端关闭(隔离被测面)
> 方法:单元测试(JUnit)+ 集成测试(failsafe IT gate)+ Chrome 端到端(真实 LLM)+ evaluate_script 注入合成 SSE 帧(确定性驱动前端,零 LLM 成本)+ curl 安全探测

---

## 结论

**通过。** 发现并修复 **2 个新缺陷**(均为样式/响应式,单测与首轮复验未覆盖),其余维度全绿。AskUser 功能在功能/样式/安全/并发/兼容/可访问 6 个维度均达标,且对既有功能零破坏(全应用回归抽查通过)。

| 维度 | 结果 | 缺陷 |
|------|------|------|
| P0 基线回归门 | ✅ 库 175 / test 394→397 / IT 123(3 skip) | — |
| PA 功能深测(10 场景) | ✅ 全过 | — |
| PB 样式/视觉(6 项) | ✅(修 2 缺陷后) | **B1 CSS token 错名**、**B3 移动端裁切** |
| PC 安全(5 项) | ✅ 全过 | — |
| PD 并发(2 项) | ✅ 全过 | — |
| PE 全应用回归(11 项) | ✅ 全过 | — |

---

## P0 — 基线回归门

| 段 | 命令 | 结果 |
|---|---|---|
| 库单元 | `mvn test -pl spring-ai-loom-agent` | **175 run, 0 fail**(首跑现 1 flaky,连跑 2 次均 175/0;系既有 BatchedCounterService/LoggingToolCallback 模拟 DB 故障的计时型 flaky,与本次改动无关) |
| install | `mvn clean install -DskipTests`(排除 4 个 loom-*-mcp,Windows 文件锁既定先例) | BUILD SUCCESS |
| test 模块单元 | `mvn test -pl spring-ai-loom-agent-test` | **397 run, 0 fail**(394 + 本轮新增 AskUserCardStyleContractTest 3 用例) |
| IT gate | 清库起跑 `mvn test -Dtest='*IT'` | **123 run, 0 fail, 3 skip**(Maven 工具 IT 条件跳过) |

---

## PA — AskUser 功能深测(Chrome)

**确定性测试**(evaluate_script 注入合成 askUser SSE 帧 + hook answer 端点,精确断言,零 LLM):

| # | 场景 | 断言 | 结果 |
|---|------|------|------|
| DT1 | 单选点选即提交 | 1 次 POST,body `{"answer":"选项A"}`(纯字符串),徽章"已答 ✓",提交按钮隐藏,frozen=true | ✅ |
| DT2 | **提交失败可重试(A6)** | hook 返回 500 → 卡片**不冻结**、按钮回"提交答案"可见、倒计时继续(4:59)、badge 清空;改 200 重试 → "已答 ✓"按钮隐藏 | ✅ restorePending 路径活体验证 |
| DT3 | **404 竞态(A7)** | hook 返回 404 → frozen、徽章"已失效(超时或已取消)"、按钮隐藏、inputs 禁用;二次提交 no-op(calls 仍=1) | ✅ |
| DT4 | 空选守卫 | multiSelect+custom 未勾任何项点提交 → **0 次 POST**、不冻结、按钮保持"提交答案" | ✅ |
| DT5 | 多选+自定义 payload | body `{"answer":["Y","自定义答案Z"]}`(trim 生效、空值过滤),徽章"已答 ✓" | ✅ C1 修复复验 |
| DT6/D1 | **三连击提交防重** | 3 次 rapid click → **仅 1 次 POST**,body `["X","Y"]` | ✅ |
| DT7 | 倒计时过期(timeout=3s) | early 0:03 未冻 → 3.5s 后徽章"已超时"、frozen、按钮隐藏、inputs 禁用;过期后点击 0 POST | ✅ |

**真实 LLM 测试**(qwen3.8-max,全新库):

| # | 场景 | 结果 |
|---|------|------|
| A8 | **同轮连续两问** | card1"顺序"→已答✓,card2"命名"→已答✓,两卡片独立冻结互不串扰,LLM 合并"先部署服务A,数据库 app_db" | ✅ |
| A9 | **子任务排除 E2E(D6)** | 委派子任务要求"向用户提问颜色" → 子任务如实报告"当前工具列表中不存在任何用户交互工具…没有 askUser",**未编造答案**,建议主对话提问 | ✅ schema 级排除行为验证 |
| A10 | 答后交互恢复 | textarea/send 重新可用,sendBtn="发送消息",侧栏统计刷新 | ✅ |

---

## PB — 样式/视觉检测

| # | 项 | 结果 |
|---|------|------|
| **B1** | **CSS token 一致性** | ❌→✅ **缺陷已修**:卡片 CSS 用 `var(--primary, #6366f1)`,但项目 `:root` 定义的是 `--primary-color`(`--primary` 未定义)。当前渲染恰好同色(fallback 字面值 == token 值)纯属侥幸,主题化/换肤时会脱钩。修复:3 处改 `var(--primary-color, #6366f1)` + 新增 `AskUserCardStyleContractTest`(锁死不得再出现未定义 `--primary`)。commit `11b3f8a` |
| B2 | 视觉一致性 | ✅ chip 背景 == submit 背景 == `rgb(99,102,241)`(--primary-color);选项边框 `rgb(226,232,240)`(--border-color);桌面截图存档 |
| **B3** | **响应式** | ❌→✅ **缺陷已修**:`.askuser-card{min-width:320px}` 在 390px 手机视口撑破 bubble —— 卡片右溢视口 47px(cardFits=false)且 `docCanScrollH=false`(无法横向滚动查看 = 内容被永久裁切)。修复:`min-width:0` + `box-sizing:border-box`,卡片随 bubble 收缩。复验:390px(card 212px)/768px(315px)/1920px(387px,≤560 上限)全部 cardWithinViewport=true、docHOverflow=0。契约测试锁死。commit `a0e9bcf` |
| B4 | console 零错误 | ✅ 全流程 error/warn 零条 |
| B5 | 可访问性 | ✅ 4 个 radio 均 `<label>` 包裹(可访问名=label 文本),submit 有文本名,custom input 有 placeholder+type=text,倒计时文本可读 |
| B6 | i18n | ✅(裁决:跟随现状)卡片文案硬编码中文 —— app 的 `window.I18N.t` 是 **scoped** 提取(仅 12 处调用,主要 market/admin),聊天侧 `showToast` 同样大量硬编码中文("密码修改成功"等)。askUser 卡片与聊天侧现状一致,记为全项目 i18n follow-up,不阻断 |

---

## PC — 安全探测(curl + evaluate_script,零 LLM)

| # | 探测 | 结果 |
|---|------|------|
| **C1** | **XSS 注入** | ✅ initScript 注入恶意 payload(question=`<img onerror>`、header=`<script>`、background=`"><svg onload>`、label=`<img onerror>`、description=`<b onmouseover>`)→ 5 个执行标记 `__XSS_*` **全 undefined**,注入的 img/svg/script/b 元素数**全 0**,文本原样转义显示,console 零错。escapeHtml 全覆盖 |
| C2 | 未登录 POST answer | ✅ 401(AuthenticationFilter 拦截) |
| C3 | questionId 枚举 | ✅ 随机 UUID / 非 UUID → **统一 404 `{"error":"not found"}`**(防存在性泄露);路径穿越 `../../etc/passwd` → Tomcat 400(容器层拦,未达路由) |
| C4 | 超长 answer(5000 字) | ✅ 200 接受,流正常继续,LLM 收到并回复,布局无溢出(docHOverflow=0)。M4 已知:服务端无长度上限(自我伤害面:已认证用户/自己的流/自己的 LLM 上下文),记录不阻断 |
| C5 | 畸形 body | ✅ not-json / `{"answer":123}` / `{}` / `{"answer":"   "}` → 全 400 `{"error":"invalid answer"}` |

---

## PD — 并发探测

| # | 场景 | 结果 |
|---|------|------|
| D1 | 双击/三连击提交 | ✅ 仅 1 次 POST(按钮 fetch 前 disabled + freeze 删 active 双重防护) |
| D2 | 跨客户端作答(C4 同机制覆盖) | ✅ 直接 POST 到挂起 questionId(绕过卡片 UI)→ 后端 complete future、流继续、LLM 收到答案;前端卡片在流结束时由 cancelAllActive 冻结为"已结束"。**语义**:非提交标签页的卡片不会自动变"已答",随流结束定格 —— 可接受(答案已送达后端为准) |

---

## PE — 全应用回归抽查(确认 16 commit + `-parameters` 零破坏)

| 检查 | 结果 |
|---|---|
| 主聊天流 + 思考过程 | ✅ 多轮 askUser 对话流式正常 |
| **ITimeTool(getCurrentTime)** | ✅ 返回正确时间 —— **证明 `-parameters` 全项目生效后既有工具 schema 未坏** |
| **startSubTask 委派** | ✅ 子任务正常执行并返回 —— `-parameters` 后既有工具参数名修正无回归 |
| 工具弹窗 | ✅ 仅 3 个 RBAC 工具(compile/git/maven,带"本地"徽章),**askUser 等 universal 工具正确隐藏** |
| admin capabilities 端点 | ✅ 仅 tool_compile/git/maven,**askUser 不在列**(universal 不进 RBAC 授权 UI) |
| 文件管理模态框 | ✅ 打开正常("目录为空") |
| 知识空间模态框 | ✅ 打开正常 |
| 子任务面板 | ✅ 打开正常(子任务/sub-task 统计) |
| 定时任务面板 | ✅ 打开正常 |
| admin 控制台(console/roles/market-skills) | ✅ 加载正常,侧栏 6 链接,market-skills 空(全新库预期),console 零错误 |
| 新建对话 | ✅ 正常 |

> 说明:PE 中一次 `admin/users.html` 404 是**我的测试笔误**(真实文件名为 `user.html` 单数,侧栏实际链接 console.html),非缺陷。

---

## 缺陷汇总(本轮新发现,均已修复)

| ID | 严重度 | 描述 | 根因 | 修复 | commit |
|----|--------|------|------|------|--------|
| B1 | Minor(潜在) | 卡片 CSS 用未定义 `--primary` token,靠同色 fallback 掩盖 | T6 plan CSS 写错 token 名(项目是 `--primary-color`) | 3 处改对 + 契约测试锁死 | `11b3f8a` |
| B3 | Important | 390px 移动视口卡片撑破 bubble 被裁切 | `.askuser-card{min-width:320px}` 刚性下限 > 移动端 bubble 宽 | `min-width:0`+`box-sizing:border-box` + 契约测试 | `a0e9bcf` |

**注**:首轮复验(T7b)已修的 4 个缺陷(arg0 schema / 字面 null / 配置绑定 / 提交按钮卡"提交中")本轮全部回归验证通过,未复发。

---

## 遗留 follow-up(记录,不阻断)

1. **全项目 i18n**(B6):聊天侧文案硬编码中文,`window.I18N.t` 仅 scoped 覆盖 market/admin。统一 i18n 是独立技术债(M3+ T4 已起头)。
2. **`loomAgentProperties` 手动拷贝漏字段**(首轮复验发现):除已修的 `askuser` 外,`subtask/schedule/time/file/skill/git` 的 yml 覆盖同样被静默丢弃(既有潜在 bug,test-app 值恰等默认值而掩盖)。修它会改 6 个其它功能行为,**超出 askUser 范围,待用户裁决**。
3. **服务端 answer 无长度上限**(C4/M4):自我伤害面,可加 `length>2000→400` 守卫。
4. **多标签页卡片状态不同步**(D2):非提交标签页的卡片不自动变"已答",随流结束定格"已结束"。语义可接受。
5. **概览图 PNG 重生成**:布局脚本已改(工具组 9→10),PNG 待 `DASHSCOPE_API_KEY`。

---

## 测试证据存档

截图(`.superpowers/sdd/2026-09-08-askuser-tool/`):
- `test-C1-xss-escaped.png` — XSS payload 全部转义为字面文本
- `test-B2-desktop-visual.png` — 桌面视觉一致性
- `test-B3-mobile-390-fixed.png` — 移动端 390px 修复后卡片适配
