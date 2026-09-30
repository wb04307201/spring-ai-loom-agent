# 子系统 #11 — Skill picker + 市场审批流 E2E 剧本(新增)

**目标**: 验证聊天内 skill picker + 市场 CRUD + 审批流(PENDING→APPROVED/REJECTED)+ 5星评价 + 公告 + 边界
**前置**: Task 0 创建 test-user-1/2 + Task 1 admin 登录
**预计 wall clock**: 25 min

---

## Smoke 用例: 聊天 UI skill picker 弹出

**步骤**:
1. (admin 登录 chat) 找到 skill picker 入口(`/` 触发或 picker 按钮)
2. 触发 picker
3. take_snapshot → 断言:
   - picker 弹出,显示已启用 skill 列表(2 个官方)
   - 含「搜索」框
4. 截图

**断言**:
- picker 可达

**截图**: `docs/notes/browser-baselines-2026-10-01/11-skill-approval/skill-picker-open.png`

---

## E2E-1: picker 选择 skill → 注入到 system prompt + LLM 使用

**步骤**:
1. picker 中 click「STAR-IJ 讲清一件事」
2. close picker
3. fill "用 STAR-IJ 框架写述职报告"
4. click 发送
5. SSE 检测
6. take_snapshot → 断言:
   - chat 流中显示「已选择 skill: STAR-IJ」(或类似 UI 提示)
   - LLM 响应严格按 STAR-IJ 框架(S/T/A/R/I/J)
   - `selectedSkillName` 强指令注入生效(绕过 LLM 工具选择偏差)
7. 截图

**断言**:
- picker 选择强指令生效
- LLM 严格遵循所选 skill

**截图**: `docs/notes/browser-baselines-2026-10-01/11-skill-approval/picker-skill-used.png`

---

## E2E-2: admin 创建 APPROVED skill(createApproved)→ 立即上架

**步骤**:
1. navigate /admin/skills
2. click「新建」skill → 表单
3. fill:
   - name="E2E 测试 approved skill"
   - description="测试"
   - content="# 测试内容"
4. 提交(走 createApproved,admin)
5. take_snapshot → 断言:
   - 列表立即显示该 skill,状态 APPROVED
   - 普通 user 可立即在 picker 中看到
6. 截图

**断言**:
- createApproved 立即上架(无需 PENDING 流程)

**截图**: `docs/notes/browser-baselines-2026-10-01/11-skill-approval/admin-create-approved.png`

---

## E2E-3: admin 提交 PENDING skill → 普通 user 看到 PENDING 不可 pull

**步骤**:
1. 清 cookie → test-user-2 登录
2. navigate /skills(或 picker)
3. take_snapshot → 断言:
   - 列表中**不显示** admin 刚提交 PENDING 的 skill
   - 或显示但状态标 PENDING + 不可用
4. **尝试调**: fill "用 E2E 测试 pending skill"
5. SSE 检测
7. take_snapshot → 断言 LLM 不能调用(403 / 无可用 skill)
8. 截图

**断言**:
- PENDING 对普通 user 不可见 / 不可用

**截图**: `docs/notes/browser-baselines-2026-10-01/11-skill-approval/pending-not-visible.png`

---

## E2E-4: admin 拒绝 + reject comment → 归档到 market_skill_archive

**步骤**:
1. admin 登录 /admin/skills
2. 找到 PENDING 的 skill → 「拒绝」按钮
3. 填 reject comment="内容不符合要求"
4. 提交
5. take_snapshot → 断言:
   - 列表中状态变 REJECTED
   - 归档到 `market_skill_archive`(查 DB)
6. 截图

**断言**:
- 拒绝流程完整
- 归档表正确记录

**截图**: `docs/notes/browser-baselines-2026-10-01/11-skill-approval/reject-archive.png`

---

## E2E-5: 5 星评价 + edit_count 门控

**步骤**:
1. 对「E2E 测试 approved skill」(E2E-2 创建)进行 5 星评价
2. 第一次编辑(改 description)→ 允许
3. 连续编辑 3 次以上 → 第 N+1 次触发 edit_count 门控,显示「编辑次数已达上限,请重新提交审批」
5. 截图

**断言**:
- edit_count 门控生效(M4/#4 修复)

**截图**: `docs/notes/browser-baselines-2026-10-01/11-skill-approval/5star-editcount.png`

---

## E2E-6(边界): 同名 REJECTED 重投 → 归档旧行 + 新 PENDING

**步骤**:
1. 创建 skill name="重名测试",提交 PENDING → admin 拒绝(comment)
2. 再次以同名 name="重名测试" 提交
3. take_snapshot + 查 DB:
   - 旧行(`market_skill`)在 `market_skill_archive`,id 保留
   - 新行在 `market_skill`,新 id,状态 PENDING
4. 截图

**断言**:
- 归档 + 新 PENDING 共存(id-preserving)

**截图**: `docs/notes/browser-baselines-2026-10-01/11-skill-approval/resubmit-archive.png`

---

## E2E-7(边界): USER_CREATED 同名 → pull 拒绝覆盖

**步骤**:
1. test-user-1 创建 user_skill name="user-owned-skill"(USER_CREATED)
2. admin 试图以同名 name="user-owned-skill" 提交 PENDING
3. take_snapshot → 断言:
   - admin 端显示「已有同名 USER_CREATED skill」错误
   - 服务端拒绝(M4/#4 修复)
4. 截图

**截图**: `docs/notes/browser-baselines-2026-10-01/11-skill-approval/user-created-protect.png`

---

## E2E-8(边界): 公告发布 + 显示

**步骤**:
1. admin 在 skill 详情页发布公告:"本 skill 正在维护,请暂停使用"
2. take_snapshot → 断言:
   - 公告显示在 skill 卡片上
   - 普通 user picker 中看到公告
3. 清 cookie → test-user-2 登录 → picker
4. take_snapshot → 断言公告可见
5. 截图

**截图**: `docs/notes/browser-baselines-2026-10-01/11-skill-approval/announcement.png`

---

## 失败处理

- 整剧本 3 次重试
- E2E-1 picker 强指令失效 = MAJOR(LLM 工具选择偏差未被绕开)
- E2E-3 PENDING 普通 user 可见 = CRITICAL(M4 审批流失效)
- E2E-4 拒绝未归档 = MAJOR
- E2E-6/7 同名覆盖破坏 = MAJOR(M4 修复失效)
- 边界失败 = MAJOR/MINOR

## 截图清单

共 ~8 张

## 清理

- 删除所有 E2E 测试创建的 skill(包括 approved / pending / rejected)
- 清空评价 + 公告
- 撤回 user_skill(user-owned-skill)