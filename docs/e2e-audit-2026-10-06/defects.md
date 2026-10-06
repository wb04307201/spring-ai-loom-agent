# E2E 巡检缺陷清单（2026-10-06）

巡检对象：`spring-ai-loom-agent` @ `dev-sp2`（BASE `7421e552`）
真实 LLM：MiniMax-M3（OpenAI 兼容端点）
汇总：**1 P0 + 4 P1 + 1 P2**

| 编号 | 级别 | 摘要 | 类型 |
|---|---|---|---|
| P0-1 | **P0** | 流式响应末尾被上游掐断，错误文案混入 AI 回复 | 真产品缺陷 |
| P1-1 | P1 | token 用量未落库（与 P0-1 同源） | 真产品缺陷 |
| P1-3 | P1 | schedule 的 `expression` 不支持 ISO-8601，且静默返回 `-1` | 真产品缺陷 |
| P1-4 | P1 | `by-path` 预览/下载在 `usersBasePath` 以 `./` 开头时全部 404 | 真产品缺陷 |
| P1-2 | P1 | 知识库向量维度为 4（test app 配置） | 测试配置缺陷 |
| P2-1 | P2 | 欢迎语写死，与真实 provider 不符 | 文案 |

---

## P0-1 流式响应末尾被上游掐断，错误文案混入 AI 回复

**维度**：C 聊天主干 / J MCP
**复现**：
1. 登录 `http://localhost:8080/spring/ai/loom/index.html`
2. 发送需要长回答的问题，如「请详细写一篇 2000 字的技术文章，主题是分布式系统的一致性模型」
3. 观察 AI 气泡末尾

**期望**：回复正文完整，无附加错误文案
**实测**：正文渲染完整后，末尾被追加

```
聊天服务异常（OpenAIIoException），请稍后重试。
```

**证据**（后端日志，非推测）：
```
2026-10-06T11:14:00.342  stream NEXT: conv=9988fe61 textLen=13 reasoningAccumLen=6446
2026-10-06T11:14:00.349  ERROR MessageAggregator : Aggregation Error
                          com.openai.errors.OpenAIIoException: Stream failed
                          Caused by: java.io.InterruptedIOException: timeout
```
- `Aggregation Error` 计数 = **18**，集中在 `11:14:00` 与 `11:32:32`，每次 9 个并发线程同时报错
- **失败发生在前一帧之后 7 毫秒** —— 流正在活跃传输时被掐，**不是超时**
- 会话 `9988fe61` 存活 `10:49:21 → 11:32:32` 共 **43 分钟**，远超任何超时阈值

**两个症状面**：

| 场景 | 表现 | 错误文案质量 |
|---|---|---|
| 长文本回答 | 正文后追加 `聊天服务异常（OpenAIIoException）` | 差 —— 无任何可操作信息 |
| tool_use 调用（MCP） | 流截断，工具调用中断 | 好 —— 指明 `tool_use JSON` 与 `max_completion_tokens` 两个方向 |

后者的文案来自 `DefaultChat.java:270`：
> 模型响应流被中途截断(Spring AI 流聚合器在 tool_use JSON 未完整到达时尝试解析触发)。
> 建议:1) 直接重发该对话(单次偶发可恢复);2) 联系管理员调大 max_completion_tokens…

**根因**：上游（MiniMax OpenAI 兼容端点）在流末尾发来的数据不完整，
Spring AI 的 `MessageAggregator` 聚合失败。**不是超时**。

**与既有修复的关系**：CLAUDE.md 记载 `2e67c59a` 修过同类问题 ——
库侧 `ChatProviderTimeoutDefaultsBeanFactoryPostProcessor` 补
`spring.ai.openai.timeout` 默认 600s（已核实：库侧 `ChatProperty.timeoutSeconds`
默认值确为 600，BFPP 注册正常）。但**那是超时层**的修复，本次是**聚合层** ——
同一个坑的新变种：配置层修好了，聚合层没覆盖。

**建议修复方向**：
1. 聚合失败时**不要把错误文案拼进 AI 气泡**（应作为独立的系统提示或仅记日志）
2. `MessageAggregator` 失败时保留已累积的文本，而不是整段替换
3. 排查上游为何在流末尾发不完整数据（`reasoning_split=true` + 长 thinking 的组合可疑）

---

## P1-1 token 用量未落库（与 P0-1 同源）

**维度**：C 聊天主干 / I 控制台
**复现**：
1. 进行多轮真实对话（本次实测 7 个会话 / 39 条消息）
2. 点右上角用户菜单 →「本月用量」
3. 或查 `admin/stats.html` 月度柱图

**期望**：显示真实 token 用量
**实测**：
```sql
SELECT COUNT(*) FROM loom_chat_usage;   → 0 行
SELECT COUNT(*) FROM chat_token_usage;  → 0 行
```
前端「总 Token / 调用次数 / 输入 Token / 输出 Token / 平均每次」**五项全为 0**；
`GET /admin/stats/tokens/monthly` 返回 **`[]`**。

**根因**（`LoomAgentConfiguration` SseController L783-789）：
```java
var chatMeta = chatResponse.getMetadata();
if (chatMeta != null && chatMeta.getUsage() != null) {   // ← 恒为 null
    chatUsageService.record(conversationId, username,
        u.getPromptTokens(), u.getCompletionTokens(), u.getTotalTokens());
}
```

OpenAI 兼容 API 的 usage **只在流的最后一帧**返回
（test app yml 已配 `stream_options.include_usage=true`，日志里确实能看到
`"promptTokens" : 24628` 这样的数据），但 Spring AI 的 `MessageAggregator`
**恰在最后一帧**抛 `OpenAIIoException`（P0-1，18 次实证）——
**聚合失败 ⇒ 最后一帧的 usage 丢失 ⇒ `getUsage()` 恒为 null ⇒ 一行都不写**。

即使流成功完成（15 次 `stream COMPLETE`），`getUsage()` 依然为 null ——
说明 Spring AI 的 OpenAI SDK 路径**根本没把 usage 挂到 `ChatResponse.getMetadata()`**。

**影响面**：前端用量弹窗、`admin/stats.html` 月度柱图、
`ChatUsageService` 的 `monthlyByUser` / `recent6MonthsForUser` / `currentMonthForUser`
三个查询全部返回空。

**与 P0-1 的关系**：**同一上游流问题的两个症状**。P0-1 是用户可见症状，
P1-1 是同一掐断导致 usage 帧丢失的下游后果。**修 P0-1 应能一并解决 P1-1**。

---

## P1-2 知识库向量维度为 4（test app 配置）

**维度**：D 工具层 / E 知识空间
**复现**：
```bash
PUT  /spring/ai/loom/knowledge {"name":"e2e-knowledge",...}   → 200
POST /spring/ai/loom/knowledge/{id}/upload -F file=@doc.txt   → 200
```
```sql
SELECT document_id, dim, LENGTH(embedding) FROM loom_vector_store;
→ c41501f4-...  |  dim=4  |  15 bytes
```

**期望**：真实 embedding 维度（1024+）
**实测**：`dim=4`

**原判修正**：spec §2 原判 P0（预期上传 500）。**读源码后修正为 P1**：

- `H2JVectorStore` 只调 `embeddingModel.embed(...)`（L208 写入 / L309 查询），
  **从不调 `call()`**
- 全库 grep 无任何 `.call(embed...)` 调用点
- fake `EmbeddingModel` 的 `embed()` **有实现**（4 维随机向量），
  只有 `call(EmbeddingRequest)` 抛 `UnsupportedOperationException`

**所以上传返回 200 而非 500，链路是通的**。

**定级 P1 理由**：上传与检索链路**都能跑通**，但向量是
`new Random(text.hashCode())` 生成的 4 维随机数 —— 检索排序**语义上无意义**
（问 A 返回随机相似文档，不是真正相关内容）。知识库作为 RAG 核心能力，
在本 test app 配置下**不可用于验证**。

**影响面**：test app 配置问题（`TestAnthropicOptionsConfig` 位于 `src/main/java`），
**不是库代码缺陷**。但它使知识库检索能力的 E2E 验证失去意义。

**建议修复**：test app 配真 embedding provider（如 DashScope `text-embedding-v4`），
或让 fake 的 `call()` 委托给 `embed()`。

---

## P1-3 schedule 的 `expression` 不支持 ISO-8601，且静默返回 `-1`

**维度**：D 工具层
**复现**：
1. 发消息「请用 createSchedule 创建一个定时任务，名字叫 e2e-sched，每隔 15 分钟执行一次」
2. LLM 按标准 ISO-8601 写 `PT15M` → **创建失败，报 `PT-1S`**
3. LLM 试错改用 `15m` → 成功

**期望**：`PT15M`（Java `Duration` 标准格式）被接受；或至少报出"格式不支持"
**实测**：被解析成 `-1` 秒，表现为 `PT-1S`

**LLM 思考链原文**（非推测）：
> 成功了!格式是 "15m" 而不是 ISO-8601 的 "PT15M"。
> 💡 **踩坑提示**：这个平台 `fixed_delay`/`fixed_rate` 的 `expression` 用的是
> **简写字符串**（如 `15m`、`30s`、`2h`），不是标准 ISO-8601 Duration（`PT15M`）。
> 我第一次用了 `PT15M` 被平台解析成了 `PT-1S` 直接报错。

**根因**（`DefaultScheduleTool.parseSeconds` 源码）：
```java
String s = expression.trim().toLowerCase()   // "PT15M" → "pt15m"
        .replace("秒钟","s").replace("秒","s")
        .replace("secs","s").replace("sec","s")
        .replace("分钟","m").replace("分","m")
        .replace("minutes","m").replace("minute","m").replace("mins","m").replace("min","m");
// "pt15m" 不含以上任何模式 → 保持 "pt15m"
if (s.endsWith("m")) { ... parseLong("pt15") 失败 → 落到下面 }
if (s.endsWith("s")) s = s.substring(0, s.length()-1);   // 不以 s 结尾，不裁剪
return Long.parseLong("pt15m");   // NumberFormatException → return -1L
```
`-1` 被当作秒数使用 ⇒ 表现为「PT-1S」。

**三个问题**：
1. **不支持 ISO-8601** —— `PT15M` 是最符合直觉/最标准的写法（Java `Duration` 的标准格式），却完全不被识别
2. **静默失败** —— 无法解析时返回 `-1L` 而非抛错或提示格式。调用方（LLM）只看到「PT-1S」这种无意义报错，无法反推真实原因
3. **`@Tool` 描述未说明格式** —— 描述只写了「类型 cron / fixed_delay / fixed_rate / one_shot」和限制，**完全没提 `expression` 的格式要求**

**定级 P1**：功能最终可用（LLM 自行试错找到了 `15m`），但**首次调用必然失败**，
且错误信息误导（`PT-1S` 与真实原因无关）。

**建议修复**：
- `parseSeconds` 增加 ISO-8601 分支（`Duration.parse()` 回退）
- 无法解析时返回明确错误信息而非 `-1`
- `@Tool` 描述补：`expression 格式：简写字符串，如 "15m"/"30s"/"2h"，或中文"15分钟"`

---

## P1-4 `by-path` 预览/下载在 `usersBasePath` 以 `./` 开头时全部 404

**维度**：F 文件管理
**复现**：
1. 以 `--spring.ai.loom.agent.users-base-path=./target/test-users` 启动（**带 `./` 前缀**）
2. 在用户目录下放置文件 `e2e/sample.txt`
3. `GET /spring/ai/loom/file/tree` → **200**，正确列出文件
4. `GET /spring/ai/loom/file/by-path/view?path=e2e/sample.txt` → **404**
5. `GET /spring/ai/loom/file/by-path/download?path=e2e/sample.txt` → **404**

**期望**：302 重定向到 `/file/view/{fileId}` / `/wopi/files/{id}/contents`
**实测**：**404**（对所有 path 形式：`e2e/sample.txt` / `./e2e/sample.txt` / `sample.txt` / `e2e`）

**根因**（用真实 `LoomPaths` 编译复现，非推测）：

```java
Path baseDir = LoomPaths.userFileDir("./target/test-users", "wb04307201");
//   → .\target\test-users\wb04307201\file        ← 保留 "./" 前缀，未 normalize
Path resolved = baseDir.resolve("e2e/sample.txt").normalize();
//   → target\test-users\wb04307201\file\e2e\sample.txt   ← normalize() 抹掉了 "."
if (!resolved.startsWith(baseDir) ...) return null;   // ← false ⇒ 404
```

实测三种 base：

| `usersBasePath` | `resolved.startsWith(baseDir)` | 后果 |
|---|---|---|
| `./target/test-users` | **false** | ❌ 404 |
| `target/test-users` | true | ✓ |
| `C:\Users\...\.loom\users`（绝对） | true | ✓ |

`baseDir` 未 normalize 而 `resolved` 已 normalize，两者**字面前缀不一致**
⇒ 沙箱校验误判为「越界」⇒ 静默 404。

**放大问题的两点**：
1. `getOrCreateFileId` 用 `catch (Exception e) { return null; }` **静默吞异常**，
   无任何日志 —— 排查时只能靠复现，无法从日志定位
2. `/file/tree` 走另一条路径（`buildChildren` 只用 `Files.exists`），
   所以**树能列、点预览却 404**，症状自相矛盾，极易被误判为前端 bug

**影响面**：
- 文件管理模态框的「预览」「下载」按钮全部失效
- `@Tool downloadFileUrl` / `viewFileUrl`（IFileTool 的 16 个工具中的 2 个）失效
- **生产默认不受影响** —— 默认 `usersBasePath` 是绝对路径
  `${user.home}/.loom/users`。**任何把 `usersBasePath` 配成 `./xxx` 相对路径的部署会踩到**

**建议修复**：
```java
Path baseDir = cn.wubo.loom.file.core.LoomPaths.userFileDir(usersBasePath, username).normalize();
```
（一行；同时建议 `catch` 里补 `log.warn`，避免静默失败）

---

## P2-1 欢迎语写死，与真实 provider 不符

**维度**：C 聊天主干
**复现**：打开 `index.html`（无对话时）
**期望**：标题反映真实 provider，或使用中性文案
**实测**：固定渲染「你好！我是你的 AI 助手」，而 LLM 实际自述为
「我是 MiniMax-M3，一个由 MiniMax 开发的智能助手」。
切换 provider 后标题不变。

**影响**：轻微 —— 用户可能困惑于「AI 助手」与「MiniMax-M3」的身份差异。

---

## 附：三个非缺陷的排查结论

以下三项**看起来像缺陷但经核实不是**，记录以免后续重复排查：

### 1. 中文文件名「乱码」（curl 上传）

| 上传方式 | 存储的 codepoints | 判定 |
|---|---|---|
| `中文测试.txt`（**curl -F**，Git Bash） | `U+FFFD U+FFFD U+FFFD U+0132 U+FFFD...` | ❌ 乱码 |
| `中文测试.txt`（**Python 显式 UTF-8**） | `U+4E2D U+6587 U+6D4B U+8BD5` = 「中文测试」 | ✓ **正确** |

**服务端 UTF-8 处理正确**。curl 的乱码是 **Git Bash 在 Windows 上以 GBK 编码
multipart 的 filename 字段**所致 —— 客户端工具问题。
**规避**：用 `--data-binary @file`（body）或 Python/Java 显式 UTF-8 构造 multipart。

### 2. e2euser 访问 `/admin/**` 返回 200

用 Python `urllib` 测试时会**自动跟随 302**，最终拿到登录页且 HTTP 200 ——
表面像「越权成功」。实测响应体为 `Content-Type: text/html` + `<title>登录</title>`
+ **不含任何真实用户名**，证明是登录页。用 `curl`（不跟随重定向）则直接看到 **302**。
**判定依据必须是响应体内容，不能只看状态码。**

### 3. RAG 关闭后 `#ks-button` 元素仍存在于 DOM

`getElementById('ks-button')` 仍能取到元素，但 `display:none` / 高度 0px ——
这是前端按 `features.knowledge` 做显隐的标准做法，**不是缺陷**。
同一时刻 `#image-add-btn` 与画板按钮 `display:flex` / 36px **恒可见**，
与 CLAUDE.md 记载的「`+`/画板不与门控联动」吻合。
