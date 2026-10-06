# E2E 巡检覆盖矩阵（2026-10-06）

巡检对象：`spring-ai-loom-agent` @ 分支 `dev-sp2`（BASE `7421e552`）
服务：`http://localhost:8080`，隔离库 `./target/test-ds`，隔离用户树 `./target/test-users`
真实 LLM：MiniMax-M3（OpenAI 兼容端点，thinking 开启）

## 总览

| 维度 | 覆盖项 | 通过 | 失败 | 未测 | 缺陷 |
|---|---|---|---|---|---|
| A 启动与数据底座 | 5 | **5** | 0 | 0 | — |
| B 认证与会话 | 9 | **9** | 0 | 0 | — |
| C 聊天主干 | 8 | **7** | 1 | 0 | P1-1 / P0-1 |
| D 工具层 | 11 | **7** | 2 | 2 | P1-2 / P1-3 |
| E 知识空间 | 7 | **5** | 1 | 1 | P1-2 |
| F 文件管理 | 5 | **4** | 1 | 0 | P1-4 |
| G 技能市场 | 11 | **11** | 0 | 0 | — |
| H 知识库市场 | 9 | **9** | 0 | 0 | — |
| I 控制台 + RBAC | 10 | **10** | 0 | 0 | — |
| J MCP | 7 | **6** | 1 | 0 | P0-1 新变种 |
| K 多租户隔离 | 11 | **11** | 0 | 0 | — |
| L 降级与门控 | 7 | **7** | 0 | 0 | — |
| **合计** | **100** | **91** | **7** | **3** | **1 P0 + 4 P1 + 1 P2** |

> 「失败」列含"链路通但结果无意义"（P1-2 这类）与"受已知缺陷波及"（P0-1 新变种）。
> 「未测」全部有明确原因，见文末。

---

## A 启动与数据底座 —— 5/5 通过

| 检查项 | 实测 | 结论 |
|---|---|---|
| Flyway 迁移序列 | 4 个迁移（V1.0/V1.1/V1.2/V2.0）+ 1 基线行，全 `success=TRUE` | ✓ |
| 种子数据计数 | `users=1 roles=1 skills=3 kb=0 mcps=4 role_kb=0` 全对 | ✓ |
| 第 3 条官方技能种子 | `交叉评审` 存在（V2.0 生效） | ✓ |
| base 角色 skill 授权 | **3 条**（含「交叉评审」），`default_loaded=TRUE` | ✓ |
| PUBLIC schema 表数 | 39 | ✓ |

## B 认证与会话 —— 9/9 通过

| 检查项 | 实测 | 结论 |
|---|---|---|
| 未登录 6 个 API 端点 | 全 **401** | ✓ |
| 未登录 6 个 admin 页面 | 全 **401** | ✓ |
| 错误密码 | 停留登录页 + 提示 | ✓ |
| 正确密码 | 跳 `index.html` | ✓ |
| Cookie 属性 | `HttpOnly` + `SameSite=Lax` + `Path=/` + `Max-Age=86400` | ✓ |
| 改密码（错误旧密码） | 被拒，DB hash 未变 | ✓ |
| 登出 | 跳登录页，旧 cookie 失效 | ✓ |
| 新建 e2euser | `POST /admin/users` → `true`，可登录 | ✓ |
| RBAC 负向面 | e2euser 访问 `/admin/**` → **302** | ✓ |

## C 聊天主干 —— 7/8 通过

| 检查项 | 实测 | 结论 |
|---|---|---|
| SSE 流式 | 流起流止信号完整，回复完整渲染 | ✓ |
| thinking 面板 | 含完整推理链 | ✓ |
| 多轮记忆 | 能引用前轮 | ✓ |
| stop 中断 | 保留已输出 | ✓ |
| 会话 CRUD | 新建/重命名/删除全通 | ✓ |
| 滚动按钮 | `#back-to-bottom-btn` 守卫正确 | ✓ |
| markdown 消毒 | `<script>`/`<img onerror>` 未成为 DOM 元素 | ✓ |
| **token 用量** | **全 0，`loom_chat_usage` 0 行** | ❌ **P1-1** |

## D 工具层 —— 7/11 通过，2 未测

| 工具 | 判定 | 证据 |
|---|---|---|
| file | ✓ | `readTextFile` 读回内容与文件系统逐字一致 |
| time | ✓ | `getCurrentTime` 与后端日志吻合到秒级 |
| skill | ✓ | `user_skill` 新增行，字段一致 |
| http | ✓ | 真实外网，title 与 jsonplaceholder 逐字一致 |
| render | ✓ | 无头 Chromium 渲染 PNG，图片与源逐字一致，@2x 尺寸正确 |
| subtask | ✓ | `loom_subtask_history` 1 行，主对话正确收口 |
| askUser | ✓ | 卡片 + 倒计时 + 作答回流 + 续答，完整闭环 |
| schedule | ⚠ | 创建成功但 **P1-3 格式解析坑** |
| knowledge | ⚠ | 上传 200 链路通，但 **P1-2 dim=4** |
| git（本地） | 未测 | 需额外授权，价值低于风险 |
| maven（validate） | 未测 | 同上 |

## E 知识空间 —— 5/7 通过

| 检查项 | 实测 | 结论 |
|---|---|---|
| 面板打开 | 正常 | ✓ |
| 知识库列表 | 显示 `e2e-knowledge` + 「自建」标签 | ✓ |
| 文件列表 + 操作按钮 | `e2e-doc.txt` 151B + 预览/下载/删除 | ✓ |
| 中文文件名往返 | 服务端正确（`U+4E2D U+6587 U+6D4B U+8BD5`） | ✓ |
| 上传链路 | **200**（非 500） | ✓ |
| **向量维度** | **dim=4** | ❌ **P1-2** |
| 检索语义相关性 | 未测 | 受 P1-2 影响，断言无价值 |

## F 文件管理 —— 4/5 通过

| 检查项 | 实测 | 结论 |
|---|---|---|
| `/file/tree` 层级 | 正确 | ✓ |
| 文件大小 | 243 / 28 / 36774 全吻合 | ✓ |
| 系统目录不外泄 | 无 `compile-workspaces/` 等 | ✓ |
| **`by-path/view` 预览** | **404** | ❌ **P1-4** |
| **`by-path/download` 下载** | **404** | ❌ **P1-4** |

## G 技能市场 —— 11/11 通过

提交 PENDING → 审批 APPROVED → pull（`user_skill` +1）→ 空评论驳回 400 →
带评论驳回 REJECTED → 重投归档（`market_skill_archive` 保留 id=34）+ 新行 id=35 →
5 星评价 → PUT 首次成功（`editCount` 0→1）→ PUT 二次 **403**。全部符合设计。

## H 知识库市场 —— 9/9 通过

提交他人知识库 → **400**（越权防护）→ 提交自己 → PENDING → 审批 APPROVED →
标签 → 公告 → pull（`loom_user_knowledge` +1）→ 标签读取 → 市场列表。全部通过。

## I 控制台 + RBAC —— 10/10 通过

9 个 admin 端点全 200；角色授权页 **5 个 RBAC 工具可选、7 个 universal 工具完全不出现**；
撤权后 `effectiveEnabled` 立即 false；
**DEFECT-Q3-1 回归验证通过** —— 5 表 + 角色行全部级联清除。

## J MCP —— 6/7 通过

4/4 server 在线；34/34 工具描述含中文；**编码错误 0 次**（JDK 25 UTF-8 免疫）；
`sequentialthinking` 调用成功。但受 **P0-1 新变种**影响（tool_use JSON 截断）。

## K 多租户隔离 —— 11/11 通过

文件树（admin 2 项 vs e2euser 空）、会话（5 vs 0）、技能（10 vs 1）三项隔离正确；
7 个 admin 端点 + 删用户全部 **302** 拦截。

## L 降级与门控 —— 7/7 通过

RAG 关闭后：`features.knowledge=false`、`checkKnowledgeUpload=false`、上传 **503**（非 500）、
启动日志 0 次 VectorStore；前端 `#ks-button` `display:none`，
而 `#image-add-btn` 与画板 `display:flex`/36px **恒可见**。

---

## 未测项与原因

| 项 | 原因 |
|---|---|
| 改密码成功路径 | 刻意跳过 —— 会污染后续 12 个任务共用的 admin 账号 |
| 知识库检索语义相关性 | 受 P1-2（dim=4）影响，断言无价值 |
| git 工具（本地 init） | 需额外 RBAC 授权，价值低于风险 |
| maven 工具（validate） | 同上 |
| ICompileAndDeployTool 完整 docker 部署 | 安全边界明确排除（spec §1） |
| git push | 安全边界明确排除 |
| MCP 写面 | 安全边界明确排除 |

## 存量缺陷确认

| 编号 | 原判 | 巡检结论 |
|---|---|---|
| **D1** | ChatSmokeBrowserIT 等 4 处 env 守卫读 `DASHSCOPE_PERSON_TOKEN_API_KEY`，yml 现用 `MINIMAX_API_KEY` | **确认成立** —— 真实 LLM 冒烟测试从未跑过 |
| **D2** | fake EmbeddingModel 使 RAG 检索链路不可用 | **确认但改级** —— 从 P0 改为 **P1**：`H2JVectorStore` 只调 `embed()` 不调 `call()`，上传返回 200 而非 500；但实测 `dim=4` 证实向量是 4 维随机数，检索排序语义无意义 |
| **D3** | VisualBaselineBrowserIT 无 `@Test`，视觉回归空跑 | **撤销** —— 误判。该类有 `@ParameterizedTest` 驱动 16 页基线比对 + `freezeDynamicContent`/`scrubTimestamps` 两个辅助方法，回归网真实存在，只是受"仓库无 failsafe 插件、需显式 `-Dtest='*IT'`"约束 |
