---
name: project-overview-image
description: 基于项目代码 + README + CLAUDE.md 生成中英文 2 张项目概览图。确定性渲染管线(2026-09-19 起取代 wan2.7-image 文生图):generate.py 的 DATA/LABELS 是布局+文案单一真源,渲染自包含 HTML/CSS 后经本地无头 Chromium(Playwright)截图为 docs/project-overview-{en,zh}.png。文字/编号/计数由 DOM 构造,不可能出现文生图的重复卡/漏卡/跳号缺陷。
---

# project-overview-image

生成/刷新中英文 2 张项目概览图(PNG),覆盖 `docs/project-overview-en.png` 和 `docs/project-overview-zh.png`。

## 管线(确定性,非文生图)

```
DATA/LABELS(generate.py 单一真源)
  → 自包含 HTML/CSS(深色海报 + 霓虹青卡片 + 橙色市场高亮)
  → 本地无头 Chromium(Playwright)截图 1280×1280 @2x = 2560×2560 PNG
  → check.py 静态校验(结构自洽 / 双语 parity / 章节编号 / 产物尺寸)
```

**为什么不用文生图**:信息密集海报(7 核心卡 + 11 工具卡带徽章 + 8 技术芯片 + 双语)
在文生图下随机缺陷压不住 —— 旧 zh 图实测章节跳号(03→04 重复)、平台行重复「会话」,
布局规格里明确写"禁止"仍复发。本管线这些缺陷**构造上不可能**:标签来自 DATA 列表,
统计胶囊(`07 PILLARS - 11 TOOLS - ...`)由 `len(PILLARS)/len(TOOLS)/...` 自动计算,
双语共用同一模板与 id 集合。

## 流程

1. **对齐项目现状**(改 `generate.py` 的 DATA/LABELS,不要手改 PNG):
   - 核心区卡片 = 产品支柱(现 7:对话/知识库/文件/MCP/技能/画板/权限)
   - 工具区卡片 = 11 个 `I*Tool`(徽章 = `@Tool` 方法数;顺序与可见性见 README 内置工具表)
   - 平台区芯片 = 用户/角色/会话/技能市场(橙)/知识市场(橙)/管理控制台;橙色高亮**恰好 2 个**
   - 技术芯片 = 底层栈;构建行 = 模块链(核心 → 自动配置 → Starter → 测试应用)
   - 约束:不出现版本号/日期/年份(check.py 不查,但海报职责是架构不是发布元数据)
2. `python scripts/generate.py` — 生成双图(需本地 Chromium)
3. `python scripts/check.py` — 校验,exit 0 才通过
4. 视觉复核(Read 两张 PNG):布局/留白/双语一致

## 依赖

- Python 3.x + `playwright`(`pip install playwright && playwright install chromium`)
- **不再需要** `DASHSCOPE_PERSON_TOKEN_API_KEY` / `DASHSCOPE_WORKSPACE_ID`(文生图管线已退役)

## 使用方式

skill 触发(任意一种):

- "更新项目概览图"
- "刷新 README 顶部的 overview 图"
- "生成 docs/project-overview-{en,zh}.png"

## 文件结构

```
project-overview-image/
├── SKILL.md                 # 本文件
├── scripts/
│   ├── generate.py          # DATA/LABELS 真源 + HTML 模板 + Playwright 截图
│   └── check.py             # 结构自洽 / 双语 parity / 编号 / 产物尺寸 静态校验
└── README.md                # 用户文档
```

## 检查清单

- [ ] 中英双图都已生成且 2560×2560
- [ ] check.py 全过(行内无重复标签 / 编号 01-04 连续 / 统计与卡片数一致 / 橙色恰好 2)
- [ ] 核心/工具/平台/构建四区内容与 README、CLAUDE.md 现状一致
- [ ] 无版本号/日期/年份
- [ ] 视觉复核:留白均匀、双语风格一致
