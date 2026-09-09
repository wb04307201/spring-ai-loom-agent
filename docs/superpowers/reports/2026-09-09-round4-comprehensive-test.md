# 第四轮全面测试报告(2026-09-09)

> 触发:base 角色种子(commit `01a6236`)落地后的全量验证 —— 新装开箱体验已改变(admin 默认有 4 MCP + 2 技能),此状态前四轮从未覆盖
> 环境:Windows + Chrome DevTools MCP,真实 DashScope qwen3.8-max,MCP 5 servers 启用,全新库(14:38 起,Flyway applied 2 migrations),8080
> 方法:真实 LLM 端到端 + 合成 SSE 帧(确定性前端)+ curl API 探测 + H2 直查(权威)+ 服务端日志核对 + Chrome 响应式 emulation

---

## 结论

**发现 1 个 Important 缺陷(DEFECT-Q3-1,pre-existing,本轮首曝)—— 已修复(commit `f1a20c8`),三层验证全绿**;其余维度全绿。

| 维度 | 结果 | 说明 |
|------|------|------|
| Q0 基线回归门 | ✅ | 修复后全量:lib **185/0** · test **412/0** · 清库 IT **128/0/3skip**(+RoleDeleteCascadeIT) |
| Q1 开箱首跑 | ✅ | base 种子 → admin 开箱 4 MCP 预勾 + 2 技能,零配置可用 |
| Q2 ROLE_GRANTED 生命周期 | ✅ | locked 不可编辑/删除;pull 转换行为记录 |
| Q3 角色删除级联(破坏性) | ❌→✅ **DEFECT-Q3-1 已修** | delete() 漏清 role_skill + loom_role_knowledge → 陈旧授权复活;fix `f1a20c8` |
| Q4 多用户 + 非 admin 安全 | ✅ | carol(无角色)0 能力 + admin 端点 302 拦截;dave(base)4/8 + 2 技能 |
| Q5 靶心人完整七步 | ✅✅ | 教科书级(七问序列精确 + 转弯放慢节奏 + 骨架表 + 停顿建议) |
| Q6 样式/视觉/响应式 | ✅ | 工具弹窗预勾态/技能徽章/admin 授权面板/390px 摘要行/token 一致性 |
| Q7 安全抽查 | ✅ | 401/404 防枚举/路径穿越 400/XSS 摘要行全转义 |
| Q8 回归抽查 | ✅ | 日志页 7 条 ANSWERED 完整(Q5 七步产物)/模态框/多页 console 零 error |

---

## DEFECT-Q3-1(Important):角色删除漏清 role_skill / loom_role_knowledge → 陈旧授权复活

**现象**(一次性角色 temp-x 复现,base 种子未受影响):
1. 建 temp-x + 授权技能 STAR-IJ → 删 temp-x → `role_skill` 残留 1 条 dangling(role/user_role/role_mcp 均正确清 0)
2. **复活实证**:重建同名 temp-x(**不授权任何技能**)→ 分配给 carol → carol 技能列表**凭空出现 STAR-IJ**(继承 dangling 授权)

**根因**(双重遗漏):
- `DefaultRoleService.delete()`(L83-87)显式清理清单只有 user_role/role_mcp/role_tool(B.2.1 fix 时代产物),**漏 role_skill(M4 后加)和 loom_role_knowledge(知识库市场后加)**
- V1.0 FK ON DELETE CASCADE 块(L582-595)同样只覆盖那三表 —— role_skill(L249)/loom_role_knowledge(L450)**无 FK**,DB 层无兜底
- 对照:market_skill 删除侧(`adminDelete` L437)**有**清 role_skill —— 指针卫生不变量("引用不得比被引用行活得久")在 market 侧执行了,role 侧漏了

**影响评估**:Important(非 Critical)—— 触发需"删角色 + 重建同 code"两步;后果是新角色静默继承旧技能/知识库授权(意外能力面,非 admin 提权)。base 种子 is_system=FALSE 可删,含 2 条 role_skill,正落在此路径上。

**修复方案**(对齐既有 B.2.1 双保险模式)—— **已修复,commit `f1a20c8`**(用户裁决"立即修"):
1. `delete()` 补 2 行:`DELETE FROM role_skill WHERE role_code=?` + `DELETE FROM loom_role_knowledge WHERE role_code=?`(注释记录 DEFECT-Q3-1 缘由)
2. V1.0 FK 块补 2 条:`fk_role_skill_role` + `fk_role_knowledge_role`(ON DELETE CASCADE)
3. 回归测试 ×2:`DefaultRoleServiceDeleteCascadeTest`(mock,锁 delete() 清理清单 6 语句)+ `RoleDeleteCascadeIT`(真 DB:授权→删→两表清 0→重建同码→sync→不复活)

**修复验证(三层)**:
- 单元 GREEN:9/9(新 1 + 既有 ErrorMapping 8)
- 回归门全绿:lib **185/0** · test 模块 **412/0**(411+1)· 清库 IT gate **128/0/3skip**(127+1,含 RoleDeleteCascadeIT)
- **活体复验**:修复后重跑复活场景 —— 建 temp-x 授权 STAR-IJ → 删 → 重建同名(不授权)→ 新用户 eve 分配 → eve 技能列表 **`[]`**(修复前 carol 同场景凭空获得 STAR-IJ)

**测试卫生**:temp-x dangling 行 + carol 泄漏技能已手动清理;修复验证的 temp-x/eve 残留经清库重启归零,最终环境恢复纯净种子态。

---

## 各维度详情

### Q1 开箱首跑 ✅
- 全新库登录 → capabilities **4/8 enabled**(4 base MCP True;cn-weather + compile/git/maven False)
- 工具弹窗:网页抓取/必应/记忆/顺序思维 **未 disabled + 已勾选**(default_enabled 预勾);其余置灰 —— 开箱即用,无需 admin 手动授权
- 技能库:STAR-IJ + 靶心人 以 **ROLE_GRANTED** 自动同步出现(DB:locked=true, default_loaded=true, market_skill_id 正确),+ 6 条 USER_CREATED 系统种子
- admin console:wb04307201 行带 **base 徽章** + 分配角色按钮;roles.html base 行"编辑/授权 + 删除"(is_system=FALSE 可删)
- admin 授权面板精确反映种子:已授权本地工具(0)/ **已授权 MCP(4)**(顺序 1.顺序思维 2.必应 3.记忆 4.抓取,带"默认启用"勾选)/ **已授权技能(2)**(带"默认加载")/ 知识库(空)

### Q2 ROLE_GRANTED 生命周期 ✅
- 详情面板:"**角色授权 已加载 · 已被角色授权锁定，不可编辑**",无编辑/删除按钮(UI 层锁定)
- API 负例:DELETE /api/skill/{name} → 404(该端点不存在,UI 无入口即不可达)
- 行为记录(pre-existing 语义,非缺陷):对已 ROLE_GRANTED 的 STAR-IJ 执行市场 pull → 原行**原地转 MARKET_PULLED**(locked=true, market_skill_id=1),同名不重复;CLAUDE.md 只锁 USER_CREATED 拒覆盖,ROLE_GRANTED→pull 转换是既有设计

### Q4 多用户 + 非 admin 安全 ✅
| 用户 | 角色 | capabilities | 技能 | admin 端点 |
|---|---|---|---|---|
| carol(USER) | 无 | **0/8** | **0** | ask-logs + stats.html 均 **302 → index**(adminPathPatterns) |
| dave(USER) | base | **4/8** | **2 ROLE_GRANTED** | 同样被拦 |
- base 授权精确按角色发放(非全局);role_skill→user_skill 自动同步对普通用户同样生效

### Q5 靶心人完整七步(补第三轮 R3.2)✅✅
- 七问 header 精确:第1步·目标 → 第2步·阻碍 → 第3步·努力 → 第4步·结果 → 第5步·意外 → 第6步·转弯 → 第7步·结局(87s,零自问自答)
- 汇总:连贯故事(素材全保留)+ **"爆了。"独立成句 —— "意外→转弯放慢节奏"技能要求被精确执行** + 七行故事骨架表(可直接做 PPT)+ 主动附演讲停顿建议 + 追问 3 分钟删减版/口播版
- 原词"转弯"正确使用("认知转弯");**两个种子技能现已双双全程验证**(STAR-IJ 六步 R3.1 + 靶心人七步 Q5)

### Q6 样式/视觉/响应式 ✅
- 摘要行:桌面 max-width 560/border-box/ellipsis/ok 色 rgb(16,185,129)=**--success-color 真实 token**;**390px mobile**:rect[117,341] 视口内、hOverflow=0、ellipsis 生效、点击展开 ▸→▾ 回看、tap 目标 38px
- 各徽章视觉:工具弹窗预勾/置灰态、技能"角色授权"vs"自建"徽章、市场 🏛️ 官方徽章、console base 角色徽章
- index/stats/console/market/roles 多页 **console 零 error**

### Q7 安全抽查 ✅
- answer 未登录 → **401**;admin/ask-logs 未登录 → **401**;登录 + 随机 UUID → **404 统一防枚举**;路径穿越 `..%2f..%2fetc` → **400**(容器层)
- XSS(fresh build 复验):question/header/background/label/自定义答案全 payload → `__xssFlags` 全 undefined、注入元素 **0**、payload 字面转义显示

### Q8 回归抽查 ✅
- **日志页 ask-logs**:Q5 七步的 **7 条全记录**,status 全 ANSWERED、答案完整提取、等待时长(0s/1s/38s 混合)、同会话 id 归组、按七步倒序 —— §2 端到端在 base 环境零回归
- 工具弹窗/文件模态框/知识空间/admin 三页全正常;Q3 后 DB 清回仅 base

---

## 环境注记

- 清库重启前必须先杀干净旧 app JVM:H2 `AUTO_SERVER=TRUE` 下 ghost 进程会以内存 DB 当 server,导致新实例 Flyway 误报 "up to date" 跳过 seed(本轮 base 种子首验时踩中,杀 ghost + 彻底 wipe 后正常)
- 第三轮已记录项不重复:E2 SSE 惰性断连(组 E follow-up)、DashScope 上游间歇抖动

## 证据存档

- 过程台账:`.superpowers/test-round4/progress.md`(scratch)
- 服务端日志:`/tmp/loom-8080-base3.log`(纯净种子态实例)
- DB 直查:qbase/qstar2/q3check/qres 系列(H2 权威证据,含 dangling 行 + 复活复现)
