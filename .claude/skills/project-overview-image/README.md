# project-overview-image skill

> 通过阿里云百炼 **wan2.7-image** 文生图，生成中英文 2 张 Spring AI LoomAgent 项目概览图，覆盖 `docs/project-overview-{en,zh}.png`。

## 当前模型

- **wan2.7-image**（同步端点）
- 端点：`https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`
- Body 格式：`{"model": "wan2.7-image", "input": {"messages": [{"role": "user", "content": [{"text": "..."}]}]}, "parameters": {"n": 1, "size": "1280*1280"}}`
- **需要 `WORKSPACE_ID`**：在控制台右上角"华北2（北京）"下拉查看

## 必需环境变量

```bash
export DASHSCOPE_PERSON_TOKEN_API_KEY="sk-xxxxx"     # 必需：personal token
export DASHSCOPE_WORKSPACE_ID="ws-xxxxxxxxxxxxx"   # 必需
export RUN_ID="1"                                    # 可选：用于多候选生成
```

如果未设置 `DASHSCOPE_WORKSPACE_ID`，会自动回退到旧的 `qwen-image`（legacy 端点 `text2image/image-synthesis`）。

> **密钥说明**（2026-09-12 实测）：同账号的 personal token（`DASHSCOPE_PERSON_TOKEN_API_KEY`，`sk-` 前缀）对 wan2.7-image workspace 端点有效，`generate.py --api-key` 默认即从该环境变量取值；也可用 `--api-key "sk-xxxx"` 显式传入。

## 使用

```bash
# 单次生成（覆盖 docs/project-overview-{en,zh}.png）
python .claude/skills/project-overview-image/scripts/generate.py

# 多候选生成（写到 -r{N}.png 不覆盖）
for i in 1 2 3 4 5; do
  RUN_ID=$i python .claude/skills/project-overview-image/scripts/generate.py --only en
  RUN_ID=$i python .claude/skills/project-overview-image/scripts/generate.py --only zh
done

# 视觉对比 docs/project-overview-{en,zh}-r*.png，选最佳覆盖主文件
cp docs/project-overview-en-r3.png docs/project-overview-en.png
cp docs/project-overview-zh-r2.png docs/project-overview-zh.png
rm -f docs/project-overview-{en,zh}-r*.png
```

## wan2.7-image 关键参数

| 参数 | 值 |
|---|---|
| `size` | `1280*1280`（默认） / `1024*1024` / `1K` 等 |
| `n` | `1`（单张） |
| `prompt_extend` | 可选：true 让模型自动扩写 prompt |
| `watermark` | 可选：false 关闭水印 |

## 6 大区块布局（对齐 generate.py EN_LAYOUT / ZH_LAYOUT）

1. **ZONE 1 · HERO**：超大标题 `Spring AI LoomAgent` + 青色副标题 + 细轮廓胶囊 `06 PILLARS  -  11 TOOLS  -  07 UNIVERSAL  -  04 RBAC`
2. **ZONE 2 · 01 PILLARS（核心）**：6 张等大青色卡片（Chat / Knowledge / Files / MCP / Skill / RBAC）
3. **ZONE 3 · 02 TOOLS（工具）**：区块标签右侧小字说明 `7 universal  4 RBAC`（中文图 `7 通用  4 RBAC`）；**EXACTLY 11 张等大卡片**（Files 16 / Knowledge 1 / Git 28 / Maven 6 / Deploy 1 / Time 2 / Skill 2 / Sub-task 4 / Schedule 4 / Ask-user 1 / Html render 1），方法数是图标区内的小数字徽章；**所有卡片完全等同 — 无高亮、无色差、无五角星**（universal / RBAC 的区分只靠区块标签旁的说明文字表达）
4. **ZONE 4 · 03 PLATFORM（平台）**：第 A 行 6 个芯片（Users / Roles / Sessions / **Skill Market** / **KB Market** / Admin Console）—— 全海报**只有**技能市场 + 知识市场两个橙色填充芯片，并排相邻形成"市场板块"；第 B 行 **EXACTLY 8 个**细青色技术栈胶囊（Spring Boot / Spring AI / JDK 17 / JVector / JGit / H2 / Flyway / ChatMemory）
5. **ZONE 5 · 04 BUILD（构建）**：4 框箭头流程（core → config → starter → test）
6. **FOOTER**：贯穿海报的细青色水平线，线上居中白字 `Interface  ·  Default  ·  Replaceable`

## Prompt 关键约束（generate.py 单一真源）

- **Card icon rule**（每张卡片强制）：图标区**只能有几何图标**，禁止任何文字、字母、数字、叠字、伪字符（解决 MCP 卡变 "NCP"、Maven 卡变 "PMC" 这类 ghost text）
- 全海报**仅有两个橙色元素** = ZONE 4 第 A 行的 Skill Market + KB Market 芯片（并排"市场板块"）；prompt 用 "the ONLY orange-filled elements on the whole poster" 强约束，其他任何元素不得用橙色
- 11 张工具卡每张在**图标区顶端**附工具方法数小徽章 `(16)` `(1)` `(28)` `(6)` `(1)` `(2)` `(2)` `(4)` `(4)` `(1)` `(1)`，与 README "Built-in Tools" 表对齐
- 工具卡 EXACTLY-11 硬约束：prompt 明写"最常见渲染错误是漏掉一张卡（经常是 Time）；宁可缩窄也不能省略或合并"
- 栈行 **8 个胶囊**必须严格一行展示；prompt 写了"如果拥挤缩小 padding 而不是省略"
- footer 用中点 `·` 分隔（中英一致）
- 专有名词字符级约束：JVector = `J` + `V` + `ector`；JDK 17 = `JDK` + 数字 `17`

## Prompt 设计陷阱（避免常见错误）

1. ❌ 坐标提示（`y=180~380`） → 模型当文字渲染
2. ❌ 章节标签词（`ZONE 1` / `GROUP A`）→ 全部画出来
3. ❌ 字母拆分（`J-V-e-c-t-o-r`）→ 模型画成 `J-V-e-c-` + `-to-r`
4. ❌ 尺码（`1328 by 1328 pixels`）→ 出现在画布上
5. ❌ 长复合词（`FILE MANAGEMENT`）→ 拼成 `FILMANAGEMENT`
6. ❌ Chat 字面拼写（`C-h-a-t`）→ 模型画成 `C-h-a-t`
7. ❌ 卡内多次写同一标签（"MCP" 出现 2 次）→ 让 label 只在底部出现一次
8. ❌ 图标区出现文字（"MCP" 卡上方出现 "NCP" 伪影）→ 用 "card icon rule" 强制
9. ✅ 强调"唯一"：用 "the ONLY card with orange" 防止多个高亮
10. ✅ 短标签：1-2 字中文 / 1 单词英文
11. ✅ 行末必须一行装下：prompt 写 "must fit on one line without overflow"

## wan2.7-image 已知限制

- 模型有随机性，但比 qwen-image **稳定得多**（5 候选基本都达标）
- 偶尔问题：
  - 模块流可能多出 1-2 个重复框（"starter"/"config"）
  - footer "Default" 偶尔拼成 "Defauit" / "Defaun"
  - 背景偶尔加白边装饰
  - 栈行 8 胶囊可能溢出（如果整套拥挤，prompt 优先缩 padding 而不是省略项）
  - 图标区仍可能偶尔注入 1 个伪字符（MCP→NCP、Maven→PMC） — 是核心修复目标
- 强烈建议 **5+5 候选选最佳**（以前用 qwen-image 需要 50+ 候选）

## 文件结构

```
project-overview-image/
├── SKILL.md              # skill 描述 + 流程
├── scripts/
│   ├── generate.py       # 调阿里云百炼 wan2.7-image 同步生成
│   └── check.py          # 文本 + 跨图一致性检查
├── examples/              # 留空
└── README.md             # 本文件
```

## 实施日志

- 2026-07-12 (v1, qwen-image)：建立 skill，async API + 重试 + 检查
- 2026-07-12 (v2, qwen-image)：14+50 次候选，中英文内容布局差异大
- 2026-07-12 (v3, wan2.7-image) ✅：5+5 候选，中英文 **布局完全对齐**，质量大幅提升
  - 4 CORE / 6 TOOLS（Deploy 4 位橙色）/ 5 admin chips / 7 stack pills / 4 框流程 / footer 全部正确
  - JVector 拼写稳定
  - 错误模式：footer "Default" 偶尔拼错（3/5 候选）、模块流偶尔多框（3/5 候选）
- 2026-09-12 (v4, wan2.7-image) ✅：布局源对齐 M6 可见性模型 —— hero 胶囊 `08 ON - 03 OPT-IN` → `07 UNIVERSAL - 04 RBAC`、工具区说明 `8 on 3 opt-in` / `8 启用 3 手动` → `7 universal 4 RBAC` / `7 通用 4 RBAC`；SKILL.md / check.py / 本 README 的"8 工具组 / sidebar 5 区块 / qwen-image"陈旧描述同步；PNG 重生成（补 2026-08-11 以来滞后的 Ask-user + Html render 两张卡），3+3 + 2+2 候选选最佳，密钥用 personal token 回退（见上文）