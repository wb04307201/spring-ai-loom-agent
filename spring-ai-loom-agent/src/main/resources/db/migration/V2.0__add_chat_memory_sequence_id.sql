-- =============================================================
-- Issue #1 修复 (2026-09-30): 给 SPRING_AI_CHAT_MEMORY 加 sequence_id 列
-- =============================================================
-- 版本号说明:本文件原为 V1.5,2026-10-04 升为 V2.0。
--   · 库侧 V1.0__init.sql 用的是 Spring AI 1.x 的 chat memory schema
--     (conversation_id, content, type, timestamp),Spring AI 2.0 需要
--     sequence_id —— 这是**破坏性 schema 变更**,不是增量补丁,故跳大版本号。
--   · 同批还删除了 V1.6(其内容已并入应用方 seed V1.2__init_app_data.sql),
--     V1.5/V1.6 两个版本号一并作废。
--   · ⚠️ 本项目只跑全新库:已有实例必须 `rm -rf ~/.loom/datasource` 重跑,
--     或 flyway baseline 后手动迁移(见 CLAUDE.md「升级注意」)。
-- Spring AI 2.0 的 JdbcChatMemoryRepository 用 sequence_id BIGINT 排序
-- (替代 1.x 的 timestamp 排序 — 跨库一致,解决 MySQL/MariaDB TIMESTAMP 秒级精度
-- 导致同秒消息乱序的问题)。Loom 的 V1.0__init.sql 用了 1.x 旧 schema
-- (conversation_id, content, type, timestamp),没 sequence_id 列,
-- Spring AI 2.0 的查询 `... ORDER BY sequence_id` 直接报
-- BadSqlGrammarException → Issue #1 现象(本质根因是 ToolCallingAdvisor
-- 关闭内部 history,叠加 schema 不匹配才导致 LLM 收到残缺请求)。
--
-- Migration 步骤(参照 Spring AI 官方升级文档,H2 适配):
--   1. 加 sequence_id 列(允许 NULL 临时)
--   2. 按 conversation_id + timestamp 顺序回填 sequence_id(0-based)
--      用相关子查询 (H2 兼容;PostgreSQL 官方文档用 WITH ... UPDATE FROM)
--   3. 设 NOT NULL
--   4. 加 (conversation_id, sequence_id) 联合索引

ALTER TABLE SPRING_AI_CHAT_MEMORY ADD COLUMN sequence_id BIGINT;

-- 相关子查询: 同一 conversation_id 内,timestamp <= 当前行的行数减 1 = sequence_id
-- 同 timestamp 行共享 sequence_id(原 timestamp 排序也是不确定的,语义不变)
UPDATE SPRING_AI_CHAT_MEMORY
SET sequence_id = (
  SELECT COUNT(*) - 1
  FROM SPRING_AI_CHAT_MEMORY t2
  WHERE t2.conversation_id = SPRING_AI_CHAT_MEMORY.conversation_id
    AND t2.timestamp <= SPRING_AI_CHAT_MEMORY.timestamp
);

ALTER TABLE SPRING_AI_CHAT_MEMORY ALTER COLUMN sequence_id SET NOT NULL;

CREATE INDEX SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_SEQUENCE_ID_IDX
  ON SPRING_AI_CHAT_MEMORY(conversation_id, sequence_id);

-- =============================================================
-- 第 3 条官方 skill:交叉评审(2026-10-04)
-- =============================================================
-- 归类:本文件前半段是 schema 变更(chat memory 加 sequence_id),
-- 这一段是库层种子数据 —— 与 V1.0 尾部「2 条官方 market_skill + base 角色
-- role_skill 授权」同风格(INSERT ... SELECT ... WHERE NOT EXISTS 幂等)。
-- 之所以放这里而不是 V1.0:V1.0 是「完整 schema 一站式 init」,历史已冻结;
-- 新增种子走增量文件,与 V2.0 的破坏性变更同批落地。
--
-- 技能机制依据(读源码核实,非猜测):
--   · 子任务走 loomSubTaskExecutor 线程池,corePoolSize = maxPoolSize
--     = max-concurrent(默认 4),queueCapacity=50 ⇒ **并行度上限 4**,
--     超出部分排队。该池大小来自
--     LoomAgentConfiguration:1171-1182(Java,非 SQL,故只能写进 skill 文案)。
--   · DefaultSubTaskTool.startSubTask(prompt, systemContext, …) 内部
--     executor.submit(...) 后 future.get(timeout) ⇒ **单次调用是阻塞的**,
--     但 LLM 在同一轮发出多个 tool call 时 Spring AI 并行执行它们
--     ⇒ 「一轮内发 N 个 start_sub_task」= 真并行。
--   · systemContext 参数正是给每个子 agent 注入不同视角的入口。
--   · ⚠️ DefaultSubTaskExecutor 递归守卫排除 IAskUserTool
--     ⇒ **子任务不能向用户提问**,只能基于主对话已给出的信息推理。
--     本技能据此把「信息补齐」放在主对话阶段(主对话有 askUser 能力)。
-- =============================================================

INSERT INTO market_skill (name, description, content, author, status,
                          reviewed_at, reviewed_by,
                          is_official, created_by_kind, category)
SELECT '交叉评审',
       '用户说「交叉评审 / 多角度评审 / 全面评估 / 从几个维度看看 / 挑毛病」时触发：主对话先按需补齐信息，再动态规划评审维度，把超出并行上限的维度分批并行派发给子 agent，最后汇总成一份合并评审报告',
       '用户想「交叉评审 / 多角度评审 / 全面评估某个方案或问题」时触发本技能。

你的任务：把同一个复杂问题拆成若干个**互不重叠的评审维度**，每个维度派一个子 agent
独立评审，全部完成后汇总成一份合并评审报告。

═══════════════════════════════════════════
⛔ 执行纪律（必读，否则任务失败）：
═══════════════════════════════════════════
1. **不要描述你打算做什么**——「我将为您规划维度 / 接下来我会评审」这类自然语言自白
   对用户毫无意义，**只会吃光你的 completion_tokens，最终 finishReason=STOP + 空
   content 收尾**。用户唯一能看到的是：(a) 子任务执行结果，(b) 你最后那份合并评审报告。
   中间所有自白一律删除，**直接派发子任务**。
2. **本 skill 不写死工具名**——工具名一律从系统下发的 function declaration 里挑
   （系统已把当前可用的工具都注册好了），下面的描述只是告诉你要达成什么能力——
   具体调用哪个工具、参数怎么填，由你根据 function schema 自行决定。
   硬写工具名经常和实际注册名（带短横线 / 前缀）对不上，导致 tool_call 静默失败。
3. **子 agent 不能向用户提问**——子任务的工具集里**没有**提问能力。所以所有需要向用户
   澄清的事情，必须在**你这里（主对话）**先做完，见下面第一阶段。绝不要派一个
   「需要更多信息」的任务给子 agent，那会让它卡住或编造。
4. **不要把维度写成重叠的**——维度之间必须是**互补的切面**，不是同一件事的重复表述。
   「从技术角度看」和「从架构角度看」若指向同一批事实，就是无效切分，应当合并。

═══════════════════════════════════════════
第一阶段：补齐信息（主对话做，可能需要向用户提问）
═══════════════════════════════════════════
先判断信息是否够派发评审。子 agent 只能看到主对话里已经出现的内容，看不到用户脑中
的假设。典型的不够情况：

- 被评审的对象没说明清楚（哪个方案 / 哪份文档 / 哪个决策）
- 缺少评判所需的上下文（业务背景、约束条件、时间压力、已有结论）
- 维度之间会打架但用户没说以哪个为准（如「成本优先」还是「体验优先」）

信息不足时，**用你的提问能力（向用户发起带选项的提问卡片）**问 1-2 个最关键的问题，
每个给 3-4 个引导选项并允许自由输入。用户回答后再进入第二阶段。
信息已经足够时**直接进入第二阶段，不要为了流程多问一轮**。

判断标准：**如果你自己都无法列出「至少 3 个有区分度的维度」，说明信息还不够，先问。**

═══════════════════════════════════════════
第二阶段：动态规划维度（数量按问题复杂度定，不固定）
═══════════════════════════════════════════
根据问题的性质**自行判断**该切几个维度，不要套固定模板。以下是维度选择的参考轴
（**只是参考，不是清单**，按问题实际相关的轴挑选，凑数维度不如不设）：

- 技术可行性 / 实现风险
- 业务价值 / 收益量化
- 成本 / 资源投入
- 风险与合规 / 失败后果
- 可维护性 / 演进空间
- 用户体验 / 接受度
- 竞品对照 / 行业惯例
- 时机 / 窗口期

维度数量守则：
- 简单问题（2-3 个维度就够）→ 别硬凑 5 个
- 常规问题 → 3-5 个
- **并行度上限是 4**（子任务线程池大小），因此：
  · 维度 ≤ 4 个 → 在**同一轮**里同时发出全部 start_sub_task，真并行
  · 维度 > 4 个 → **必须分批**：第一批 4 个并行，等它们全部返回后再发第二批
- **超上限时不要在同轮发超过 4 个**——多出的会排队等待，看起来像并行实则串行，
  而你不会察觉。分批是你必须主动做的编排。

给每个子 agent 的任务描述要包含：
(a) 这个维度**只看这一面**，不要越界去评别的维度；
(b) 需要它重点回答的问题（1-3 个具体问题，别只说「评一下」）；
(c) **如果信息不足以判断，就明确写出「假设前提是 X」，不要编造事实**。

═══════════════════════════════════════════
第三阶段：汇总成一份报告（必做，子任务全返回后必须执行）
═══════════════════════════════════════════
所有维度的评审都返回后，**必须**汇总成一份报告给用户，结构如下：

1. **总体结论**：一句话说清整体判断（可行 / 有条件可行 / 需重大修改 / 不建议）。
2. **维度结论一览**：每维度一段，标注该维度的**风险等级**（高 / 中 / 低）与**一句话结论**。
3. **冲突与分歧**：哪些维度之间结论打架（如「技术可行」vs「成本过高」），**这是交叉评审
   最有价值的部分，不要抹平**。
4. **行动建议**：按「先做什么 / 需补什么前置条件 / 建议放弃什么」组织，可执行、不空泛。
5. **假设与局限**：列出各子 agent 标注过的假设前提，以及本次评审未覆盖的方面。

⚠️ 有子任务失败或超时时：明确说明「XX 维度未完成」，**不要**用其他维度的结论代替它，
也不要凭空编造该维度的评审意见。

═══════════════════════════════════════════
注意：
═══════════════════════════════════════════
- 维度之间**不要互相引用对方的结论**——并行派发意味着它们互相看不见，这是特性不是缺陷，
  交叉验证正是靠这种独立性产生的。若让子 agent 引用彼此，反而破坏了交叉评审的意义。
- 汇总时若发现某维度的结论与你的预判冲突，**如实呈现冲突**，不要为了结论好看而调和。
- 用户只想听结论时，把第 3、5 节压缩成一句话，不要强推全报告格式。',
       'system', 'APPROVED', CURRENT_TIMESTAMP, 'system',
       TRUE, 'ADMIN', '分析决策'
WHERE NOT EXISTS (SELECT 1 FROM market_skill WHERE author = 'system' AND name = '交叉评审');

-- 授权给默认基础角色(与 V1.0 尾部 2 条官方 skill 的授权写法一致:按名子查询 market_skill.id)
INSERT INTO role_skill (role_code, market_skill_id, sort_order, default_loaded)
SELECT 'base', ms.id, 2, TRUE FROM market_skill ms
WHERE ms.author = 'system' AND ms.name = '交叉评审'
  AND NOT EXISTS (SELECT 1 FROM role_skill rs WHERE rs.role_code = 'base' AND rs.market_skill_id = ms.id);
