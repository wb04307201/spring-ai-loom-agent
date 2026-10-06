# 全功能多维度 E2E 巡检设计

- 日期：2026-10-06
- 分支：`dev-sp2`
- 交付形态：**巡检为主，缺陷清单交付**（不向 `src/test` 写测试代码）

## 1. 目标与非目标

### 目标

启动真实 LoomAgent 服务，通过真实浏览器模拟人工操作，覆盖全功能面，产出：

1. **缺陷清单** —— 分级 P0 / P1 / P2 + 复现步骤 + 证据（截图 / console 输出 / 后端日志片段）
2. **覆盖矩阵** —— 12 个维度 × 通过 / 失败 / 未测
3. **巡检留档** —— 执行命令与逐项结果记在 `docs/`，供后续复现

### 非目标

- 不向 `spring-ai-loom-agent-test/src/test` 新增测试类
- 不修缺陷（发现后交由用户决定是否进入修复流程）
- 不碰 `~/.loom`（沿用 CLAUDE.md 既有隔离约定）
- 不跑 `ICompileAndDeployTool` 完整 docker 部署管线（真实副作用 + 耗时过长）
- 不做 git push 到远程仓库
- 不碰 MCP server 的写面

## 2. 已确认的存量缺陷（侦察阶段发现，无需运行即可确认）

### D1 —— 真实 LLM 冒烟测试恒跳过（死锁）

`ChatSmokeBrowserIT:41` 的环境守卫读 `DASHSCOPE_PERSON_TOKEN_API_KEY`，而 `spring-ai-loom-agent-test/src/main/resources/application.yml` 现用 `${MINIMAX_API_KEY}`。全仓共 4 处 guard 使用旧变量名、2 处使用新变量名 —— 唯一的真实 SSE 链路冒烟从未真正执行过。

### D2 —— RAG 检索链路从未被真实验证（高发区）

`TestAnthropicOptionsConfig`（位于 `src/main/java`，随主应用启动）注册的 fake `EmbeddingModel`：

- `embed()` 返回 4 维随机向量（`new Random(text.hashCode())`）
- `call(EmbeddingRequest)` 直接 `throw new UnsupportedOperationException()`
- 类注释自述 "The test app does not perform real embedding lookups in the unit/IT gate"

后果：`IUpload.uploadWithKnowledge` → 文档解析 → embedding → `loom_vector_store` 落库 → `searchKnowledge` 检索，任何走 `call()` 的真实路径会 500。知识空间是本项目的核心卖点之一，此链路一次都没跑过。

### D3 —— 截图基线回归空跑

`VisualBaselineBrowserIT` 的 `@Test` 方法计数为 0（模板方法未被 JUnit 识别），16 个页面的视觉回归网实际不存在。

## 3. 执行环境

### 启动序列

```bash
# 1. 库模块装进 m2 —— 改了 Flyway 迁移文件必须走这步，否则 test 模块跑到 jar 里的旧副本
mvn install -pl spring-ai-loom-agent -Dgpg.skip=true

# 2. 全新隔离库（IT 仪式，不触碰 ~/.loom）
rm -rf spring-ai-loom-agent-test/target/test-ds
rm -rf spring-ai-loom-agent-test/target/test-users

# 3. 启动真实服务（server.port=8080，src/main/resources/application.yml）
mvn spring-boot:run -pl spring-ai-loom-agent-test
```

### 环境事实（已验证）

| 项 | 状态 |
|---|---|
| `MINIMAX_API_KEY` | 已设置（真实 LLM 可用） |
| Docker | 28.3.2（本次不跑部署管线） |
| Playwright Chromium | `~/AppData/Local/ms-playwright/` 下 4 个版本 |
| Node | v24.13.0（`npx.cmd` 拉 MCP server 用） |
| Maven | 可用 |

### 数据隔离

- 数据库：`./target/test-ds`（相对 test 模块，由 `src/test/resources/application.yml` 覆盖）
- 用户树：`./target/test-users`
- MCP 沙箱：`~/.loom/mcp`（MCP server 自身配置，非巡检数据）

`mvn clean` 即可全清。本次巡检产生的测试用户、市场条目、会话全部写入 `./target/test-ds`。

## 4. 覆盖的 12 个维度

| # | 维度 | 代表场景 | 手段 |
|---|---|---|---|
| A | 启动与数据底座 | Flyway V1.0 / V1.1 / V1.2 / V2.0 全跑通，`flyway_schema_history` 版本与内容核对 | 启动日志 + H2 查询 |
| B | 认证与会话 | 未登录跳转、错误密码、HttpOnly cookie、改密码、登出、`/admin/**` 门控 | 浏览器 |
| C | 聊天主干 | SSE 流式、thinking 面板、stop 中断、多轮记忆、会话增删改、滚动按钮、markdown 消毒、token 用量 | 浏览器 |
| D | 工具层（真实 LLM 驱动） | time / file / skill / git(本地) / maven(validate) / http(真实外网) / render / subtask / schedule / askUser | 浏览器发消息 + 观察工具调用 |
| E | 知识空间 | 建库 → 上传 → 检索 → 引用；画板导出 PNG 作附件 | 浏览器 |
| F | 文件管理模态框 | 目录树展开、预览、下载 | 浏览器 |
| G | 技能市场 | 浏览 → 提交 PENDING → admin 审批通过 / 驳回 → 评价 → pull | 浏览器 |
| H | 知识库市场 | 知识库版本的提交 / 审批 / 评价 / pull + 标签筛选 | 浏览器 |
| I | admin 控制台 + RBAC 负向面 | 用户 / 角色 / 工具授权 / MCP 描述维护、5 表级联删除、stats 图表、conversation 流水；**负向面**：未授权用户看不到 admin 页、未授权工具不进 tool callback | 浏览器 + API |
| J | MCP | 4 个 server 连接状态、中文描述是否乱码（Windows GBK 坑） | admin MCP 页 + 后端日志 |
| K | 多租户隔离 | 两用户文件 / 会话 / 技能互不可见 | 双 context 浏览器 |
| L | 降级与门控 | 关闭 RAG 后知识空间按钮消失、附件 / 画板仍可见 | 改属性重启 |

### 维度间依赖

- **D 依赖 A、B、C**：底座不可信则工具层结果不可信
- **E 高发**：D2 已知缺陷，预期本维度问题最集中
- **L 需要另一次启动周期**：用 Spring Boot 命令行属性临时覆盖，不改任何文件——
  `mvn spring-boot:run -pl spring-ai-loom-agent-test -Dspring-boot.run.arguments=--spring.ai.loom.agent.rag.enabled=false`。
  L 排在最后一波，避免打断进行中的巡检

## 5. 三波推进

| 波次 | 维度 | 理由 |
|---|---|---|
| Wave 1 | A + B + C | 先确认底座可靠，否则后面全白跑 |
| Wave 2 | D + E + G + H + I | 主力工作量 |
| Wave 3 | F + J + K + L | 边界与降级 |

Wave 1 结束后中途汇报一次，再继续 Wave 2 / 3。

## 6. 执行手段

模拟人工操作，通过 Chrome DevTools MCP 驱动真实浏览器：

- 真登录（填表单、点按钮），不用 API 直调
- 真打字发送消息，等真实 LLM 流式响应
- 真上传文件（文件选择器）
- 真点审批 / 授权 / 删除按钮
- 真拖画板绘图
- 逐项截图取证，console 输出与后端日志同时留档

截图落 `docs/e2e-audit-2026-10-06/screenshots/`。

## 7. 缺陷分级标准

| 级别 | 判据 |
|---|---|
| P0 | 功能不可用 / 数据丢失 / 越权，或全链路断裂导致该功能完全无法演示 |
| P1 | 主要路径可完成但结果错误、明显退化或关键交互失效 |
| P2 | 文案、样式、边界情况等非阻断问题 |

## 8. 交付物清单

1. `docs/e2e-audit-2026-10-06/defects.md` —— 缺陷清单（分级 + 复现 + 证据）
2. `docs/e2e-audit-2026-10-06/coverage-matrix.md` —— 覆盖矩阵
3. `docs/e2e-audit-2026-10-06/screenshots/` —— 证据截图
4. `docs/e2e-audit-2026-10-06/execution-log.md` —— 执行命令与逐项结果

本 spec 落 `docs/superpowers/specs/2026-10-06-full-e2e-audit-design.md`。

## 9. 风险与应对

| 风险 | 应对 |
|---|---|
| 真实 LLM 非确定性，同一场景两次结果不同 | 只断言链路通与结构健康，不断言语义内容；每项记录实际观察到的输出 |
| 真实 LLM 有延迟，整轮可能很长 | 单场景超时上限明确设定；超时不重试，直接记为"未测 + 原因" |
| `npx.cmd` 首次拉 MCP server 慢或失败 | Wave 3 的 J 维度容许跳过，记为未测 + 原因 |
| 改 `rag.enabled` 重启会打断进行中的巡检 | L 维度排在最后一波，单独启动周期 |
| 用户已有 `dev-sp2` 未提交改动（`PathSecurityUtils.java`） | 巡检只读不改，绝不覆盖该文件 |

## 10. 验收标准

- 12 个维度全部有明确结论（通过 / 失败 / 未测 + 原因），无"没来得及测"的空项
- 每个失败项有可复现步骤和证据
- D1 / D2 / D3 三个存量缺陷在报告中得到确认或修正
- 报告可用 §3 的启动序列复现
