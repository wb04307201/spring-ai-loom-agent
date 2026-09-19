# project-overview-image skill

> **确定性渲染**生成中英文 2 张 Spring AI LoomAgent 项目概览图，覆盖 `docs/project-overview-{en,zh}.png`。
> 2026-09-19 起取代 wan2.7-image 文生图管线。

## 管线

```
generate.py 的 DATA(结构) + LABELS(双语文案)   ← 布局单一真源
  → 自包含 HTML/CSS(深色海报 + 霓虹青卡片 + 橙色市场高亮)
  → 本地无头 Chromium(Playwright)截图 1280×1280 CSS px @2x → 2560×2560 PNG
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

# 校验(结构自洽 / 双语 parity / 章节编号 / 产物尺寸)
python .claude/skills/project-overview-image/scripts/check.py
```

## 改内容 = 改 generate.py,不要手改 PNG

| 想改什么 | 改哪里 |
|---|---|
| 增删核心支柱卡 | `PILLARS`(统计胶囊 `07 PILLARS` 自动跟随) |
| 增删工具卡/方法数徽章 | `TOOLS`(徽章数字 = `@Tool` 方法数;`UNIVERSAL_COUNT`/`RBAC_COUNT` 同步) |
| 平台芯片/橙色市场高亮 | `PLATFORM` / `MARKET_IDS`(橙色**恰好 2 个**,check.py 锁) |
| 技术芯片/构建行/页脚 | `TECH` / `BUILD` / `LABELS[*].footer` |
| 任一双语文案 | `LABELS["en"|"zh"]`(键集合双语对齐,check.py 锁) |
| 配色/字号/留白 | `build_html()` 内 CSS(`space-between` 控制大区呼吸空白) |

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
