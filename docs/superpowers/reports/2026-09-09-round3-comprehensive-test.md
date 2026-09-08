# 第三轮全面测试报告(2026-09-09)

> 触发:用户"先对项目进行调整,然后再进行测试" —— 四项后续调整(spec `2026-09-08-askuser-followups-design.md`,commits `dee7749..6cda60e`)落地后的全量验证轮
> 环境:Windows + Chrome DevTools MCP,真实 DashScope qwen3.8-max,**MCP 12 servers 启用**(前两轮均关闭),全新库起跑(`~/.loom/datasource`),8080,最终构建(含 fix `ad7bc8f`)
> 方法:真实 LLM 端到端 + 合成 SSE 帧(确定性前端)+ curl API 探测 + H2 直查 + 服务端日志线程级核对

---

## 结论

**通过。** 本轮 **0 个需修代码缺陷**;四项调整的全部功能在真实环境验证通过,RAG 路径(#3 H2 向量存储)与 MCP 启用环境均为**生产首验**。1 项架构观察(E2,SSE 断连惰性检测,记 follow-up)+ 1 项外部抖动(DashScope EngineAbort 500,上游服务)如实记录。

| 维度 | 结果 | 说明 |
|------|------|------|
| R0 基线回归门 | ✅(继承) | lib 185/0 · test 411/0 · IT 127/0/3skip(T6 落地,本轮无代码改动不重跑) |
| R1 MCP 启用环境差异 | ✅ 4/4 | 本轮首次开 MCP |
| R2 RAG 首次活体 | ✅ 3/3 | **#3 H2JVectorStore 生产首验** |
| R3 种子技能深测 | ✅ | STAR-IJ 全程教科书级;靶心人序列开头正确 |
| R4 定时任务×挂起提问并发 | ✅ | schema 级排除线程级实证 |
| R5 边界 E1-E4 | ✅ 4/4 | 登出/刷新/断网/双用户 |
| R6 回归抽查 | ✅ | 持久化/模态框/admin 页/console 零错误 |
| R7 样式/响应式 | ✅ | 摘要行 390/1536 + 日志页区块 |

---

## R1 — MCP 启用环境差异(前两轮 MCP 关闭,本轮首开)

| # | 项 | 结果 |
|---|---|---|
| R1.1 | RBAC 双维度授权 | ✅ dev-role 授权 bing-search MCP + tool_compile 工具组 → admin capabilities 精确:两项 True、其余 6 项 False;工具弹窗:授权项可勾选(defaultEnabled 预勾),未授权项 disabled 置灰 |
| R1.2 | 混合流(MCP + askUser 同会话) | ✅ 后端日志 `Executing tool call: bing_search` ×5 —— role 授权的 MCP 工具确实进入 LLM tool callbacks;askUser 卡片正常弹出/作答/折叠。**注**:期间 DashScope 上游 `InternalError.EngineAbort 500`(外部服务抖动),前端优雅降级"聊天服务异常,请稍后重试",UI 无卡死 |
| R1.3 | ask-logs 隔离性 | ✅ loom_tool_call_log 实存 askUser=9/bing_search=5/searchKnowledge=5/listDirectory=1/searchFiles=1,但 `/admin/ask-logs` 返回恰好 9 行(`WHERE tool_name='askUser'` 过滤零混入);status 全轮 21 条:ANSWERED 18/TIMEOUT 1/CANCELLED 2/**UNKNOWN 0** —— fix `ad7bc8f` 在真实生产数据 100% 正确 |
| R1.4 | JSON UTF-8 完整性 | ✅ 响应 2644 bytes decode OK、9 条 question 零 lone-surrogate(终端乱码系 Git Bash GBK 渲染,数据干净) |

## R2 — RAG 路径首次活体(#3 H2 向量存储生产首验)

| # | 项 | 结果 |
|---|---|---|
| R2.1 | 上传→embed→落库 | ✅ 建 KB(PUT /knowledge)+ 上传 loomdoc.txt → `H2JVectorStore Adding 1 documents` → **loom_vector_store rows=1 dim=1024**(DashScope text-embedding-v4 真实调用) |
| R2.1b | 交互发现(非缺陷) | ✅ KB **未勾选启用**时 system prompt 无【知识库】段 → LLM 不知道 KB 存在,**用 askUser 反问用户提供 knowledgeId**(header"知识库来源"+4 选项+自定义)—— 启用制语义正确,且是 askUser×RAG 的自然组合实证 |
| R2.2 | 检索→引用 | ✅ 勾选启用后问"研发代号+架构师" → LLM 自主调 searchKnowledge ×2 → 答案含「紫隼(Purple Falcon)」+「林墨」+ 原文引用 + 来源说明 |
| R2.3 | **重启 hydrate 消灭 re-embed** | ✅ 不清库重启 → `hydrating 1 row(s)` + `hydrate done: loaded=1, dimSkipped=0, poisonSkipped=0`,**零 embedding API 调用**(日志 grep 证实);重启后检索"部署流水线构建工具" → maven/npm/npm-frontend/pip 全对 —— **#3 核心价值(向量持久化+启动免 re-embed)端到端首验通过** |

## R3 — 种子技能深测(真实 LLM)

**R3.1 STAR-IJ 完整六步:教科书级通过** ✅
- 提问序列:S 情境 → T 任务 → A 行动 → **A 追问**(仅行动步追问一轮,与技能文本"仅在行动/结果两步可追问"精确一致)→ R 结果 → I 价值 → J 成长,共 7 问,header chip 全带维度名
- 纪律:零自问自答、零自白死循环、每步真实等待作答;J 答完**立即**汇总("信息足够时立即进入汇总"生效)
- 汇总产出:六段结构化叙述 + ⚡30 秒电梯稿 + 用户真实数字全保留(零 P0/QPS×3/800ms→200ms)+ 主动附 2 条补强建议 + 场景化调整引导(超出最低要求)
- 7 张卡片全部正确折叠摘要,stream COMPLETE + doWriteMemory 正常

**R3.2 靶心人(前 2 步验证 + pull)** ✅ pull id=2 → user_skill 33;第 1 问 header"目标"(4 个高质量引导选项贴合咖啡店创业素材 + 其他)、第 2 问 header"阻碍"("阻碍越具体,听众越能共情")—— 七步序列正确开头;变体公告未见(LLM 直接走七步,合理:素材变体判断点在 4/5 步);作答折叠/stop 定格正常

**R3.3 纪律观察** ✅ 无空 content、无 finishReason 异常、无重复提问(memory 三铁律在真实 qwen3.8-max 上全部生效)

## R4 — 定时任务 × 挂起提问并发

✅ createSchedule 建 `loom-sched-{user}-{conv}-timecheck` fixed-rate 任务;**同一毫秒**首触发 → DefaultSubTaskExecutor 子任务(scheduleThreadPool-3 → loom-subtask-1 线程)4s 完成,写命名空间 ChatMemory(`{conv}--sub--{id}`)—— 执行窗口完整落在主对话 askUser 挂起窗口内,**真并发重叠**。
- **schema 级排除线程级实证**:grep 子任务线程上的 askUser 调用 = 空(D6 契约生效)
- 主对话挂起卡片不受干扰,正常作答折叠;测试后 cancel → 运行时+H2 双写删除(`loom_scheduled_task rows=0` 证实)

## R5 — 边界维度 E1-E4

| # | 场景 | 结果 |
|---|---|---|
| E1 | 挂起时**登出** | ✅ 跳 login.html、卡片全清;后端不即时 cancel(SSE 惰性断连,见 E2 根因),future 挂至超时;附注:登出仅清浏览器 cookie,服务端 token 不失效(pre-existing auth 设计,与 askUser 无关) |
| E2 | 挂起时**刷新(F5)** | ✅ 前端立即干净(0 卡片、UI 可用);后端 future 不即时释放 —— curl 探测旧 qid answer 仍 200(存活)。**根因(架构观察,非缺陷)**:Tomcat 异步 servlet 仅在写入时检测断连,askUser 阻塞期 SSE 无数据帧 → onError/onCompletion 不触发 → cancelAll 挂不上 → 挂至 300s 超时。低危(占 1 boundedElastic 线程、自我伤害面、最终释放)。**follow-up 候选**:挂起期 SSE 心跳帧(keep-alive),刷新后下次心跳即触发 cancelAll 秒级释放。stop 按钮路径不受此限(主动 POST /stop,已验秒级) |
| E3 | 挂起时**提交失败(500/断网)** | ✅ fetch hook 注入 500:卡片不折叠、按钮复位"提交答案"可用、badge 清空、摘要行保持隐藏(D8)、倒计时续跑;恢复后重试成功 → 折叠"✓ ... → 甲"。restorePending 生产路径完整 |
| E4 | **双用户**跨会话作答 | ✅ 创建 bob;bob 作答 alice 挂起 qid → **404**(跨用户 false 且不 complete);bob 随机 UUID → **404**(统一防枚举);alice 自己作答同 qid → **200**(越权尝试零副作用) |

## R6 — 回归抽查

✅ 持久化跨重启:ChatMemory 11 会话 / user_conversation 18 行 / 2 种子技能 / KB+向量 / dev-role+授权 全部保留;文件模态框、定时/子任务面板、admin console(用户列表含角色徽章)、market-skills(🏛️ APPROVED system 表达沟通)全正常;index/stats/console/market 四页 **console 零 error**。

## R7 — 样式/响应式(本轮新 UI)

- **摘要行**:桌面 1536px hOverflow=0、max-width 560/min-width 0/border-box、ellipsis 生效(scrollW 624>clientW 516);移动 390px(emulate)稳定复测 rect [117,341] 全在视口内、父链 wrap→bubble→chat-item→chat-content 逐层收缩 —— §1 新元素完整继承 B3 响应式纪律
- **日志页区块**:6 列表头、徽章真实 token(灰 --text-muted/绿 success/答案文本)、等待时长两分支(`等待 42s` / `等待 1m 4s`)、username 过滤(bob→0 行+空态"暂无提问记录";Enter 触发)、docHOverflow=0

---

## 发现汇总

| 级别 | 项 | 处置 |
|------|---|------|
| 缺陷(需修) | **无** | — |
| 架构观察 | E2/E1:SSE 断连惰性检测 → 刷新/登出不即时 cancelAll,挂起 future 占线程至超时(≤300s) | follow-up 候选:挂起期心跳帧;低危不阻断 |
| 外部抖动 | DashScope `InternalError.EngineAbort 500`(R1.2 期间) | 上游服务问题;前端降级路径顺带验证通过 |
| 环境注记 | Git Bash curl 中文 body GBK 编码坑(400 Invalid UTF-8)→ 改 --data-binary @file;终端中文乱码系 GBK 控制台渲染 | 非代码问题,memory 已有记录 |

## 三轮测试累计视角

- 第一轮(T7b 复验)抓 4 缺陷(-parameters/字面 null/配置绑定/按钮卡死)→ 全修
- 第二轮(全面测试)抓 2 缺陷(CSS token 错名/移动端裁切)→ 全修
- **第三轮(本轮)抓 1 生产缺陷**(result_text JSON 引号形态 → 日志页全显"未知",fix `ad7bc8f`,re-review all addressed)**+ 0 新缺陷** —— 四项调整质量收敛,且真实环境(RAG/MCP/并发/边界)全维度通过

## 证据存档

- 截图:`docs/superpowers/reports/assets/v1-summary-answered.png`(折叠摘要)、`v4-asklog-fixed.png`(日志页修复后)
- 服务端日志:`/tmp/loom-8080-r4.log`(首轮)、`/tmp/loom-8080-r5.log`(重启 hydrate 轮,含 bing_search/subtask/cancelAll 线程级证据)
- 测试台账:`.superpowers/test-round3/progress.md`(过程记录,scratch)
