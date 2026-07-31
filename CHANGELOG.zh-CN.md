# 变更日志

Spring AI LoomAgent 的所有重要变更将记录在此文件中。

---

## [1.1.36] — 2026-07-31

> **知识市场 · 工具化 RAG · 子任务 & 定时任务 · RBAC 加固**

### 🚀 新功能

#### 知识市场 (Knowledge Market)
- **知识市场服务** — 支持 RBAC：提交 → 待审批 → 管理员通过 → 已发布 → 订阅 完整工作流
- **四 Tab 知识空间 UI**：我的 / 市场 / 共享 / 我的发布 — 与技能市场模式对齐
- **知识库文件下载 & 预览** — 通过 `downloadFileUrl` / `viewFileUrl` MCP 工具
- **IFileStorage 抽象** — 两种实现：`DatabaseFileStorage`（H2 BLOB）和 `DiskFileStorage`（文件系统）
- **知识库描述字段** — `knowledge` 表新增 `description` 列；创建对话框和列表中展示
- **canEdit REST 端点** — 基于权限会话的条件编辑/删除按钮
- **角色知识库授权** — `role_knowledge` 表授权知识库给角色（登录时自动注入）

#### 工具化 RAG (Tool-based RAG)
- **IKnowledgeTool** — 替代旧 `RetrievalAugmentationAdvisor`，LLM 可调用的 `listKnowledgeBases` + `searchKnowledge`
- **会话级知识库启用** — `user_conversation.enabled_knowledge_ids`（JSON）让用户选择搜索哪些知识库
- **技能/知识摘要注入** — 动态系统提示词补充当前激活的技能和知识上下文

#### 技能市场增强
- **listMySubmitted API** — 追踪市场提交状态（PENDING / APPROVED / REJECTED）
- **撤回功能** — 撤回 PENDING 条目以编辑后重新提交
- **分页 listSkills** — 用分页 `listSkills(page, size)` 替代批量 `skillContents`

#### 子任务 & 定时任务 (Sub-task & Schedule)
- **ISubTaskTool** — LLM 可调用 `start_sub_task` / `list_sub_tasks` / `cancel_sub_task` / `get_sub_task_history`
- **IScheduleTool** — LLM 可调用创建/取消/列表/历史的定时任务
- **会话级面板** — 每个会话独立的子任务和定时任务管理 UI
- **会话级历史持久化** — V15 + V16 Flyway 迁移
- **flex-schedule 1.2.2** 集成 — 10 分钟最小间隔 / 72 小时最大生命周期 / 严格模式
- **对话标题自动重命名** — 根据首条消息自动生成对话标题

### 🔧 优化

- **README 分层结构** — 6 大核心 / 平台功能 / 高级特性，与项目概览图对齐
- **项目概览图重新设计** — ZONE 2：6 大核心（对话/知识库/文件/MCP/技能/权限），ZONE 4：双市场橙色高亮
- **知识库选择 → 多选** — 用多知识库选择器替代单一 `enableRag` 开关
- **存储根目录统一** — 所有用户数据统一在 `~/.loom/` 下（file/knowledge/datasource/jvector/compile-deploy-workspaces）
- **V2.0 迁移** — 合并 V12~V17 为单个 `V2.0__subtask_and_schedule.sql`
- **flex-schedule 升级** — 1.0-SNAPSHOT → 1.2.2，修复 BUG-14

### 🐛 Bug 修复

- **定时任务 RBAC**：恢复时过滤孤立用户、事务性市场操作、历史路由守卫
- **子任务 RBAC**：`SubTaskRegistry.kill` 按用户名隔离、父会话传播
- **MCP 持久化**：按用户名隔离命名空间、localStorage 跨刷新持久化
- **认证校验**：拒绝含横线的用户名、未登录返回 401
- **安全加固**：LLM/技能 Markdown 输出消毒、模块路径认证排除
- **文件 RBAC**：`findById` 作用域化、路径模式强制校验
- **侧边栏**：新对话持久化、安全重命名、时间戳修正
- **知识库**：删除时清除会话绑定的 KB id、防止重新提交重复键异常
- **聊天**：防止附件上传未完成就发送导致图片静默丢弃
- **管理后台**：角色 CRUD 返回正确 4xx 状态码、MCP 编辑保存竞态条件、会话消息路由
- **SSE**：subRef/disposeRequested 守卫、单记录使用守卫
- **UI**：硬编码限制替换为真实配置值（子任务历史上限、定时任务最小间隔）

### 🧪 测试
- 15 项全量回归测试套件
- `DefaultKnowledgeTool` 单元测试
- 知识市场集成测试
- 定时任务所有权 + 孤立用户过滤 + 用户上限 + 管理消息路由测试
- 99/99 E2E 通过率

###  文档
- 知识市场架构文档 (`docs/knowledge-market.md`)
- 工具化 RAG 架构文档
- 双语 SUBTASK-SCHEDULER 文档（英文 + 中文）
- 全量回归测试报告（15 项任务）
- API 文档新增知识市场章节 (§5.8)

### 🏗️ 基础设施
- **数据库**：`knowledge.description`（VARCHAR 500）、`user_conversation.enabled_knowledge_ids`（VARCHAR 1000）、`loom_scheduled_task`、`loom_schedule_execution`、`loom_subtask_history`
- **依赖**：flex-schedule 1.2.2、spring-boot-starter-actuator
- **Git**：JGit 7.6.0（`git.enabled=true` 手动启用）
- **模型**：默认切换为阿里百炼通义千问（`qwen3.7-plus`）

---

## [1.1.34] — 2026-07-10

> **子任务 & 定时任务基础**

### 🚀 新功能
- `ISubTaskExecutor` + `DefaultSubTaskExecutor` — 专用线程池同步子任务
- `ISubTaskTool` 接口 — LLM 可调用 `start_sub_task`
- `IScheduleTool` 完整签名 + `DefaultScheduleTool`
- flex-schedule 依赖集成
- 对话删除级联到子任务 + 定时任务
- 会话级取消全部 + 状态 + 级联删除

### 🐛 Bug 修复
- 打破子任务执行器 3-hop Bean 循环依赖
- 绑定 kill 语义 + 父会话 ID + MCP 回调传播
- REST cancel 路径清理幽灵任务

---

## [1.1.32] — 2026-06-27

> **模型切换 + 流式修复**

###  变更
- 切换到阿里百炼通义千问模型
- 修复 Spring AI 2.0 流式传输中推理内容丢失问题
- 版本号 1.1.31 → 1.1.32
