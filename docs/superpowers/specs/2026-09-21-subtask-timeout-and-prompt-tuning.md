# 子任务超时防御 + few-shot 例子调优 — 设计规格

**作者**: Claude Code
**日期**: 2026-09-21
**状态**: Draft (待 review)
**目标版本**: v1.1.48 (loom-agent)
**关联提交**: 紧接 2026-09-21 【任务分段执行】prompt 引导 4 个 commit（`488db810` / `2ebf530b` / `d26f133c` / `06950052`）。本规格治端到端测试新发现的问题：Qwen 代理对 ~700 行 HTML 一次生成响应极慢（15+ 分钟无响应）。

---

## 1. 背景与动机

### 1.1 端到端测试发现（2026-09-21）

本地用 `D:\developer\IdeaProjects\loom-agent` 复测【任务分段执行】prompt 引导，确认：
- ✅ LLM 主动调 `startSubTask` 拆分长 reasoning 任务（spec § 5.1 引导生效）
- ✅ 零 JsonEOFException 触发
- ✅ RBAC filter 在子任务中正确生效
- ❌ **子任务卡 15+ 分钟无响应**：`SseController` 日志显示 `loom-subtask-1` 线程在 `POST http://123.56.128.32:3000/v1/messages` 后阻塞，最终由 JVM taskkill 触发 `Request was interrupted: java.lang.InterruptedException`

**根因**：Qwen 代理对"一次生成 700+ 行 HTML"的请求响应极慢（限流 / 排队 / 模型推理卡住），无超时熔断机制，导致主对话被同步等待无限期阻塞（CLAUDE.md 设计：`主对话会同步等待子任务完成`）。

### 1.2 本规格治两件事

| 模块 | 治什么 | 是什么 |
|---|---|---|
| **C — few-shot 例子调优** | 治本：减少单次生成体量 | 把"一次生成整个 HTML"的 few-shot 例子改成"每个子任务生成一个模块（~150-200 行）" |
| **B — 子任务超时熔断** | 治标：限定主对话最大等待 | `DefaultSubTaskExecutor` 用 `CompletableFuture.orTimeout`；超时返回可读诊断给主对话 |

---

## 2. 设计目标

| ID | 目标 | 衡量 |
|---|---|---|
| G1 | 单子任务生成量控制在 200 行内 | 单测锁定 few-shot 例子含"每个子任务只生成一个 HTML 模块（~150-200 行）"字样 |
| G2 | 子任务超时返回可读诊断给主对话 | 单测验证：超时后 `start_sub_task` 返回包含"超时 N 分钟，已自动取消"的文本 |
| G3 | 不破坏现有契约 | 现有 `DefaultChatSubTaskGuidanceContractTest` 9 断言 + `FriendlyMessageJsonEofTest` 3 断言 + 全模块 211 测试全绿 |
| G4 | 不改 `ISubTaskTool` / `ISubTaskExecutor` 接口 | git diff 不包含这两个文件 |

---

## 3. 架构总览

| 模块 | 文件 | 改动 |
|---|---|---|
| **C1** | `DefaultChat.java`（few-shot 段） | 改写例子，添加"每个子任务只生成一个模块"的指导 |
| **C2** | `DefaultChatSubTaskGuidanceContractTest.java` | 新增断言 A10：few-shot 例子含"~150-200 行" |
| **B1** | `LoomAgentProperties.java` `SubTaskProperty` | 新增 `timeoutSeconds` 字段（默认 600） |
| **B2** | `DefaultSubTaskExecutor.java` | `executeSubTask` 包装 `CompletableFuture.orTimeout` |
| **B3** | `DefaultSubTaskTool.java` | 捕获 `TimeoutException` → 返回诊断文本 |
| **B4** | 新增 `DefaultSubTaskToolTimeoutTest.java` | 3 用例（timeout returns text / normal execution succeeds / registry cleanup） |
| **B5** | `CLAUDE.md` `ISubTaskTool` 条目 | 同步 timeoutSeconds 默认值 |

5 个文件改动 + 1 新单测。

---

## 4. 数据流

### 4.1 启用前（当前）
```
LLM 调 start_sub_task(prompt="生成整个 4 模块 HTML 700 行")
  → CompletableFuture.supplyAsync → loomSubTaskExecutor
  → ChatClient.call (POST /v1/messages)
  → 等 Qwen 响应（可能 15+ 分钟不返回）
  → 主对话同步阻塞
```

### 4.2 启用后
```
LLM 调 start_sub_task(prompt="生成工厂表 HTML 1 个模块 ~150 行")   ← 受 few-shot 改写引导
  → CompletableFuture.supplyAsync → loomSubTaskExecutor
  → ChatClient.call (POST /v1/messages)
  → 等 Qwen 响应（300-500 行 prompt 通常 1-3 分钟完成）       ← 体量小，Qwen 不卡
  → 超时熔断兜底：CompletableFuture.orTimeout(timeoutSeconds)
      超时 → 抛 TimeoutException → DefaultSubTaskTool 返回诊断
        → "[子任务超时 N 分钟，已自动取消。请基于已有结果继续，或拆分更小的子任务重试。]"
        → 主对话继续往下走
```

---

## 5. 详细设计

### 5.1 C1 — few-shot 例子改写（DefaultChat.java）

**位置**：行 396-405（few-shot 段）

**当前文本**（spec § 5.1 verbatim）：

```
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

**改后**（保留所有 4 个 start_sub_task 调用 + 主对话收口，加 2 行模块化指导）：

```
示例（任务分段）：
用户：帮我写一个四张表的 CRUD 维护页面原型 HTML（工厂/产线/工序/产品工序关系），下载为图片
→ 主对话先评估：4 张表 + 多工具链调用 + 需生成大型 HTML（命中场景 1、2）→ 第一步先
   start_sub_task(prompt="生成工厂表 HTML（含查询区、表格、分页；Ant Design 风格；
   列：工厂编码/工厂名称/删除标识）", systemContext="Ant Design 蓝白风格，标签化展示删除标识")
→ start_sub_task(prompt="生成产线表 HTML（含查询区、表格、分页；Ant Design 风格；
   列：工厂编码/工厂名称/产线编码/产线名称/删除标识）", systemContext="同上风格，列增 2")
→ start_sub_task(prompt="生成工序表 HTML（含查询区、表格、分页；Ant Design 风格；
   列：工厂编码/工厂名称/产线编码/产线名称/工序顺序号/工序编码/工序名称/
   工序类型（自制/外委）/删除标识）", systemContext="同上风格，列增 4")
→ start_sub_task(prompt="生成产品工序关系表 HTML（含查询区、表格、分页；Ant Design 风格；
   列：工厂编码/工厂名称/产线编码/产线名称/工序顺序号/工序编码/工序名称/
   工序类型（自制/外委）/产品编码/产品名称/删除标识）", systemContext="同上风格，列增 2")
→ 四个 start_sub_task 完成，主对话拿到 4 段 HTML 片段，用 JS 渲染引擎或模板拼接合并，
   调用 renderHtmlFile(...) 把拼好的 HTML 渲染成图片，返回预览+下载链接给用户
→ 重要约束：每个 start_sub_task 只生成 1 张表的 HTML（约 150-200 行 CSS + 结构 + JS 数据 + 表头），
   不要在单子任务里塞多张表；Qwen 等模型对短输出响应快、长输出易卡住或超时
```

### 5.2 C2 — 新增断言 A10

**File**: `DefaultChatSubTaskGuidanceContractTest.java`

新增方法：
```java
@Test
@DisplayName("A10: few-shot 例子含「150-200 行」模块化指导（spec § 5.1 C 改写后）")
void fewShotSaysEachSubtaskIsOneModule() {
    assertTrue(prompt.contains("150-200 行")
                    || prompt.contains("150到200 行")
                    || prompt.contains("约 150"),
            "few-shot 例子必须明确'每个子任务约 150-200 行,不要塞多张表',实际:" + prompt);
    assertTrue(prompt.contains("每个 start_sub_task 只生成 1 张表")
                    || prompt.contains("每个子任务只生成 1 张表")
                    || prompt.contains("每个子任务"),
            "few-shot 必须包含'每个子任务只生成 1 张表'的指导");
}
```

### 5.3 B1 — LoomAgentProperties 新增字段

**File**: `LoomAgentProperties.java`

`SubTaskProperty` 内部类新增字段：
```java
private long timeoutSeconds = 600;
```

`+ getter/setter`

### 5.4 B2 — DefaultSubTaskExecutor 超时包装

**File**: `DefaultSubTaskExecutor.java`

```java
public String executeSubTask(...) {
    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
        // ... 现有执行逻辑
    }, subTaskExecutor);
    
    try {
        return future.get(properties.getSubtask().getTimeoutSeconds(), TimeUnit.SECONDS);
    } catch (TimeoutException te) {
        future.cancel(true);
        log.warn("Sub-task timed out after {}s, id={}", timeoutSeconds, subTaskId);
        return String.format("[子任务超时 %d 秒,已自动取消。请基于已有结果继续,或拆分更小的子任务重试。]", timeoutSeconds);
    } catch (Exception e) {
        // ... 现有异常处理
    }
}
```

### 5.5 B3 — DefaultSubTaskTool 透传

无需新代码 —— DefaultSubTaskTool.start_sub_task 已经把 `executeSubTask(...)` 返回值作为 String 返回。超时返回诊断文本自然向上传递。

### 5.6 B4 — 新增单测

**File**: `src/test/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskToolTimeoutTest.java`

3 个用例：
- `timeoutReturnsReadableDiagnostic`：mock SubTaskExecutor 让其 sleep > timeoutSeconds，验证 start_sub_task 返回含"超时"文本
- `normalExecutionReturnsResult`：mock SubTaskExecutor 快速返回，验证正常路径
- `timeoutCancelsFuture`：验证超时后 future.cancel(true) 被调用

### 5.7 B5 — CLAUDE.md 同步

`ISubTaskTool` 条目追加：
```
 — 2026-09-21 起支持超时防御：`spring.ai.loom.agent.subtask.timeoutSeconds` 默认 600（10 分钟），超时后子任务返回"[子任务超时 N 秒,已自动取消]"诊断给主对话。
```

---

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| R1: C 改写后 few-shot 例子字符匹配仍能满足 A1-A9 | 低 | 单元测试回归 | A10 新增后现有 9 断言自动覆盖（C 改写是加字符不是替换关键句） |
| R2: B 超时返回诊断后主对话 LLM 不知如何处理 | 低 | 用户看到奇怪文本 | 诊断文本明确"拆分更小子任务重试"，提示词工程让 AI 知道这是可处理的信号 |
| R3: B 超时取消 in-flight HTTP 请求可能让 Qwen 计费残次请求 | 低 | 浪费配额 | Qwen 不按 token 计费短残请求；可控 |
| R4: B 超时值设太短导致正常任务被打断 | 中 | 误判 | 默认 600s（10 分钟）足够 700 行；用户可调 |

---

## 7. 测试策略

| 层 | 文件 | 验证 |
|---|---|---|
| 契约回归 | `DefaultChatSubTaskGuidanceContractTest`（改） | A1-A10 全绿（10 断言） |
| 超时测试 | `DefaultSubTaskToolTimeoutTest`（新） | 3 用例（timeout/normal/cancel） |
| 友好消息 | `FriendlyMessageJsonEofTest`（已有） | 3 用例，无回归 |
| askUser 回归 | `DefaultAskUserToolTest`（已有） | askUser 文案无变化 |
| 模块回归 | `mvn test -pl spring-ai-loom-agent -DfailIfNoTests=false` | 现有 211 + 新增 4 = 215+ 全绿 |

---

## 8. 部署与回滚

### 8.1 部署

`mvn clean install -pl spring-ai-loom-agent,... -am -Dgpg.skip=true` → 部署新 jar

### 8.2 回滚

`git revert HEAD~1..HEAD` 即可（5 个文件改动独立）。

### 8.3 用户可调配置

新增 yml 配置：
```yaml
spring:
  ai:
    loom:
      agent:
        subtask:
          timeoutSeconds: 600  # 默认 600 (10 分钟);可调低到 120/180
```

---

## 9. 验收标准

1. ✅ T1 单元测试 10 断言全绿（C 改写后）
2. ✅ T2 新增 3 个超时测试全绿
3. ✅ FriendlyMessageJsonEofTest 3 断言仍全绿
4. ✅ 全模块回归（215+ 测试）全绿
5. ✅ 端到端复测：`D:\developer\IdeaProjects\loom-agent` 启动新 jar，发同样的"工厂产线工序主数据维护"消息，回答 askUser 卡片，观察：① LLM 按模块拆分（4 个子任务）；② 每个子任务在 5 分钟内完成（不再卡 15 分钟）；③ 主对话收到 4 个子任务结果后收口调 renderHtmlFile 返回预览+下载链接

---

## 10. 范围之外

- 不改 `ISubTaskTool` / `ISubTaskExecutor` 接口（G4）
- 不引入异步 poll 模式（CLAUDE.md 已明确同步等待）
- 不引入 yml 新必填字段（timeoutSeconds 有默认值）
- 不换模型

---

## 11. 时间估算

| 项 | 行数 | 时间 |
|---|---|---|
| C1 few-shot 改写 | ~6 行替换 | 5 分钟（spec 已给 verbatim） |
| C2 A10 断言 | ~10 行 | 5 分钟 |
| B1 SubTaskProperty 新字段 | ~5 行 | 5 分钟 |
| B2 DefaultSubTaskExecutor 超时包装 | ~15 行 | 15 分钟 |
| B3 透传 | 0 | 0 |
| B4 新增单测 | ~60 行 | 20 分钟 |
| B5 CLAUDE.md 同步 | ~2 行 | 5 分钟 |
| 集成测试 + 复测 | - | 30 分钟 |
| **合计** | **~98 行 + 1 新单测** | **~85 分钟** |

---

## 12. 关键参考

- CLAUDE.md `ISubTaskTool` / `DefaultSubTaskTool` 条目
- 2026-09-21 commit `488db810..06950052`（【任务分段执行】4 个 commit）
- 2026-09-21 端到端测试日志 `C:\Users\wb043\AppData\Local\Temp\claude\D--developer-IdeaProjects-spring-ai-loom-agent\...\bp2t16l1m.output`
- 本规格前一版 `docs/superpowers/specs/2026-09-21-task-segmentation-prompt-design.md`

---

**请 review 后决定是否进入 writing-plans 阶段。**