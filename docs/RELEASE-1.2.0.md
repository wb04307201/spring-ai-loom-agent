# Release Notes — v1.2.0

**发布分支**: `dev` → `master`
**Commit 范围**: `e53d11d` (2026-08-23) → `79c2c56` (2026-08-31)
**Commits**: 27
**文件**: 56 changed, +2,074 / -346 行(对比 8.30 起的 27 个 commit 而言)

---

## 🌟 亮点

**Capability 统一模型 + 严格 RBAC**(M1-M7 里程碑):新增 `@ToolGroup` 注解 + `role_tool` 表 + 统一 `CapabilityService`,聊天面板和 admin 控制台基于统一视图渲染。本地工具 / MCP 服务走同一套 role ∩ user_pick 过滤,**所有用户(含 admin)严格 RBAC**,不再有 admin 自动 bypass。

**M6 Universal 工具**:`@ToolGroup(defaultGranted=true)` 标记 6 个工具(`tool_file` / `tool_skill` / `tool_time` / `tool_knowledge` / `tool_subtask` / `tool_schedule`)对**所有登录用户**默认可见,完全脱离 `role_tool` RBAC 控制。admin UI 不展示这些工具的可增删入口,V2.4 migration 一键清理历史授权。

**DB schema 一站式 init**:V1.0~V2.4 五份 Flyway migration 合并为单一 `V1.0__init.sql`(已包含 V12~V17 历史 schema 演进 + M6 清理)。新装环境从空白到完整 schema 一条命令搞定。

---

## 💥 破坏性变更(部署必读)

### 1. 严格 RBAC — admin bypass 移除

**前**: admin 用户自动拥有所有 capability(隐式 bypass)

**后**: admin 走与普通 user 完全相同的 role 授权路径。**新装的 admin 账号没有任何 role → 0 capability → 必须首次进 admin 控制台手动授权**(P0.3.5 决策)

迁移:admin 部署后立即:
```bash
# 进 /admin/console.html → 创建业务 role + 授权 tool/mcp/skill/knowledge
# 给 admin 分配该 role
curl -X PUT -H "Content-Type: application/json" -d '{"roleCodes":["your-admin-role"]}' \
  /spring/ai/loom/admin/users/wb04307201/roles
```

### 2. Flyway 历史 schema 合并

**前**: `V1.0__init.sql` + `V2.0__subtask_and_schedule.sql` + `V2.1~V2.4` 增量

**后**: 单一 `V1.0__init.sql`(完整 schema 一站式)

**升级路径**:
- **全新部署**: 正常跑 `V1.0__init.sql` + 业务 `V1.1__init_app_data.sql`
- **已有部署**(已跑过 V1.0 + V2.x): 必须 `rm -rf ~/.loom/datasource` 重跑,或 `flyway baseline` + 手动数据迁移
- 本项目**不接受在已运行实例上就地升级 schema**

---

## ✨ 新功能

### Capability 统一(M1-M7)
- `feat(capability)` `e66dda8` — 新增 `@ToolGroup` 注解 + `role_tool` 表 + 统一 `CapabilityService`(本地 + MCP 同源)
- `feat(rbac)` `8f1ca91` — 严格 role-based capability gating + 统一 chat filter + API 端点
- `feat(ui)` `0411022` — 聊天面板统一 "工具与服务" + admin 角色工具授权
- `feat(capability)` `8427ebf` — M6 universal tools(`@ToolGroup(defaultGranted=true)`)+ V2.4 migration
- `feat(ui)` `4a72ea5` — M6 admin "已分配角色" badge 列 + 聊天面板 "工具" 文案 + checked-disabled 死锁修复
- `chore` `f3041ca` — M6 杂项(CLAUDE.md 文档、test app 配置、UserInfo/DefaultUser 调整)

### 工具能力
- `feat(capability)` — 9 个 `I*Tool` 接口(`ITimeTool` / `ISkillTool` / `IFileTool` / `IKnowledgeTool` / `ISubTaskTool` / `IScheduleTool` / `IGitTool` / `IMavenTool` / `ICompileAndDeployTool`)通过 `@ToolGroup` 注解声明 group_name;admin `/admin/roles/{code}/tools` 端点统一授权

### 文件存储
- 新增 `loom_file_content` 表(DB 模式文件内容存储,作为默认 DiskFileStorage 的替代)
- 新增 `loom_market_knowledge` / `loom_user_knowledge` / `loom_role_knowledge`(知识库市场三件套)

---

## 🐛 修复

### RBAC & 数据完整性
- `fix(db)` `cd70e98` — `user_role.username` 加 FK + CASCADE,**修 P0.3.3** 删 user 留 orphan bug
- `fix(rbac)` `2e16cb3` — role 删除时 cascade 清 `user_role` / `role_mcp` / `role_tool`(B.2.1 修复)
- `fix(rbac)` `80c55bb` — overlong role code/name → 400 而非 500(P3.1)
- `fix(rbac)` `d1d3e0f` — 不存在 user / role_code → 400/404 而非 500(P2,TDD 修复)
- `fix(rbac)` `ef8a554` — `syncRoleSkills` SQL 移除已删的 `m.version` 列

### Capability / Service
- `fix(capability)` `51ab723` — 用 live `@ToolGroup` 元数据替换硬编码 `KNOWN_TOOL_GROUPS` 列表(B.5.5)
- `fix(admin-ui)` `ef74304` — admin UI 所有 fetch 显式 `charset=UTF-8`(浏览器路径 GBK locale 解析修复)

### Memory & MCP
- `fix(mcp)` `19e1199` — `loom-*-mcp` 工具改用 `@McpTool` 注解 + snake_case 命名
- `fix(memory)` `9c1f245` — `LastChunkAdvisor` 在流响应空 chunk 时 NPE
- `fix(memory)` `0bc7df8` — `doFinally` 只在 `ON_COMPLETE` 时写 memory(回归修复)

### Test config
- `fix(test)` `b8d01d5` — 切回 dashscope,排除 ollama/minimax chat 冲突
- `chore(test)` `657a813` — enable Anthropic thinking for MiniMax-M3
- `chore(test)` `50f66e8` — test app 切换 anthropic-only chat + Ollama embedding

---

## 🔧 重构

- `refactor(db)` `79c2c56` — V1.0~V2.4 五份 migration 合并为单一 `V1.0__init.sql`
- `chore(mcp)` `c8d24aa` — `.mcp.json` 指向 4 个 `loom-*-mcp` jars

---

## 🧪 测试

### 新增测试文件(7 个)
- `DefaultRoleServiceErrorMappingTest.java` — 8 个测试覆盖 4xx 映射(P2 修复)
- `CapabilityServiceTest.java` — 18 个测试覆盖 universal/RBAC 过滤逻辑(M6 核心)
- `AdminRouterSpotTest.java` — admin router 抽测
- `ConversationRouterTest.java` / `DefaultUserConversationTest.java` / `DefaultUserTest.java` — user 模块
- `KnowledgeMarketIntegrationTest.java` — 知识库市场集成
- `SidebarFrontendContractTest.java` — UI 契约
- `LoomAgentToolAutoConfigTest.java` — autoconfigure 校验

### 测试状态
- **L1 Maven** — 14 模块编译 + **364/364 测试全过**(0 fail / 0 err)
- **L2 HTTP e2e** — 22 P0 + 4 M6 场景,本会话后 **20/20 通过**(P2 4xx 修复 + lock-in)
- **L3 Playwright UI** — 7 截图覆盖 admin / chat / 普通 user,全部通过(后由 commit `b37f101` 删除 docs/testing 目录)

---

## 📝 文档

- `docs(CLAUDE)` `8142df0` — M1-M7 能力统一、严格 RBAC、cascade 写入项目说明
- `docs(testing)` `dd6465a` — P0 综合功能测试报告(22 场景 / 21 通过 + 1 P2 bug)
- `docs(testing)` `f5a82ca` — M6 + 全功能综合测试报告(后由 `b37f101` 删除目录)
- `docs(tools,customization,api)` `9fb6536` — TOOLS.md / CUSTOMIZATION.md / API.md 对齐 M6 universal/RBAC 模型,移除过时 "*.enabled yml 开关" 描述
- `chore` `b37f101` — 删除 `docs/testing` 目录(用户要求)

---

## 🚀 升级指南

### 全新部署

```bash
# 1. 拉 dev 分支(包含本批次全部 commit)
git checkout dev

# 2. 构建
mvn clean install -Dgpg.skip=true

# 3. 启动 test app
mvn spring-boot:run -pl spring-ai-loom-agent-test

# 4. 默认 admin 账号 wb04307201 / 123456(请立即改密)
# 5. 首次部署必须:admin 控制台 → 创建业务 role + 授权 → 给 admin 分配 role
# 6. Schema: 单 V1.0__init.sql + 业务 V1.1__init_app_data.sql 自动 Flyway
```

### 已有 V1.0 + V2.x 实例升级

```bash
# 选项 A: 全新库(推荐,dev / staging)
rm -rf ~/.loom/datasource
# 重启 → Flyway 自动跑合并后的 V1.0 + V1.1

# 选项 B: flyway baseline(生产)
# 1. 备份 ~/.loom/datasource/
# 2. flyway baseline -baselineVersion=2.4
# 3. 手动迁移数据(market_skill / user_skill 等业务表可能需要保留)
# 4. 启动 → Flyway 跳过 V1.0~V2.4(标记为 baseline)
```

### Admin 首次配置

```bash
# 1. 登录 admin 账号
# 2. 创建业务 role(如 "研发" / "运营" / "客服")
# 3. 给 role 授权:
#    - 工具:/admin/roles/{code}/tools
#    - MCP: /admin/roles/{code}/mcps
#    - Skill: /admin/roles/{code}/skills
#    - Knowledge: /admin/roles/{code}/knowledge
# 4. 把 role 分配给 admin 自己 + 普通 users
```

---

## 📊 数字

| 指标 | 数 |
|------|----|
| Commits | 27 |
| Files changed | 56 |
| Lines added | +2,074 |
| Lines removed | -346 |
| New files | 7 |
| Deleted files | 4(全为合并的 V2.1-V2.4 migrations) |
| Tests added | 26 个新单测 |
| Tests total | 364(全部通过) |
| Files (含历史对比 v1.1.0 → HEAD) | 353 changed, +55,597 / -42,148 行(整开发周期累计) |

---

## ⚠️ 已知问题

无新增已知问题。本批次 P2 bug(4xx 映射)已修复并 lock-in。`user_role.username` 缺 FK 的 P0.3.3 历史问题已在 `cd70e98` 修复。

---

## 🔗 完整 commit 列表

```
8427ebf  feat(capability): M6 universal tools (defaultGranted + V2.4 cleanup)
4a72ea5  feat(ui): M6 admin role badge + chat panel 工具文案 + checked-disabled 修复
f3041ca  chore: M6 docs + test app config + user model/svc
9fb6536  docs(tools,customization,api): align with M6 universal/RBAC model
b37f101  chore: remove docs/testing directory
f5a82ca  docs(testing): M6 + 全功能综合测试报告
d1d3e0f  fix(rbac): 400/404 instead of 500 for missing user/role (P2)
b8d01d5  fix(test): 切回 dashscope,排除 ollama/minimax chat 冲突
657a813  chore(test): enable Anthropic thinking for MiniMax-M3 (enabled enum)
80c55bb  fix(rbac): 400 instead of 500 for overlong role code/name (P3.1)
cd70e98  fix(db): V2.3 add user_role.username FK + CASCADE (P0.3.3)
dd6465a  docs(testing): add P0 comprehensive functional test report
8142df0  docs(CLAUDE): document capability unification (M1-M7) + strict RBAC + cascade
0bc7df8  fix(memory): doFinally only writes on ON_COMPLETE (regression from 9c1f245)
39724b4  test(test-app): fix Spring context failures for ollama-less dev
da2d5d3  test(capability): add unit tests for CapabilityService core filtering
ef74304  fix(admin-ui): explicit charset=UTF-8 on all admin UI fetch calls
51ab723  fix(capability): replace KNOWN_TOOL_GROUPS hardcode with live @ToolGroup metadata (B.5.5)
2e16cb3  fix(rbac): cascade-delete sub-tables when deleting a role (B.2.1)
ef8a554  fix(skill): drop m.version from syncRoleSkills SQL (column was removed in V1.0)
0411022  feat(ui): unified 工具与服务 chat panel + admin role tool authorization
8f1ca91  feat(rbac): strict role-based capability gating + unified chat filter + API endpoints
e66dda8  feat(capability): @ToolGroup annotation + role_tool table + unified CapabilityService
50f66e8  chore(test): wire anthropic-only chat + Ollama embedding in test app
9c1f245  fix(memory): LastChunkAdvisor NPE on empty-result streaming chunks
c8d24aa  chore(mcp): point .mcp.json at the 4 loom-*-mcp jars
19e1199  fix(mcp): switch loom-*-mcp tools to @McpTool annotation with snake_case names
```

Co-Authored-By: Claude Code <noreply@anthropic.com>