# project-overview-image skill

> **确定性渲染**生成中英文 2 张 Spring AI LoomAgent 项目概览图，覆盖 `docs/project-overview-{en,zh}.png`。
> 2026-09-19 起取代 wan2.7-image 文生图管线。

## 管线

```
generate.py 的 DATA(结构) + LABELS(双语文案)   ← 布局单一真源
  → 自包含 HTML/CSS(**科技风**:近黑蓝墨底 + 浮起暗面板 + 霓虹青唯一强调色)
  → 本地无头 Chromium(Playwright)截图 1600×840 CSS px @2x → 3200×1680 PNG
  → check.py 静态校验
```

文生图管线退役原因:信息密集海报的随机缺陷(重复卡、漏卡、章节跳号)靠 prompt
"禁止"压不住 —— 旧 zh 图实测平台行重复「会话」、编号 03 跳成 04。确定性渲染下
标签/编号/计数来自代码列表与自动计算,**构造上不可能**出错;双语共用同一模板;
git 可 diff;无 API 费用与密钥依赖。

## 依赖

```bash
pip install playwright && playwright install chromium
```

不再需要 `DASHSCOPE_PERSON_TOKEN_API_KEY` / `DASHSCOPE_WORKSPACE_ID`。

## 使用

```bash
# 生成双图(覆盖 docs/project-overview-{en,zh}.png)
python .claude/skills/project-overview-image/scripts/generate.py

# 校验(结构自洽 / 分类覆盖 / 双语 parity / 版式文案 / 产物尺寸)
python .claude/skills/project-overview-image/scripts/check.py
```

## 改内容 = 改 generate.py,不要手改 PNG

| 想改什么 | 改哪里 |
|---|---|
| 增删核心支柱卡 | `PILLARS`(hero 右侧的 `{np} 核心能力` 自动跟随) |
| 增删工具卡/方法数徽章 | `TOOLS`(徽章数字 = `@Tool` 方法数)。**universal / RBAC 分类不用手改计数** —— 把工具 id 加进 `UNIVERSAL_IDS` 或 `RBAC_IDS` 即可,胶囊与小注的 `07 UNIVERSAL / 05 RBAC` 由 `universal_count()` / `rbac_count()` 从卡片集合推导;`check.py` 会校验「不重叠 + 不遗漏 + 无幽灵 id」,漏归类直接 exit 1。工具卡上的方法数徽章在浅色版**已移除**(28/16 这类数字挤在卡上会争夺注意力),需要时改回 `.badge` 样式 |
| 平台静默带(页脚一行) | `PLATFORM` — 纯文字 + 分隔点,浅色版不再有橙色高亮块 |
| hero 右侧统计块 | `MCP_SERVERS`(54 MCP 工具)/ `OFFICIAL_SKILLS` / `COPY[*].hr_*` |
| hero 徽章行(替代旧技术芯片行) | `COPY[*].badges`(JDK 25 / Boot 4 / AI 2 / H2·Flyway·JVector) |
| 视觉 token(配色) | `BG` `PANEL` `PANEL2` `EDGE` `INK` `INK2` `RULE` `ACCENT` `MONO`(等宽字体栈) |
| 版式文案(标题/副标题/分组标签) | `COPY` — 与 `LABELS`(卡片级文案)分工,两者都需 en/zh 键对齐 |
| 尺寸 | `SIZE_W` / `SIZE_H`(1600×840 CSS px @2x)|
| 任一双语文案 | 卡片级用 `LABELS["en"|"zh"]`;版式级(标题/副标题/分组标签/签名)用 `COPY["en"|"zh"]`。两者键集合都需双语对齐,check.py 锁 |
| 配色/字号/留白/版式 | `build()` 内 CSS(视觉 token 见上表);`build_html()` 已更名为 `build(lang)` |

## 视觉语言

- 深蓝底 `#232a3d` + 霓虹青 `#45e3cf` 卡片 + 白色主文字(与旧版海报一致)
- 橙色 `#f5a020` 高亮**仅**技能市场/知识市场两芯片("市场板块")
- 卡片 = 几何线图标 + 单一标签;工具卡左上角白底徽章 = 方法数
- 不出现版本号/日期/年份

## 文件

```
project-overview-image/
├── SKILL.md        # skill 流程与检查清单
├── scripts/
│   ├── generate.py # DATA/LABELS 真源 + HTML 模板 + Playwright 截图
│   └── check.py    # 静态校验(exit 0 = 通过)
└── README.md       # 本文件
```
