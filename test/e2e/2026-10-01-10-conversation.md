# 子系统 #10 — 对话历史 E2E 剧本(新增)

**目标**: 验证历史会话列表 + conversation.html 加载 + thinking 折叠 + 工具调用 log + 用户隔离
**前置**: Task 0 预生成 3-5 轮对话(已完成) + Task 1 admin 登录
**预计 wall clock**: 12 min

---

## Smoke 用例: 历史侧栏 + 列表渲染

**步骤**:
1. (admin 登录 chat) 找到历史/侧栏入口(具体 UI 位置查 app.js)
2. click 展开
3. take_snapshot → 断言:
   - 侧栏展开
   - 列表显示历史会话(应有 1 条 fixture 生成的)
4. 截图

**断言**:
- 侧栏可达
- 会话列表渲染

**截图**: `docs/notes/browser-baselines-2026-10-01/10-conversation/sidebar-expanded.png`

---

## E2E-1: 点开历史会话 → conversation.html 加载 + 消息流水

**步骤**:
1. click 列表中 fixture 生成的历史会话
2. wait_for pageId text=["对话", "消息"]
3. take_snapshot → 断言:
   - URL 含 `/conversation/{id}`
   - 消息流水按时间顺序显示
   - user / assistant 消息气泡区分
4. 截图

**断言**:
- 历史会话完整加载
- 消息不丢失 / 不错位

**截图**: `docs/notes/browser-baselines-2026-10-01/10-conversation/conversation-loaded.png`

---

## E2E-2: thinking 折叠面板按轮显示

**步骤**:
1. (接 E2E-1) 历史会话中,展开 assistant 消息的 thinking 折叠区
2. take_snapshot → 断言:
   - thinking 内容按轮显示
   - 时间戳就近配对(2026-09-19 修复)
   - 不混入库记忆正文
3. 截图

**断言**:
- thinking 完整保留
- 按轮渲染,不串台

**截图**: `docs/notes/browser-baselines-2026-10-01/10-conversation/thinking-per-turn.png`

---

## E2E-3: 工具调用 log 正确展示(loom_tool_call_log join)

**步骤**:
1. (接 E2E-1) 历史会话中,展开 assistant 消息中的工具调用折叠区
2. take_snapshot → 断言:
   - 工具调用折叠区可见
   - 含工具名 / 参数 / 结果
3. 截图

**断言**:
- tool_call_log 正确 join 到 conversation

**截图**: `docs/notes/browser-baselines-2026-10-01/10-conversation/tool-call-log.png`

---

## E2E-4: 删除历史 → DB 清理

**步骤**:
1. 在侧栏列表中 hover 历史会话 → 删除按钮
2. click 删除 → 确认
3. take_snapshot → 断言:
   - 列表移除该会话
   - 刷新后仍不存在
4. 查 DB: conversation_id 关联的 chat_usage / chat_reasoning / tool_call_log 全部清理
5. 截图

**断言**:
- 删除完整

**截图**: `docs/notes/browser-baselines-2026-10-01/10-conversation/delete-conversation.png`

---

## E2E-5(边界): 空会话列表(empty state)

**步骤**:
1. 创建全新 test-user-3(从未发过消息)
2. test-user-3 登录 chat → 侧栏
3. take_snapshot → 断言:
   - 显示 empty state 提示「暂无历史会话」或类似
   - 无控制台 error
4. 截图

**截图**: `docs/notes/browser-baselines-2026-10-01/10-conversation/empty-state.png`

---

## E2E-6(边界): 跨账号历史不可见(用户隔离)

**步骤**:
1. admin 在侧栏记录 N 条历史会话
2. 清 cookie → test-user-1 登录
3. navigate /conversation/{admin 的某会话 ID}
4. take_snapshot → 断言:
   - 显示 404 / 「无权限」/ redirect 到 index.html
   - 不能查看 admin 的会话
5. 截图

**断言**:
- 用户隔离严格(防止跨账号读历史)

**截图**: `docs/notes/browser-baselines-2026-10-01/10-conversation/cross-user-isolated.png`

---

## 失败处理

- 整剧本 3 次重试
- E2E-1 历史加载失败 = CRITICAL(核心功能)
- E2E-2 thinking 串台 = MAJOR(2026-09-19 修复失效)
- E2E-6 跨账号可见 = CRITICAL(安全洞)
- 边界失败 = MAJOR/MINOR

## 截图清单

共 ~7 张

## 清理

- 删除 test-user-3
- 撤回 E2E-4 删除的历史(可选)