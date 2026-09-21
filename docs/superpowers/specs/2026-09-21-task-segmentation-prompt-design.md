# 任务分段执行 prompt 引导 — 设计规格

**作者**: Claude Code
**日期**: 2026-09-21
**状态**: Draft (待 review)
**目标版本**: v1.1.48 (loom-agent)
**关联提交**: 本规格是独立 feature。话题上紧接 2026-09-21 `fix(chat): friendlyMessage 加 JsonEOFException 分支`
（治已病），本规格治未病 —— 让 LLM 主动避开撞上限的任务形态。两次提交可独立 revert。

---

## 1. 背景与动机

### 1.1 触发事件

2026-09-21 生产事故：服务器侧 `conv=c3a65ce4-4fdc-430e-9c98-24daf020d5cc` 会话在长 reasoning
（`reasoningAccumLen=33591`）后触发 Spring AI 1.1.8 `ToolUseAggregationEvent.squashIntoContentBlock`
对不完整 tool_use JSON 强制解析，导致 `JsonEOFException`：

```
com.fasterxml.jackson.core.io.JsonEOFException: Unexpected end-of-input:
was expecting closing quote for a string value
at column 33252
```

### 1.2 本地复现

本地 `D:\developer\IdeaProjects\loom-agent` 端到端复现同一场景（同一问一答 + 同一 renderHtmlFile
路径），reasoning 涨到 62949 后静默 234 秒后触发 6 次同源异常，
column=34068，调用栈 `squashIntoContentBlock:2019` 与生产 byte-for-byte 一致。

### 1.3 触发三件套

| 条件 | 生产观测 | 本地复现 |
|---|---|---|
| 长 reasoning | 33591 chars | 62949 chars |
| 流截断（idle timeout / max_tokens / 代理 idle close） | 199 秒静默 | 234 秒静默 |
| tool_use 块未收到 CONTENT_BLOCK_STOP | ✓ | ✓ |

### 1.4 治理现状（已落地）

`DefaultChat.friendlyMessage` 已加 `JsonEOFException` 分支，单测
`FriendlyMessageJsonEofTest` 3 用例全绿 —— 把误导性"聊天服务异常（RuntimeException）"
文案换成可操作的 3 条建议（重发 / 调 max-tokens / 升级 Spring AI 2.0+）。

**但 friendlyMessage 是治标** —— 用户仍会撞 bug、re-quota 仍会浪费。本规格治另一面：**让
LLM 主动避开容易撞上限的任务形态**。

---

## 2. 设计目标

| ID | 目标 | 衡量 |
|---|---|---|
| G1 | LLM 在长 reasoning 风险任务中**主动**调 `start_sub_task` 拆分 | 单测锁定 prompt 含触发协议 |
| G2 | 不破坏短任务体验 —— 简单任务**不要**误触发拆任务 | 护栏 `≤ 5` + 触发协议 `≥3 个独立子目标` |
| G3 | 与现有 `【提问与澄清】` 段风格对称 | 走三层引导（正向场景 + 协议 + few-shot），对齐 CLAUDE.md § "三层引导共同提升触发率(2026-09-20)" 已验证机制 |
| G4 | 不引入 yml 新配置、不动 ISubTaskTool 接口 | git diff 限定 4 文件 + 1 单测 |
| G5 | 与 Spring AI 2.0+ 升级路径共存 | 不依赖 1.1.8 内部类型；只调公开 ChatResponse API |

---

## 3. 架构总览

| 模块 | 类型 | 文件 | 改动 |
|---|---|---|---|
| C1: system prompt 新增段 | system prompt | `spring-ai-loom-agent/.../chat/DefaultChat.java` `buildDynamicSystemPrompt` | 在【提问与澄清】段后插入【任务分段执行】段 |
| D1: Tool description 强化 | tool 注解 | `spring-ai-loom-agent/.../subtask/DefaultSubTaskTool.java` `start_sub_task` `@Tool(description=...)` | 末尾追加"用途：避免主对话 reasoning 超长"段 |
| T1: 单测锁契约 | 单测 | `spring-ai-loom-agent/.../chat/DefaultChatSubTaskGuidanceContractTest.java` (新文件) | 断言 7 条 |
| D2: CLAUDE.md 同步 | 文档 | `CLAUDE.md` `IChat` 与 `ISubTaskTool` 条目 | 追加本规格变更说明 |

**架构原则**：所有改动均在自己代码库内（loom-agent 包内），**不依赖** Spring AI 1.1.8 的私有类型
（`ToolUseAggregationEvent` / `StreamHelper.mergeToolUseEvents`）。这保证 Spring AI 2.0+ 升级路径
下本规格无须重写（除单测里 "StreamHelper" 字面量关键词可能失效，详见 §6.4）。

---

## 4. 数据流

### 4.1 启用前（当前）

```
User message → DefaultChat.buildDynamicSystemPrompt
              ↓ 注入【提问与澄清】段（askUser 触发引导）
              ↓ ChatClient.stream().chatResponse()
              ↓ LLM 看到长 reasoning 风险任务 → 自主决定写 HTML
              ↓ writeFile tool_use（input_json > 32K chars）
              ↓ 流被截断（max_tokens 命中 / Qwen 代理 idle close / JDK keepalive）
              ↓ squashIntoContentBlock:2019 解析不完整 JSON
              ↓ JsonEOFException → onErrorResume → friendlyMessage 已修复文案
              ↓ 用户看到可操作诊断（不再被误导）
```

### 4.2 启用后（本规格）

```
User message → DefaultChat.buildDynamicSystemPrompt
              ↓ 注入【提问与澄清】段（askUser 触发引导）
              ↓ 注入【任务分段执行】段（新 — 4 场景 + 触发协议 + few-shot）
              ↓ ChatClient.stream().chatResponse()
              ↓ LLM 看到长 reasoning 风险任务 → 评估是否 ≥3 子目标
              ├─ 是 → start_sub_task 子任务 pool 异步执行（4 个 HTML 表格拆分）
              │      ↓ 子任务完成自动回流结果
              │      ↓ 主对话收口、调用 renderHtmlFile 渲染
              └─ 否 → 主对话直接完成（短任务不退化）
```

**关键不变量**：ISubTaskExecutor / ISubTaskTool 已有实现（loom-subtask / loom-scheduler
子模块），本规格**不动**其运行时代码，只调整调用 LLM 的引导。

---

## 5. 详细设计

### 5.1 C1: `DefaultChat.buildDynamicSystemPrompt` 新增段

**位置**：紧跟 `【提问与澄清】` 段之后（在动态拼装 `【技能】` / `【知识库】` 之前）

**段内容**：

```text
【任务分段执行】
单次主对话的流式输出长度有上限（Spring AI 1.1.8 Anthropic 协议下，max_tokens=16384 时
thinking 占用后留给 tool_use JSON 的 token 不到 8K）。当你判断一个任务超出单次主对话的
能力边界时，应主动调 start_sub_task(prompt, systemContext)把它拆出去异步执行，避免
主对话 reasoning 撞上限导致流截断（症状：聊天最终弹"工具响应流被中途截断"，工具
调用被强制中断）。

必须主动拆的 4 类场景：
1. 大型代码/HTML 生成：单次要写出超过 200 行的完整文件（HTML 原型、SQL 脚本、复杂配置）
2. 多工具链调用：一次任务里需要顺序或并行调多个工具
   （writeFile → renderHtmlFile → 桥接 fileId → 拼装 markdown 链接）
3. 长 reasoning 风险：用户需求模糊、需先枚举可能方案再收敛
   （视觉风格选型、方案权衡、数据建模字段确认）
4. 大文件写入/编辑：整文件替换、超 5K 字符单 patch、整段重写

触发协议（拆之前先评估）：
1. 估算任务是否含 ≥3 个独立子目标（如 4 张表 = 4 个独立 HTML 生成）
2. 是 → 第一步先调 start_sub_task，每个子目标一个独立 prompt
3. 子任务在 loomSubTaskExecutor 池异步执行，子任务完成会自动回流结果
4. 主对话做收口/汇总，不重复执行子任务的实际工作

护栏：能 1 步完成的任务不要拆；子任务总数 ≤ 5（与 askUser 5-ask 护栏对齐）。

示例（任务分段）：
用户：帮我写一个四张表的 CRUD 维护页面原型 HTML（工厂/产线/工序/产品工序关系），下载为图片
→ 主对话先评估：4 张表 + 多工具链调用 + 需生成大型 HTML（命中场景 1、2）→ 第一步先
   start_sub_task(prompt="生成工厂表 HTML，含查询区、表格、分页；Ant Design 风格；
   列：工厂编码/工厂名称/删除标识", systemContext="Ant Design 蓝白风格，标签化展示删除标识")
→ start_sub_task(prompt="生成产线表 HTML ...", systemContext="...")
→ start_sub_task(prompt="生成工序表 HTML ...", systemContext="...")
→ start_sub_task(prompt="生成产品工序关系表 HTML ...", systemContext="...")
→ 主对话不做 HTML 生成，统一调 renderHtmlFile(...) 把拼好的 HTML 渲染成图片，
   返回预览+下载链接给用户
```

### 5.2 D1: `DefaultSubTaskTool.start_sub_task` `@Tool` description 强化

**当前**：

```java
@Tool(description = "启动一个子任务异步执行...")
public String start_sub_task(...)
```

**改为**（在原 description 末尾追加）：

```text
用途：把需要大量 reasoning、规划或多步工具调用的大型任务拆出来异步执行，避免主对话
因 reasoning 超长被 Spring AI 1.1.8 流协议截断（截断症状：聊天最终弹"工具响应流被
中途截断"，用户体验断裂）。单次主对话适合 ≤ 200 行代码 / ≤ 3 个工具调用 / ≤ 5K 字符
文件的简单任务；超过这个尺度的任务请用 start_sub_task 拆分。
```

### 5.3 T1: `DefaultChatSubTaskGuidanceContractTest` 单测契约

**新文件**：`spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChatSubTaskGuidanceContractTest.java`

**测试用例**（7 条断言）：

| ID | 断言 | 锁定的契约 |
|---|---|---|
| A1 | `prompt.contains("【任务分段执行】")` | 段标题存在 |
| A2 | `prompt.contains("大型代码")` | 场景 1 关键词 |
| A3 | `prompt.contains("多工具链")` | 场景 2 关键词 |
| A4 | `prompt.contains("长 reasoning 风险")` | 场景 3 关键词 |
| A5 | `prompt.contains("大文件写入")` | 场景 4 关键词 |
| A6 | few-shot 模板含 `start_sub_task` + `renderHtmlFile` 调 | 实际复现路径被覆盖 |
| A7 | 触发协议含 "≥3 个独立子目标" | LLM 可机械读取的拆分阈值 |

辅助测试（2 条，覆盖 D1）：

| ID | 断言 |
|---|---|
| A8 | `toolDescription.contains("流协议截断")` —— D1 改动到位 |
| A9 | 护栏含 `≤ 5` —— 与 askUser 5-ask 护栏对称 |

**测试形式**：reflection 读 `DefaultChat.buildDynamicSystemPrompt` 返回值断言关键词；reflection 读
`DefaultSubTaskTool.start_sub_task` 上的 `@Tool` 注解字符串断言 D1。

### 5.4 D2: CLAUDE.md 同步

修改 `CLAUDE.md` 两处：

**A. `ISubTaskTool` 条目追加**：

```text
@Tool 描述 2026-09-21 起为正向引导措辞（"用途：把需要大量 reasoning、规划或多步
工具调用的大型任务拆出来异步执行，避免主对话因 reasoning 超长被流协议截断"），
与 DefaultChat【任务分段执行】段双层引导，DefaultChatSubTaskGuidanceContractTest
锁定（9 断言：4 场景关键词 + few-shot + 触发协议 + Tool description + 护栏）。
```

**B. `IChat` 条目追加**：

```text
buildDynamicSystemPrompt 新增【任务分段执行】段（4 类必拆场景 + 触发协议 + 1 完整
few-shot 轨迹），紧跟【提问与澄清】段后；与 askUser 三层引导模式完全对称。
实现：DefaultChat.buildDynamicSystemPrompt。规格：docs/superpowers/specs/
2026-09-21-task-segmentation-prompt-design.md。
```

---

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| R1: LLM 不遵循新 prompt，bug 仍偶发 | 中 | 单次任务失败 | friendlyMessage 已落地（友好提示 + 重发建议）+ 建议 max_tokens 32768+ |
| R2: 误触发，简单任务也走子任务 | 低 | 多耗 LLM quota + 延迟 | 护栏 `≤ 5` + 触发协议 `≥ 3 个独立子目标` |
| R3: prompt 变长影响每轮 token 成本 | 低 | 每轮多 ~150 tokens | 14 行新增 vs askUser 段同体量，token 增量可控 |
| R4: 单测里关键词字面量与 Spring AI 升级耦合 | 低 | 2.0+ 升级后单测改名 | "流协议截断" 关键词改自描述性，与 SDK 版本号无关 |
| R5: 回归风险 —— 现有 askUser 触发率受新段影响 | 极低 | askUser 触发率下降 | askUser 段完全保留，【任务分段执行】是新独立段，互不干扰 |

---

## 7. 测试策略

| 层 | 文件 | 验证 | 必须 |
|---|---|---|---|
| 单测契约 | `DefaultChatSubTaskGuidanceContractTest` (新) | 9 断言（见 §5.3） | ✅ 必绿 |
| 单测回归 —— friendlyMessage | `FriendlyMessageJsonEofTest` (已有) | JsonEOFException 仍正确翻译，**新 prompt 段不污染其结果** | ✅ 必绿 |
| 单测回归 —— askUser 引导 | `DefaultAskUserToolTest` (已有) | askUser 三层引导文案不变；【提问与澄清】段与【任务分段执行】段互不干扰 | ✅ 必绿 |
| 单测回归 —— 聊天响应 | `DefaultChatThinkingBridgeTest`、`ChatReasoningHistoryTest`、`DefaultChatTest` | `buildDynamicSystemPrompt` 仍正常返回；thinking 桥接未引入回归 | ⚠️ 必跑 |
| E2E 浏览器 IT | `*BrowserIT` (项目已有) | 不在本次 spec 范围；T1 单测通过即可合并 | ❌ 本次不跑 |
| 端到端复测 | D:\developer\IdeaProjects\loom-agent 手动 Playwright | 合并后用同一问一答 + 同一 renderHtmlFile 路径复测，验证 LLM 主动 start_sub_task | ⚠️ 推荐跑 |

---

## 8. 部署与回滚

### 8.1 部署步骤

1. 合并 PR（含本规格 4 文件改动 + 1 新单测）
2. 重新构建 loom-agent jar（`mvn clean install -Dgpg.skip=true`）
3. 生产服务器滚动重启

### 8.2 回滚

单文件 revert `DefaultChat.buildDynamicSystemPrompt` 移除【任务分段执行】段即可回滚（无 schema 变更）。
若 D1 也需要回滚：还原 `DefaultSubTaskTool.start_sub_task` `@Tool(description=...)` 原文。

### 8.3 监控

无需新增监控指标。建议观察：
- `loom_tool_call_log` 中 `start_sub_task` 调用频次（应有所上升）
- 用户报告"流被截断"频次（应下降）
- 单次会话平均 token 消耗（小幅上升，可接受）

---

## 9. 与其他路线的协同

| 路线 | 状态 | 协同关系 |
|---|---|---|
| **A 路线** — 升级 Spring AI 2.0+ 官方 SDK | 长期规划 | 本规格不依赖 A；A 完成后本规格无须重写 |
| **B 路线** — 升级 max-tokens 到 32768+ | 短期建议 | 建议同步执行（独立 yml 配置，不在本规格范围） |
| **C-外置型** — DefaultChat 自动分段 | 中期规划 | 本规格是 C 的"软件引导"层；C 的"硬件守护"层独立 |
| **friendlyMessage 修复**（已落地） | 已完成 | 互补：本规格治未病，friendlyMessage 治已病

---

## 10. 验收标准

1. ✅ T1 单测 9 断言全绿
2. ✅ FriendlyMessageJsonEofTest 3 断言仍全绿（无回归）
3. ✅ DefaultAskUserToolTest 通过（askUser 段未动）
4. ✅ D:\developer\IdeaProjects\loom-agent 复测：使用原"工厂产线工序主数据维护"消息 + 5 轮 askUser + Ant Design + 拼接长图选项，观察 LLM 是否主动调 start_sub_task 拆分 4 张表的 HTML 生成（**期望：是**）
5. ✅ CLAUDE.md 同步到位，`grep` 能查到【任务分段执行】段、ISubTaskTool 条目更新

---

## 11. 范围之外（明确排除）

| 不做 | 理由 |
|---|---|
| 不动 `ISubTaskTool` / `ISubTaskExecutor` 接口 | 接口契约稳定，仅调实现 |
| 不引入 yml 新配置 | 14 行 prompt 是合理体量，无需配置化阈值 |
| 不实现 DefaultChat 自动分段（**C-外置型**） | 独立 spec 议题，本规格专注"软件引导" |
| 不写 Spring AI 2.0+ 适配层 | 长期路线，独立规划 |

---

## 12. 时间估算

| 项 | 行数估计 | 估计时间 |
|---|---|---|
| C1 prompt 段文本 | ~14 行 | 10 分钟（已有完整文本） |
| D1 Tool description | ~3 行追加 | 5 分钟 |
| T1 单测契约 | ~50 行（含 reflection helper） | 30 分钟 |
| D2 CLAUDE.md 同步 | ~10 行 | 10 分钟 |
| 集成测试与回归 | - | 20 分钟 |
| **合计** | **~80 行代码** | **~75 分钟** |

---

## 13. 关键参考

- CLAUDE.md `【提问与澄清】` 段：当前 askUser 三层引导范本（`DefaultChat.java:355-367`）
- CLAUDE.md `IAskUserTool` 条目：`@Tool` 描述措辞契约先例
- 2026-09-21 commit `fix(build): test 模块固化 file.encoding=UTF-8` — 同期变更记录
- 2026-09-21 commit `fix(chat): friendlyMessage 加 JsonEOFException 分支` — 本规格的前置治标

---

**请 review 后决定是否进入 writing-plans 阶段。**