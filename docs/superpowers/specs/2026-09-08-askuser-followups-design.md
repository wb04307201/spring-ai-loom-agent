# AskUser 四项后续调整 — 设计文档

> **日期**:2026-09-08
> **触发**:用户在 #1 AskUser 工具落地 + 两轮全面测试后,提出 4 项调整,要求"先调整项目,再跑第三轮全面测试"
> **状态**:设计已批准(用户 2026-09-08 拍板),待写实现计划
> **上游**:`docs/superpowers/specs/2026-09-08-askuser-tool-design.md`(#1 主 spec,D1-D9)、`docs/superpowers/reports/2026-09-08-askuser-comprehensive-test.md`(第二轮测试报告)
> **下游**:本 spec 落地后执行第三轮全面测试(R0-R5,设计已在会话中呈现)

---

## 0. 范围与四项需求

| 节 | 需求(用户原话要点) | 用户已拍板方向 | 改动面 |
|---|---|---|---|
| §1 | askUser 卡片触发时出现在 AI 回复下方,用户选择后能否"消失/折叠/隐藏" | **折叠成一行摘要**,点击展开回看 | 纯前端(app.js + style.css)+ 契约测试 |
| §2 | 控制台日志功能里没有卡片相关的日志记录 | **复用 `loom_tool_call_log` + 日志页 UI 增强** | 后端只读查询接口 + 路由 + stats 页区块 |
| §3 | 用户类型是管理员的用户现在也应该能分配角色 | **admin 可被分配角色**;追问"admin 的 MCP 工具是否也该按角色控制" → 核验:**已经是了**(M3 起 strict RBAC 无 admin bypass) | 纯前端(console.js)+ 一个 `@Deprecated` 注解 |
| §4 | V1.0__init.sql 里添加两个技能市场初始化技能:STAR-IJ 法则(讲清一件事)、靶心人公式(讲好一个故事),都用一问一答方式 | **market_skill 官方已审**(author=system / status=APPROVED / is_official=TRUE / created_by_kind=ADMIN / category=`表达沟通`) | V1.0 尾部种子段 + 内容创作 |

**不在本次范围**(已记录,用户未裁决或明确推迟):
- `loomAgentProperties` 手动拷贝漏 setter(`subtask/schedule/time/file/skill/git`)—— 第二轮测试报告 follow-up #2,修它会改 6 个其它功能行为,超范围
- 服务端 answer 长度上限(follow-up #3)、多标签页卡片状态同步(follow-up #4)
- 概览图 PNG 重生成(待 `DASHSCOPE_API_KEY`;本次 4 节均不新增工具组,图无失真)
- 全项目 i18n(follow-up #1)

---

## 1. §1 askUser 卡片答后折叠成一行摘要

### 1.1 现状(已核验)

`app.js` 的 `askUserCards` IIFE(L1613 起)在终态调用 `freeze(qid, stateText, ok)`(L1622),freeze 只:禁用 inputs、写徽章文案、**隐藏提交按钮**(第二轮修复 `8876b2f`)。卡片 DOM(`.askuser-card`)完整留在对话流里。

5 种终态文案(4 个 freeze 调用点 + cancelAllActive 转发):

| 调用点 | stateText | ok | 触发场景 |
|---|---|---|---|
| L1678 | `已答 ✓` | true | 用户提交成功 |
| L1681 | `已失效(超时或已取消)` | false | 提交返回 404 |
| L1760 | `已超时` | false | 倒计时归零 |
| L1772 | reasonText(`已结束` / `已取消`) | false | cancelAllActive —— 流结束/用户点 stop/切换会话 |

问题:长对话里多张已答卡片堆积,把后续 AI 回复推远;用户回滚阅读时视觉噪音大。

### 1.2 设计

**终态折叠成一行摘要,点击 toggle 展开完整卡片。** 不删除 DOM(答案/问题需可回看,且 ChatMemory 刷新后文本记录仍在,卡片是视觉补充)。

**DOM 结构变化**:

```
.askuser-wrap                      ← 新增外层容器(render 时创建)
├── .askuser-summary               ← 新增,默认 display:none,freeze 时显示
│     [图标] 问题文本 → 答案/状态  [▸]
└── .askuser-card#askuser-{qid}    ← 原卡片,freeze 时 display:none
```

**freeze() 签名扩展**:

```js
function freeze(qid, stateText, ok, answerText)
```

- `answerText`:仅"已答"路径传(提交成功时的用户答案字符串/数组 join);其余 4 个调用点传 `null`/省略
- freeze 末尾:隐藏 `.askuser-card`、填充并显示 `.askuser-summary`

**摘要行内容规则**:

| 终态 | 图标 | 摘要文本 | 配色 |
|---|---|---|---|
| 已答 ✓ | `✓` | `{question} → {answerText}` | 绿(`--success-color`) |
| 已超时 | `⏳` | `{question} → 已超时,未作答` | 灰(`--text-secondary`) |
| 已失效(404) | `✗` | `{question} → 已失效` | 灰 |
| 已结束 / 已取消 | `✗` | `{question} → {stateText}` | 灰 |

- 多选答案 `answerText` = 各选项用 `、` join
- 问题/答案文本超长时 CSS `text-overflow: ellipsis` 单行截断(展开后看全文)
- **全部文本走 `escapeHtml`**(question 来自 LLM、answerText 来自用户,均不可信)

**展开/收起交互**:整行 `.askuser-summary` 可点击(含 `▸`/`▾` 指示符切换),toggle 原卡片 `display`。复用 thinking-container 的展开先例(项目内已有同类交互)。展开后卡片保持终态(inputs 禁用、无提交按钮),只读回看。

### 1.3 约束

- **零后端改动** —— `AskUserEvent` SSE 帧、answer 端点、Registry 全部不动
- 不影响既有语义:提交防重、restorePending 重试(非 404 失败不冻结 → 不进摘要)、404 no-op
- `restorePending` 路径(可重试失败)**不调用 freeze** → 卡片保持可交互,摘要行保持隐藏 ✅

### 1.4 测试

- 契约测试 `AskUserCardCollapseContractTest`(test 模块,镜像 `AskUserCardStyleContractTest` 的静态读文件断言风格):
  - `app.js` 含 `.askuser-summary` 与 `.askuser-wrap` 结构
  - `freeze(` 签名为 4 参(含 `answerText`)
  - `style.css` askUser 块含 `.askuser-summary` 规则,且使用真实 token(不得出现 `var(--primary,`)
- Chrome 复验(合成 SSE 帧驱动,零 LLM 成本):已答 → 摘要显示答案 + 点击展开;超时 → 摘要显示"已超时";stop → 摘要显示"已取消";XSS payload 在摘要行同样被转义

---

## 2. §2 日志页展示提问卡片记录

### 2.1 核验结论:数据已存在,缺展示

`LoggingToolCallback` 包裹**所有** tool callback(含 askUser),每次调用落 `loom_tool_call_log`:

```sql
CREATE TABLE loom_tool_call_log (
  log_id, conversation_id, username, tool_call_id, tool_name,
  arguments_json CLOB, result_text CLOB, result_is_error, duration_ms, created_at
);
```

askUser 调用行里:
- `tool_name = 'askUser'`
- `arguments_json` = LLM 传的完整参数(question / optionsJson / multiSelect / header / background / allowCustomInput)
- `result_text` = 工具返回值,前缀即状态:`[用户已回答] xxx` / `[用户未作答] xxx` / `[提问被中断] xxx` / `[提问失败] xxx`
- `duration_ms` = **含用户思考 + 作答的阻塞时间**(可达 timeout 上限 300s)

`admin/stats.html`(侧栏"📈日志")当前只渲染用量统计(`stats.js` 的 `load()` → `renderTable()` / `renderBarChart()`),没有提问维度视图。

### 2.2 设计

**只读查询,不新增表、不改写入路径。**

**后端**:

1. 接口 `IAskUserLogQuery`(`cn.wubo.spring.ai.loom.agent.askuser`):
   ```java
   List<AskUserLogRecord> recent(int limit, String usernameOrNull);
   ```
2. 实现 `JdbcAskUserLogQuery`(JdbcTemplate,镜像仓库既有 JDBC 实现风格):
   ```sql
   SELECT log_id, conversation_id, username, arguments_json, result_text, duration_ms, created_at
   FROM loom_tool_call_log
   WHERE tool_name = 'askUser'
     [AND username = ?]
   ORDER BY created_at DESC
   LIMIT ?
   ```
   `LIMIT ?` 用 H2 兼容写法(仓库已有先例);limit 上限钳制(如 ≤200)防滥用。
3. 记录 `AskUserLogRecord`:
   ```java
   record AskUserLogRecord(long logId, String conversationId, String username,
                           String question, String header, String answerText,
                           String status, long durationMs, java.time.Instant createdAt) {}
   ```
   (`createdAt` 用 `Instant` 与既有 `ToolCallLog` record 一致,Jackson 默认 ISO-8601 序列化)
   - `question` / `header`:从 `arguments_json` 解析(Jackson `readTree`,解析失败 → question 置 `"(解析失败)"`,不抛)
   - `status`:从 `result_text` 前缀推导 —— `[用户已回答]`→`ANSWERED` / `[用户未作答]`→`TIMEOUT` / `[提问被中断]`→`CANCELLED` / `[提问失败]`→`FAILED` / 其它→`UNKNOWN`
   - `answerText`:ANSWERED 时取前缀之后的正文;其余为 null
4. Bean 注册:`ToolConfiguration`(或 `StorageConfiguration`)`@Bean @ConditionalOnMissingBean`
5. 路由:`GET /spring/ai/loom/admin/ask-logs?limit=&username=` → `WebConfiguration` 新增 RouterFunction,**落在 `adminPathPatterns`(`/spring/ai/loom/admin/**`)门禁内自动 admin-only**,无需额外校验代码
6. 返回 JSON 数组;时间序列化与仓库既有 admin 路由一致

**前端**(`admin/stats.html` + `stats.js`):

- 新增"提问卡片"区块(section),独立于用量统计,页面加载时并行 fetch
- 表格列:时间 / 用户 / 问题(截断,hover title 全文) / 答案或状态徽章 / **等待时长** / 会话 ID(截断)
- **时长标注 = `等待 Ns`**(用户拍板):durationMs 含用户作答阻塞时间,标"耗时"会误导成系统性能问题;≥60s 显示 `等待 Xm Ys`
- 状态徽章配色沿用 §1 摘要语义(已答绿 / 超时灰 / 取消灰 / 失败红)
- 顶部可选 username 过滤输入 + 刷新按钮;默认 limit 50
- 空态文案"暂无提问记录";fetch 失败显示 HTTP 状态(镜像 stats.js 既有错误处理)
- 所有渲染走 `escapeHtml`(stats.js 已有该函数)

### 2.3 测试

- 单元:`JdbcAskUserLogQueryTest`(H2 内存或 mock JdbcTemplate)—— 4 种 result_text 前缀 → 4 种 status;arguments_json 畸形 → 不抛、question 兜底;username 过滤生效;limit 钳制
- 路由:`AskUserLogRouterTest`(真 router + 假 IAskUserLogQuery,镜像 `AskUserRouterTest` / `AdminRouterSpotTest` 先例,无 Spring 上下文)
- 自动配置:bean 在 `LoomAgentConfiguration` 切片测试中存在
- Chrome:真实触发一次 askUser 作答 → 日志页出现该记录,等待时长与实际作答时间量级一致

---

## 3. §3 admin 用户可被分配角色

### 3.1 核验结论:后端早已支持,唯一障碍是前端 early-return

- `DefaultRoleService.setUserRoles(username, roles)`(L80-98)**无任何 user type 检查**
- `setUserRolesOrSkipAdmin`(L108-114)名字是历史残留 —— M5 删除 admin 短路后,它**就是委托 `setUserRoles`**,无 skip 逻辑
- M3 起 strict RBAC 无 admin bypass:`getVisibleMcpsForUser` / `getVisibleToolsForUser` 对 admin 同样按角色过滤(三重证据:M3 代码注释、`SyncMcp.getVisibleToolCallbackProvider` 走 role-gated、活体测试无角色 admin → 0 capability)
- **用户追问"admin 的 MCP 工具也应该根据角色控制才对吧"→ 答案:已经是了,无需改动。** 本次修完前端后,admin 分配角色 → 角色授权 MCP/工具 → admin 可见能力随之变化,链路完整闭环
- `admin/console.js` `openAssignRole(username, type)`(L401-411)对 `type === "ADMIN"` early-return:显示过时文案"管理员账号默认拥有全部 MCP 服务,无需分配角色。"、清空列表、隐藏保存按钮、**从不加载角色**

### 3.2 设计

1. **删除 console.js 的 ADMIN early-return 块**(L404-411 的 `if (type === "ADMIN") {...}`),admin 与普通用户走完全相同的角色分配流程(加载全部角色 + 已分配角色 → 勾选 → 保存)
2. 提示文案统一为既有的"勾选要分配给该用户的角色(可多选)。用户实际可用的 MCP = 所有已选角色授权 MCP 的并集。"—— 对 admin 补一句语义提示:`管理员同样受角色授权约束(strict RBAC),未分配角色时仅平台默认能力(universal 工具)可用。`(渲染在 assignHint,仅 type=ADMIN 时追加)
3. `setUserRolesOrSkipAdmin` 标 `@Deprecated`,javadoc 注明"名字是历史残留,行为等同 setUserRoles;下一 minor 版本删除"(对齐 T3 v1 shim policy:1 minor version)
4. **后端行为零改动** —— 不改 setUserRoles、不改 RBAC 查询

### 3.3 风险与边界

- 给自己(当前登录 admin)移除全部角色 → 立即失去 admin 页访问能力(`adminPathPatterns` 校验 type=ADMIN,与角色无关 → **仍能进 admin 页**,但 capability 为 0 + universal)。type 校验不依赖角色,所以不存在"把自己锁死在控制台外"的风险,无需额外守卫
- 既有 IT(`RoleIntegrationTest` 等)断言的是 setUserRoles 行为,不受前端改动影响

### 3.4 测试

- 单元:`@Deprecated` 注解存在性(反射断言,防误删)
- Chrome:admin 用户打开"分配角色" → 角色列表加载、勾选保存、刷新后保持;给 admin 分配一个带 MCP 授权的角色 → 该 admin 聊天面板工具列表出现对应 MCP

---

## 4. §4 V1.0 种子两个一问一答技能

### 4.1 落点与写法(已核验 schema)

`market_skill` 基础列在 V1.0 L207 CREATE TABLE,扩展列由同文件 ALTER 追加:`is_official`(L644)/ `featured_rank`(L647)/ `category`(L650)/ `created_by_kind`(L653)/ `updated_at`(L739)。种子段放**文件尾部**(loom_vector_store 段之后),所有列此时均已存在。

镜像默认 admin 种子(L262)的幂等写法:

```sql
-- =============================================================
-- ==== 官方种子技能:一问一答表达训练(#4 follow-up)====
-- fresh-DB 政策:本段只在全新库执行一次;WHERE NOT EXISTS 幂等防重跑。
-- =============================================================

INSERT INTO market_skill (name, description, content, author, status,
                          reviewed_at, reviewed_by,
                          is_official, created_by_kind, category)
SELECT 'STAR-IJ 讲清一件事', '<description>', '<content>',
       'system', 'APPROVED', CURRENT_TIMESTAMP, 'system',
       TRUE, 'ADMIN', '表达沟通'
WHERE NOT EXISTS (SELECT 1 FROM market_skill WHERE author = 'system' AND name = 'STAR-IJ 讲清一件事');

-- 靶心人公式 同构第二条
```

- `UNIQUE(author, name)` 与 WHERE NOT EXISTS 双保险
- 不写 `featured_rank`(默认 0)、不写 `submitted_at`/`updated_at`(默认 CURRENT_TIMESTAMP)
- 用户从市场 pull 后成为其 `user_skill`(MARKET_PULLED),走既有链路,**无需额外接线**

### 4.2 内容设计约束(项目 memory 三铁律)

1. **不硬编码工具名** —— 技能文本描述"向用户提问的能力"(如"使用你的提问工具/发起一次单选提问"),让 LLM 从 function schema 自选;绝不写 `askUser` 字样(Spring AI 按方法名注册,写死易与实际注册名不符 → tool_call 静默失败)
2. **防自白死循环** —— qwen `enable_thinking: true` + 冗长模板 → 空 content + finishReason=STOP。必须含:①"不要描述你打算做什么,直接发起提问";②"**每次提问后等待用户真实回答,不得代替用户作答、不得自问自答**";③"信息足够时立即进入汇总,不要为凑步数继续提问"
3. **纯文本 markdown** —— `.st`/content 是纯文本,不用裸 HTML(`<a href>` 等),链接用 `[text](URL)`

通用结构(两个技能一致):触发条件 → 执行纪律 → 分步提问流程(每步:问什么、引导选项、追问策略)→ 汇总产出格式 → 边界(用户拒答/跑题时怎么办)。

### 4.3 技能一:STAR-IJ 讲清一件事

**定位**:帮用户把一件事(项目复盘、绩效述职、面试回答、经验沉淀)讲清楚。6 步顺序一问一答,每步一次提问(单选/多选 + 允许自由输入),答完汇总。

| 步 | 维度 | 提问要点 | 引导选项示例 |
|---|---|---|---|
| 1 | **S 情境** | 事情发生的背景:时间、场合、当时的局面 | 项目启动期 / 攻坚期 / 收尾期 / 自由输入 |
| 2 | **T 任务** | 你在其中的角色与要达成的目标 | 负责人 / 核心执行 / 协作支持 / 自由输入 |
| 3 | **A 行动** | 你具体做了什么(拆成 2-4 个关键动作,可多轮追问) | 多选:方案设计 / 资源协调 / 技术攻坚 / 沟通推进 / 自由输入 |
| 4 | **R 结果** | 可量化的成果(数字、对比、评价) | 追问:有没有具体数字?对比之前改善多少? |
| 5 | **I 项目价值** | 这件事对团队/业务/用户的意义 | 提效 / 降本 / 增收 / 风险控制 / 体验改善 / 自由输入 |
| 6 | **J 个人成长** | 你从中学到什么、能力有何提升 | 方法论沉淀 / 技术突破 / 协作能力 / 认知升级 / 自由输入 |

**汇总产出**:结构化叙述(S/T/A/R/I/J 六段,每段 2-4 句),末尾附"一句话版本"(30 秒电梯稿)。用户可要求针对特定场景(面试/述职)调整语气。

**边界**:用户某步拒答或答"不知道" → 记录该步为略过,继续下一步,汇总时标注;用户跑题 → 温和拉回当前步骤;信息已足够 → 立即汇总。

### 4.4 技能二:靶心人公式 讲好一个故事

**定位**:许荣哲"靶心人公式"7 步引导用户把经历讲成一个有张力的故事。**已网络核实原版 7 步**:目标 → 阻碍 → 努力 → 结果 → 意外 → **转弯**(原词是"转弯"非"转折")→ 结局。

| 步 | 维度 | 提问要点 |
|---|---|---|
| 1 | **目标** | 主角(通常是你)想要什么?一句话目标 |
| 2 | **阻碍** | 什么在阻挡你?(人/事/环境/自身) |
| 3 | **努力** | 你为克服阻碍做了什么?(可 1-3 轮追问细节) |
| 4 | **结果** | 努力的直接结果如何?(常见:没成功/部分成功 —— 这正是故事转折点) |
| 5 | **意外** | 出现了什么意料之外的事? |
| 6 | **转弯** | 这个意外让你/局面发生了什么转变?(认知、策略、关系) |
| 7 | **结局** | 最终收场如何?你希望听众记住什么? |

**快速变体**(技能内说明,按用户素材复杂度自选):
- **努力人公式**(4 步):目标 → 阻碍 → 努力 → 结局 —— 简单场景/时间紧
- **意外人公式**(4 步):目标 → 意外 → 转弯 → 结局 —— 突出反转的故事

**汇总产出**:连贯故事文本(300-600 字),保留用户原话的关键细节;可选输出"故事骨架"(7 步各一行)供用户二次创作。

**边界**:同 STAR-IJ(拒答跳过、跑题拉回、够了就写)。用户素材实在没有"意外/转弯" → 建议改用努力人公式,不硬编。

### 4.5 description 字段

`description` 语义 = LLM 用的内容摘要(触发判断),不是用户标签:
- STAR-IJ:`用户想"讲清楚一件事"(项目复盘/绩效述职/面试准备/经验沉淀)时触发:按 情境-任务-行动-结果-项目价值-个人成长 六步一问一答收集信息,汇总成结构化叙述`
- 靶心人:`用户想"讲好一个故事"(品牌故事/演讲/个人经历分享)时触发:按 目标-阻碍-努力-结果-意外-转弯-结局 七步一问一答引导,汇总成有张力的故事`

### 4.6 落地代价(fresh-DB 政策)

合入后当前运行实例必须清库重跑:`rm -rf ~/.loom/datasource` + 重启 8080 实例。副作用:知识库文档需重传(一次性 re-embed)、既有测试数据清空。这是项目既定政策(不接受增量升级 schema),非本次新增成本。

### 4.7 测试

- IT:清库启动后 `market_skill` 存在 2 条 author=system / status=APPROVED / is_official=TRUE / category=表达沟通 的行;重复执行迁移不产生重复行(幂等)
- 内容静态断言(test 模块,读 V1.0 资源文件):两条 content 不含 `askUser` 字面量(铁律 1)、含"不得代替用户作答"类反自白句(铁律 2)、不含裸 `<` HTML 标签(铁律 3)
- Chrome:admin 市场页看到两个官方技能(带官方徽章);普通用户 pull STAR-IJ → 选中该技能发起对话 → LLM 按 6 步逐问(真实 LLM 验证,观察是否自问自答)

---

## 5. 执行顺序与依赖

四节相互独立,可并行实现,但建议顺序:

1. **§3**(最小:删一个 if 块 + 一个注解)→ 快速见效
2. **§1**(前端折叠,与 §2 无耦合)
3. **§2**(后端查询 + 日志页;§1 的摘要语义/配色被 §2 徽章复用,先定 §1)
4. **§4**(种子内容创作,最重,但完全独立;放最后避免阻塞前三节)

回归门(每节落地后):库单元 + test 模块单元 + 清库 IT gate(三段全绿,基线:175 / 397 / 123-3skip)。

全部落地后:更新 CLAUDE.md(§4 种子技能、§2 日志页职责、§3 admin 角色分配)+ roadmap 落地记录,然后执行**第三轮全面测试**(R0-R5:MCP 启用环境差异、RAG 首次活体、E1-E5 新边界维度 —— 登出/刷新/断网/双用户/定时任务期间挂起提问)。

---

## 6. 决策记录

| # | 决策 | 备选 | 理由 |
|---|---|---|---|
| D1 | §1 折叠成一行摘要(可展开) | 完全消失 / 隐藏到侧栏 | 用户拍板;答案可回看且不占视觉 |
| D2 | §2 复用 `loom_tool_call_log` | 新表 `loom_ask_log` | 用户拍板;数据已被 LoggingToolCallback 完整记录,新表 = 双写冗余 |
| D3 | §2 时长标注 `等待 Ns` | `耗时 Ns` / 两段显示 | 用户拍板;durationMs 主体是用户作答阻塞,标"耗时"误导 |
| D4 | §3 仅删前端 early-return | 后端加 admin 角色守卫 | 后端本就支持且 strict RBAC 已闭环;加守卫是倒退 |
| D5 | §4 种子进 `market_skill`(官方已审) | 进 `user_skill`(V1.1 先例) | 用户拍板;市场可见 + 可 pull + 官方徽章,比塞给单个用户合理 |
| D6 | §4 category = `表达沟通` | 职场表达 / 沟通与写作 | 用户拍板;覆盖两个技能共同主题且不过窄 |
| D7 | §4 靶心人用"转弯"(非"转折") | — | 网络核实许荣哲原版用词 |
| D8 | §1 restorePending(可重试失败)不折叠 | 一律折叠 | 卡片仍需交互,折叠会吞掉重试入口 |
