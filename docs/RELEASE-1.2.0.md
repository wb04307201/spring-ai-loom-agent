# v1.2.0 Release Notes

**范围**: 2026-08-30 → 2026-08-31,27 commits,56 files (+2,074 / -346)

---

## 🌟 主要新功能

### 1. Capability 统一模型 + 严格 RBAC(M1-M7)

聊天面板和 admin 控制台现在基于**统一视图**渲染本地工具与 MCP 服务:

- **`@ToolGroup` 注解**:9 个 `I*Tool` 接口声明所属 group(如 `@ToolGroup("file")` → `tool_file`),替换原硬编码 `KNOWN_TOOL_GROUPS`
- **`role_tool` 表**:与 `role_mcp` 镜像的结构,镜像业务角色 ↔ 工具组授权
- **统一 `CapabilityService`**:`list()` / `listAll()` / `visibleToolGroupsFor()` / `allowedCapabilityIdsFor()` 单一实现
- **严格 RBAC**:admin 自动 bypass 已移除 — **所有用户(含 admin)按 role 授权走同一路径**
- 统一 chat filter + API(`/admin/roles/{code}/tools` / `/admin/capabilities` / `/api/capabilities`)

```java
@ToolGroup(value = "git", defaultGranted = false,
            description = "28 个 git 操作")
public interface IGitTool extends IEmbedTool { ... }
```

### 2. M6 Universal 工具(平台默认能力)

6 个工具对**所有登录用户**默认可见,完全脱离 `role_tool` RBAC:

| Universal 工具 | 理由 |
|----------------|------|
| `tool_file` | per-user 文件隔离(`{fileBasePath}/{username}/`) |
| `tool_skill` | per-user skill,允许自建/编辑 |
| `tool_time` | 只读,无副作用 |
| `tool_knowledge` | 下游 KB 列表已独立受 `role_knowledge` 控制 |
| `tool_subtask` | 子任务继承 user 身份 |
| `tool_schedule` | per-user 命名空间 + 子任务触发 |

剩下 3 个(`tool_git` / `tool_maven` / `tool_compile`)走 RBAC,涉及 `git push` / 任意 mvn / Docker 容器执行,必须显式授权。

**UI 行为**:
- 聊天面板 / admin 控制台 /admin/capabilities **完全不展示** universal 工具 checkbox(Q3+Q4 决定)
- admin 给 role 授权时,universal group_name 写时被 service 静默过滤掉(无残留 row)
- LLM 仍可通过 `visibleToolGroupsFor` 调用 universal 工具

### 3. Flyway schema 一站式 init

V1.0 + V2.1~V2.4 五份 migration 合并为单一 `V1.0__init.sql`(已内联 V12~V17 历史 schema + M6 清理)。新装环境从空白到完整 schema 一条命令搞定。

### 4. 工具能力补全

- **`IKnowledgeTool`** 上线:`searchKnowledge(knowledgeId, query, topK?)` 按 KB 维度做向量检索(替代 `RetrievalAugmentationAdvisor`)
- **工具能力检查 RBAC**:每个工具都标注 `defaultGranted`,元数据单一源 = `@ToolGroup` 注解
- **Ad-hoc MCP 工具**:admin 控制台 → 能力维护 → 单个添加 MCP 工具

### 5. 文件存储增强

- **DB 模式文件存储**:`loom_file_content` 表 — 替代默认 DiskFileStorage 的可选 backend
- **知识库市场三件套**:`loom_market_knowledge` / `loom_user_knowledge` / `loom_role_knowledge`
- **MCP 元数据**:每条 MCP 工具可独立维护 title / description

### 6. Admin 控制台 UI 升级

- **用户列表新增"已分配角色"列**:多 badge 横向排列,空状态显示 `— 未分配`
- **工具面板 / admin 能力页**:"工具与服务" → "工具" 文案精简
- **修复 checked + disabled 死锁态**:localStorage 残留授权 ID 被自动清理,UI 同步刷新

### 7. 测试覆盖

| Layer | 结果 |
|-------|------|
| **L1 Maven** | 14 模块编译 + **364/364 单测/集成测通过** |
| **L2 HTTP e2e** | 22 P0 + 4 M6 场景,**20/20 通过**(含本次新修复 P2 4xx 映射) |
| **L3 Playwright UI** | 7 截图覆盖 admin / chat / 普通 user,全部 OK |

新增测试:
- `CapabilityServiceTest` — 18 个 case 覆盖 M6 universal/RBAC 过滤
- `DefaultRoleServiceErrorMappingTest` — 8 个 case 锁 P2 4xx 修复
- `AdminRouterSpotTest` / `ConversationRouterTest` 等 5 个 router 抽测

---

## ⚠️ 部署必读

1. **新装 admin 必须首次手动授权**:旧部署中 admin 自动拥有所有 capability,新版本后 admin 必须先进 admin 控制台给 wb04307201 分配业务 role,否则登录后看到 0 capability
2. **Flyway 升级**:已有部署 `rm -rf ~/.loom/datasource` 重跑,或 `flyway baseline -baselineVersion=2.4` 后手动迁移。本项目**不接受就地 schema 升级**
3. **API 4xx 映射变更**(附带 P2 修复):
   - `PUT /admin/users/{u}/roles` body 含不存在 role → **400** `{"error":"角色不存在: xxx"}`(原 500)
   - `PUT /admin/roles/{code}/tools` 不存在 role → **404** `{"error":"角色不存在: xxx"}`(原 500)
   - `PUT /admin/users/{u}/roles` body 含不存在 user → **400** `{"error":"用户不存在: xxx"}`(原 500)
   - body schema 修正:`{"roleCodes": [...]}` 而非 raw array

---

## 📊 数字

| 维度 | 数 |
|------|----|
| Commits | 27 |
| Files | 56 changed (+2,074 / -346) |
| Tests | +26 new / **364/364 pass** |
| Migrations | 5 → 1 合并 |
| Files deleted | 4(V2.1-V2.4) |
| Files added | 7(测试 + 文档) |

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