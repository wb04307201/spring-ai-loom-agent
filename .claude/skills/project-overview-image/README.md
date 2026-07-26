# project-overview-image skill

> 通过阿里云百炼 **wan2.7-image** 文生图，生成中英文 2 张 Spring AI LoomAgent 项目概览图，覆盖 `docs/project-overview-{en,zh}.png`。

## 当前模型

- **wan2.7-image**（同步端点）
- 端点：`https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`
- Body 格式：`{"model": "wan2.7-image", "input": {"messages": [{"role": "user", "content": [{"text": "..."}]}]}, "parameters": {"n": 1, "size": "1280*1280"}}`
- **需要 `WORKSPACE_ID`**：在控制台右上角"华北2（北京）"下拉查看

## 必需环境变量

```bash
export DASHSCOPE_API_KEY="sk-xxxxx"
export DASHSCOPE_WORKSPACE_ID="ws-xxxxxxxxxxxxx"   # 必需
export RUN_ID="1"                                    # 可选：用于多候选生成
```

如果未设置 `DASHSCOPE_WORKSPACE_ID`，会自动回退到旧的 `qwen-image`（legacy 端点 `text2image/image-synthesis`）。

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

## 7 大板块布局

1. **HEADER**：项目名 + 副标题
2. **CORE 4**：4 张青色卡片（对话 / 知识库 / MCP / 技能市场）
3. **8 TOOLS**：8 张卡片（文件 16 / Git 28 / Maven 6 / 部署 1 / 时间 2 / 技能 2 / 子任务 4 / 定时 4），**第 4 张"部署"橙色高亮 + 右上角 ★**
4. **RBAC**：灰色小标题 "RBAC + User Type" + 5 芯片（用户 / 角色 / MCP / 市场 / 会话）+ 2 胶囊（管理员 / 普通用户）
5. **STACK**：8 个技术栈胶囊（Spring Boot / Spring AI / JDK 17 / JVector / JGit / H2 / Flyway / ChatMemory）
6. **FLOW**：4 框流程（核心 → 配置 → 启动 → 测试）
7. **FOOTER**：中点分隔横幅（接口 · 默认 · 可替换）

## Prompt 关键约束（generate.py 单一真源）

- **Card icon rule**（每张卡片强制）：图标区**只能有几何图标**，禁止任何文字、字母、数字、叠字、伪字符（解决 MCP 卡变 "NCP"、Maven 卡变 "PMC" 这类 ghost text）
- 顶部 4 卡末位是 **Skill Market**（中英一致），把"技能"+"市场"合并到一张卡里，避免与下行工具组 Skill 重名
- 8 工具组每张卡在名字后附**工具方法数** `(16)` `(28)` `(6)` ...，与 README "Built-in Tools" 表对齐
- Deploy 卡 = 橙色 + 右上角 ★ + "推荐入口"
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