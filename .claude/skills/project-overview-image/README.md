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

## 6 大板块布局

1. **HEADER**：项目名 + 副标题
2. **CORE 4**：4 张青色卡片（对话 / 知识库 / MCP / 技能）
3. **6 TOOLS**：6 张卡片（文件 / Git / Maven / 部署 / 时间 / 技能），**第 4 张"部署"橙色高亮**
4. **ADMIN**：5 个芯片（用户 / 角色 / MCP / 市场 / 会话）+ 2 个胶囊（管理员 / 普通用户）
5. **STACK**：7 个技术栈胶囊（Spring Boot / Spring AI / JDK 17 / JVector / JGit / Flyway / ChatMemory）
6. **FLOW + FOOTER**：4 框流程（核心 → 配置 → 启动 → 测试）+ "接口 默认 可替换" 横幅

## Prompt 设计陷阱（避免常见错误）

1. ❌ 坐标提示（`y=180~380`） → 模型当文字渲染
2. ❌ 章节标签词（`ZONE 1` / `GROUP A`）→ 全部画出来
3. ❌ 字母拆分（`J-V-e-c-t-o-r`）→ 模型画成 `J-V-e-c-` + `-to-r`
4. ❌ 尺码（`1328 by 1328 pixels`）→ 出现在画布上
5. ❌ 长复合词（`FILE MANAGEMENT`）→ 拼成 `FILMANAGEMENT`
6. ❌ Chat 字面拼写（`C-h-a-t`）→ 模型画成 `C-h-a-t`
7. ✅ 强调"唯一"：用 "the ONLY card with orange" 防止多个高亮
8. ✅ 短标签：1-2 字中文 / 1 单词英文

## wan2.7-image 已知限制

- 模型有随机性，但比 qwen-image **稳定得多**（5 候选基本都达标）
- 偶尔问题：
  - 模块流可能多出 1-2 个重复框（"starter"/"config"）
  - footer "Default" 偶尔拼成 "Defauit" / "Defaun"
  - 背景偶尔加白边装饰
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