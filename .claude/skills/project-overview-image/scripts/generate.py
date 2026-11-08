#!/usr/bin/env python3
"""项目概览图确定性渲染器(2026-09-19 取代 wan2.7-image 文生图管线)。

布局单一真源 = 本文件的 DATA(结构)+ LABELS(双语文案)。渲染为自包含
HTML/CSS(深色海报 + 霓虹青卡片 + 橙色市场高亮,与旧版视觉语言一致),
经本地无头 Chromium(Playwright)截图为 1280x1280 @2x PNG。

为什么不再用文生图:信息密集海报(11 工具卡+徽章+8 技术芯片+双语)在
文生图下随机缺陷压不住(旧 zh 图实测:章节跳号 03→04、平台行重复「会话」,
规格里明确禁止仍复发);本管线文字/编号/计数由 DOM 构造,**不可能**重复或漏卡,
统计胶囊数字由 DATA 自动计算,双语同模板渲染,git 可 diff,无 API 依赖。

依赖:pip install playwright && playwright install chromium
用法:python scripts/generate.py   → 覆盖 docs/project-overview-{en,zh}.png
"""
from __future__ import annotations

import pathlib
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parents[4]
DOCS = REPO_ROOT / "docs"

# 2026-10-04 由 1280x1280 方图改为 1600x840 横版(浅色编辑风)。
# 为什么改尺寸:方图在 README 里渲染成巨型方块,把下方内容全推走;横版能用上
# 横向空间,也符合"首屏 hero"的阅读习惯。840 而非 900 是实测结果 —— 内容实占
# ~800px,900 会留 100px 死白。
SIZE_W, SIZE_H = 1600, 840

# ---------------------------------------------------------------- 图标(24 viewBox,stroke 继承)
ICONS = {
    "bubble": '<path d="M4 5h16v10H9l-5 4z"/>',
    "ribbon": '<circle cx="12" cy="9" r="5"/><path d="M9.5 13.5 7.5 21l4.5-2.7L16.5 21l-2-7.5"/>',
    "doc": '<path d="M7 3h7l4 4v14H7z"/><path d="M14 3v4h4"/>',
    "plug": '<rect x="3.5" y="8" width="10" height="10" rx="2"/><circle cx="16.5" cy="12.5" r="4"/>',
    "spark": '<path d="M12 3l2.4 6.6L21 12l-6.6 2.4L12 21l-2.4-6.6L3 12l6.6-2.4z"/>',
    "palette": '<path d="M12 3a9 9 0 1 0 0 18c1.5 0 2.1-1 2.1-2s-.9-1.4-.9-2.4c0-1 .8-1.7 1.9-1.7H17a4 4 0 0 0 4-4c0-4.4-4-7.9-9-7.9z"/>'
               '<circle cx="8.6" cy="9.4" r="1.05"/><circle cx="12.4" cy="7.4" r="1.05"/><circle cx="16" cy="9.6" r="1.05"/>',
    "shield": '<path d="M12 3l8 3v6c0 5-3.4 8-8 9-4.6-1-8-4-8-9V6z"/>',
    "branch": '<circle cx="7" cy="6" r="2.4"/><circle cx="7" cy="18" r="2.4"/><circle cx="17" cy="9" r="2.4"/>'
              '<path d="M7 8.4v7.2M17 11.4c-1.6 3-6.4 2.2-8.6 4.4"/>',
    "grid": '<path d="M4 9l8-5 8 5-8 5z"/><path d="M4 14l8 5 8-5"/>',
    "deploy": '<path d="M12 3v9M8.5 6.5 12 3l3.5 3.5"/><path d="M4 13v7h16v-7"/>',
    "clock": '<circle cx="12" cy="12" r="8"/><path d="M12 8v4.2l3 2"/>',
    "cal": '<rect x="4" y="6" width="16" height="14" rx="2"/><path d="M4 10.5h16M9 4v4M15 4v4"/>',
    "ask": '<path d="M4 5h16v10H9l-5 4z"/><circle cx="12" cy="10" r="1.7"/>',
    "layers": '<rect x="8.5" y="3" width="11" height="12" rx="1.5"/><path d="M5 8v11.5h10.5"/>',
    "monitor": '<rect x="4" y="5" width="16" height="11" rx="2"/><path d="M9 20h6M12 16v4"/>',
    "globe": '<circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17"/>'
             '<path d="M12 3.5c2.2 2.3 3.4 5.3 3.4 8.5S14.2 18.2 12 20.5'
             'C9.8 18.2 8.6 15.2 8.6 12S9.8 5.8 12 3.5z"/>',
}

# ---------------------------------------------------------------- 结构(语言无关)
PILLARS = [  # 核心区卡片:(label_id, icon)
    ("chat", "bubble"), ("knowledge", "ribbon"), ("files", "doc"),
    ("mcp", "plug"), ("skill", "spark"), ("canvas", "palette"), ("rbac", "shield"),
]
TOOLS = [  # 工具区卡片:(label_id, icon, 方法数徽章)
    ("tool_file", "doc", 16), ("tool_knowledge", "ribbon", 1), ("tool_git", "branch", 28),
    ("tool_maven", "grid", 6), ("tool_deploy", "deploy", 1), ("tool_time", "clock", 2),
    ("tool_skill", "spark", 2), ("tool_subtask", "layers", 5), ("tool_schedule", "cal", 4),
    ("tool_askuser", "ask", 1), ("tool_render", "monitor", 1), ("tool_http", "globe", 5),
]
# 分类**由卡片 id 集合推导**,不再手写计数 —— 过去 UNIVERSAL_COUNT/RBAC_COUNT 是
# 手写常量,而 stats_line() 只从它们自身读,于是"加了卡片忘了改常量"这类漂移
# 构造上就查不出来(7+4==11 恰好掩盖了 HTTP 卡缺失)。现在改��集合后,
# 加工具只需动 TOOLS,胶囊数字自动跟随。
UNIVERSAL_IDS = frozenset({
    "tool_file", "tool_knowledge", "tool_time", "tool_skill",
    "tool_subtask", "tool_schedule", "tool_askuser",
})
RBAC_IDS = frozenset({"tool_git", "tool_maven", "tool_deploy", "tool_render", "tool_http"})
PLATFORM = ["user", "role", "session", "market_skill", "market_kb", "console"]
MARKET_IDS = {"market_skill", "market_kb"}  # 全海报唯一两个橙色芯片
TECH = ["Spring Boot", "Spring AI", "JDK 25", "JVector", "JGit", "H2", "Flyway", "ChatMemory"]
BUILD = ["build_core", "build_config", "build_starter", "build_test"]

# ---------------------------------------------------------------- id → 文案(能力卡/平台片)
# 与下面的 COPY 分工:COPY 是**版式级**文案(标题/副标题/分组标签/签名),
# LABELS 是**卡片级**文案(能力名/工具名/平台名),两者都需 en/zh 对齐。
LABELS = {
    "en": {
        "subtitle": "Spring Boot AI Agent Out-of-the-Box Solution",
        "sec_pillars": "01 PILLARS", "sec_tools": "02 TOOLS",
        "sec_platform": "03 PLATFORM", "sec_build": "04 BUILD",
        "tools_note": "-",   # 占位:实际文案由 tools_note() 按卡片集合生成
        "chat": "Chat", "knowledge": "Knowledge", "files": "Files", "mcp": "MCP",
        "skill": "Skill", "canvas": "Canvas", "rbac": "RBAC",
        "tool_file": "Files", "tool_knowledge": "Knowledge", "tool_git": "Git",
        "tool_maven": "Maven", "tool_deploy": "Deploy", "tool_time": "Time",
        "tool_skill": "Skill", "tool_subtask": "Sub-task", "tool_schedule": "Schedule",
        "tool_askuser": "Ask-user", "tool_render": "Html render",
        "tool_http": "HTTP",
        "user": "Users", "role": "Roles", "session": "Sessions",
        "market_skill": "Skill Market", "market_kb": "KB Market", "console": "Admin Console",
        "build_core": "core", "build_config": "autoconfigure",
        "build_starter": "starter", "build_test": "test app",
        "footer": "Interface  ·  Default  ·  Replaceable",
    },
    "zh": {
        "subtitle": "Spring Boot AI Agent 开箱即用方案",
        "sec_pillars": "01 核心", "sec_tools": "02 工具",
        "sec_platform": "03 平台", "sec_build": "04 构建",
        "tools_note": "-",   # 同上
        "chat": "对话", "knowledge": "知识库", "files": "文件", "mcp": "MCP",
        "skill": "技能", "canvas": "画板", "rbac": "权限",
        "tool_file": "文件", "tool_knowledge": "知识库", "tool_git": "Git",
        "tool_maven": "Maven", "tool_deploy": "部署", "tool_time": "时间",
        "tool_skill": "技能", "tool_subtask": "子任务", "tool_schedule": "定时",
        "tool_askuser": "问答", "tool_render": "HTML渲染",
        "tool_http": "外网HTTP",
        "user": "用户", "role": "角色", "session": "会话",
        "market_skill": "技能市场", "market_kb": "知识市场", "console": "管理控制台",
        "build_core": "核心", "build_config": "自动配置",
        "build_starter": "Starter", "build_test": "测试应用",
        "footer": "接口  ·  默认  ·  可替换",
    },
}


# ---------------------------------------------------------------- 视觉 token(浅色编辑风)
# 2026-10-04 从"深色 + 霓虹青"改为浅色编辑风。三个理由:
#   1. 青绿(#45e3cf)+深蓝底是当下 AI 项目最泛滥的组合,属于 generic tech;
#   2. 本项目是给开发者用的基础设施,浅色更诚实地传达"严肃的文档"而非"炫技 Demo";
#   3. 浅色下"颜色承载信息"才成立 —— 琥珀**只**用于 RBAC 语义与关键数字,
#      而旧版橙色只用在那 2 个市场芯片上,90% 面积同色 ⇒ 颜色等于没说信息。
# 科技风(2026-10-04,继浅色编辑风之后)。与前两版的差异:
#   · 深色版(2026-09-19)是"青绿铺满 90%" —— 所有卡片同底同权重,满而无序;
#   · 浅色版(同日)层级清晰但**没有暗色锚点**,7 张核心卡是白底浅描边,
#     在暖白底上近乎隐没 —— 元素都在,但视线无处停留,冲击感不足;
#   · 本版用「近黑底 + 少量霓虹 + 明确明暗层级」:核心卡做成比底色亮一档的
#     浮起面板(亮边 + 投影 + 图标微光晕),霓虹青只在 4 处出现(核心卡图标 /
#     54 / RBAC 片 / MCP 数字)。**冲击感来自对比,不来自颜色数量。**
BG      = "#0E1117"   # 近黑蓝墨(终端/编辑器底,非旧版的灰蓝 #232a3d)
PANEL   = "#161B24"   # 浮起面板
PANEL2  = "#1C222C"   # 面板渐变上端
EDGE    = "#2A313D"   # 亮边(卡片描边)
INK     = "#F2F5F8"   # 主文字(近白)
INK2    = "#8B96A5"   # 次级文字(冷灰)
RULE    = "#232A35"   # 细线
ACCENT  = "#5EEAD4"   # 霓虹青(唯一强调色)
MONO    = "ui-monospace,\"Cascadia Mono\",Consolas,monospace"

# 独立 MCP server 的工具数 —— **从源码扫描得出**,不手写。
# 为什么必须扫:54 是全图最大的数字(hero 右侧霓虹青),一旦加/删 MCP server 工具
# 而忘了改这里,漂移的影响比其他任何数字都大。扫描口径:模块 src/main/java 下
# 所有 Loom*Service.java 里 `@McpTool(name = ...)` 的出现次数。
# 与 TOOLS 分类(UNIVERSAL_IDS / RBAC_IDS)不同源:那些统计**主库**工具组,
# 这里统计**独立 MCP server** 的工具,两者相加才是全部工具面。
MCP_MODULES = ["loom-file-mcp", "loom-git-mcp", "loom-maven-mcp",
               "loom-compile-mcp", "loom-http-mcp"]


def _count_mcp_tools(module: str) -> int:
    """扫描一个 MCP server 模块的 @McpTool 数量。找不到目录时返回 0 而非抛错 ——
    图不该因为某模块改名/移动而渲染失败,但 check.py 会校验总数不为 0。"""
    import re as _re
    root = REPO_ROOT / module / "src" / "main" / "java"
    if not root.is_dir():
        return 0
    n = 0
    for f in root.rglob("Loom*Service.java"):
        n += len(_re.findall(r"@McpTool\s*\(\s*name\s*=",
                             f.read_text(encoding="utf-8")))
    return n


def mcp_tool_counts() -> list[tuple[str, int]]:
    """[(短名, 工具数), ...] —— 短名去掉 loom- 前缀与 -mcp 后缀。"""
    out = []
    for m in MCP_MODULES:
        short = m.removeprefix("loom-").removesuffix("-mcp")
        out.append((short, _count_mcp_tools(m)))
    return out

# 库层官方技能数(market_skill, author=system):V1.0 尾部 2 条 + V2.0 追加 1 条。
# 改动时同步核对 V1.0__init.sql / V2.0__add_chat_memory_sequence_id.sql 的种子 INSERT。
OFFICIAL_SKILLS = 3


COPY = {
    "zh": {
        "title": "LoomAgent 灵梭",
        "claim": "Spring Boot 4 + Spring AI 2 的 <b>对话 · 知识 · 工具</b> 一体化底座",
        "badges": ["JDK 25", "Spring Boot 4", "Spring AI 2", "H2 · Flyway · JVector"],
        "hr_cap": "MCP 工具<br /><span>{ns} 个独立 server</span>",
        "hr_r1": "<b>{nt}</b> 组工具 · <b>{np}</b> 核心能力",
        "hr_r2": "<b>{nsk}</b> 条官方技能 · 有审批流与市场",
        "hr_r3": "核心 / 自动配置 / Starter 三段式 · 可执行 jar",
        "sec1": "核心能力", "sec2": "工具", "sec3": "独立 MCP server",
        "sec2_note": "{nt} 组 · 通用 {nu} / RBAC {nr}",
        "g_univ": "通用 —— 所有登录用户默认可用",
        "g_rbac": "RBAC —— 需管理员授权后方可见",
        "mcp_note": "每个 server 可独立进程运行,与主库共享同一套 core 操作层",
        "sig": "接口 · 默认实现 · 全部可替换",
    },
    "en": {
        "title": "LoomAgent",
        "claim": "An integrated <b>chat · knowledge · tools</b> foundation on Spring Boot 4 + Spring AI 2",
        "badges": ["JDK 25", "Spring Boot 4", "Spring AI 2", "H2 · Flyway · JVector"],
        "hr_cap": "MCP TOOLS<br /><span>{ns} standalone servers</span>",
        "hr_r1": "<b>{nt}</b> tool groups · <b>{np}</b> core capabilities",
        "hr_r2": "<b>{nsk}</b> official skills · approval workflow",
        "hr_r3": "core / autoconfigure / starter · runnable jars",
        "sec1": "Core capabilities", "sec2": "Tools", "sec3": "Standalone MCP servers",
        "sec2_note": "{nt} groups · universal {nu} / RBAC {nr}",
        "g_univ": "Universal — visible to every signed-in user",
        "g_rbac": "RBAC — requires an admin grant",
        "mcp_note": "each server runs as its own process, sharing the same core operation layer",
        "sig": "Interface · Default · All replaceable",
    },
}


def universal_count() -> int:
    """落在 UNIVERSAL_IDS 里的卡片数 —— 与卡片集合同源,不会漂移。"""
    return sum(1 for lid, _, _ in TOOLS if lid in UNIVERSAL_IDS)


def rbac_count() -> int:
    """落在 RBAC_IDS 里的卡片数 —— 与卡片集合同源,不会漂移。"""
    return sum(1 for lid, _, _ in TOOLS if lid in RBAC_IDS)


def icon(name: str, size: int = 24, color: str = INK) -> str:
    """图标 —— stroke 继承调用方传入的 color(浅色风里图标不再是纯白)。"""
    return (f'<svg viewBox="0 0 24 24" width="{size}" height="{size}" fill="none" '
            f'stroke="{color}" stroke-width="1.7" stroke-linecap="round" '
            f'strokejoin="round">{ICONS[name]}</svg>')


def build(lang: str = "zh") -> str:
    servers = mcp_tool_counts()
    ns = len(servers)
    # 只注入 ns/nsk;其余占位(nt/np/nu/nr)在各自使用处 format ——
    # 若在此处统一 format,hr_r1/sec2_note 的 {nt} 会因缺参抛 KeyError。
    c = {k: v.format(ns=ns, nsk=OFFICIAL_SKILLS) if ("{ns}" in v or "{nsk}" in v) else v
         for k, v in COPY[lang].items()}
    L = LABELS[lang]
    UNIV = [(lid, ic, n) for lid, ic, n in TOOLS if lid in UNIVERSAL_IDS]
    RBAC = [(lid, ic, n) for lid, ic, n in TOOLS if lid in RBAC_IDS]

    pillars = "\n".join(
        f'      <div class="pcard"><span class="pi">{icon(ic, 34)}</span>'
        f'<span class="pl">{L[lid]}</span></div>'
        for lid, ic in PILLARS)

    def toolchip(label, ic, cls):
        return (f'      <div class="tchip {cls}">{icon(ic, 20, INK2)}'
                f'<span class="tl">{label}</span></div>')

    univ_row = "\n".join(toolchip(L[l], ic, "") for l, ic, _ in UNIV)
    rbac_row = "\n".join(toolchip(L[l], ic, "rbac") for l, ic, _ in RBAC)

    # 平台:纯文字 + 分隔点做静默带。**不**加下划线/底色 —— 旧图用橙色块强调市场芯片,
    # 但浅色编辑风里"一行里两个彩色块"比"全行同权重"更像模板。
    plats = "  ·  ".join(f'<span class="pill">{L[p]}</span>' for p in PLATFORM)

    badges = "\n        ".join(f'<span>{b}</span>' for b in c["badges"])
    total_mcp = sum(n for _, n in servers)
    mcp_detail = " · ".join(f"{k} {n}" for k, n in servers)

    # hero 右侧统计块:填掉左对齐布局下右侧 1/3 的空白
    hero_right = f"""<div class="hero-right">
      <div class="hr-num">{total_mcp}</div>
      <div class="hr-cap">{c["hr_cap"]}</div>
      <div class="hr-sep"></div>
      <div class="hr-row">{c["hr_r1"].format(nt=len(TOOLS), np=len(PILLARS))}</div>
      <div class="hr-row">{c["hr_r2"]}</div>
      <div class="hr-row">{c["hr_r3"]}</div>
    </div>"""

    return f"""<!DOCTYPE html>
<style>
  * {{ margin:0; padding:0; box-sizing:border-box; }}
  html, body {{ width:{SIZE_W}px; height:{SIZE_H}px; overflow:hidden; }}
  body {{
    background:{BG}; color:{INK};
    font-family:"Inter","Segoe UI","Microsoft YaHei","PingFang SC",sans-serif;
    padding:60px 76px 48px; display:flex; flex-direction:column;
    /* 极轻的网格底纹 —— 精密感,不抢内容 */
    background-image:
      linear-gradient(rgba(94,234,212,.028) 1px, transparent 1px),
      linear-gradient(90deg, rgba(94,234,212,.028) 1px, transparent 1px);
    background-size:48px 48px;
  }}
  /* ---------- HERO ---------- */
  .hero {{ display:flex; justify-content:space-between; align-items:flex-start; gap:56px; }}
  .hero h1 {{ font-size:74px; font-weight:800; letter-spacing:-0.028em; line-height:1;
              text-shadow:0 0 42px rgba(94,234,212,.20); }}
  .hero .claim {{ font-size:23px; font-weight:600; color:{INK2}; margin-top:14px;
                 letter-spacing:-0.005em; white-space:nowrap; }}
  .hero .claim b {{ color:{ACCENT}; font-weight:700; }}
  .badges {{ display:flex; gap:8px; margin-top:20px; }}
  .badges span {{ font-family:ui-monospace,"Cascadia Mono",Consolas,monospace;
                  font-size:13.5px; font-weight:600; color:{INK2};
                  border:1px solid {EDGE}; border-radius:6px; padding:5px 11px;
                  background:{PANEL}; }}
  .hero-right {{ text-align:right; padding-top:6px; flex-shrink:0; }}
  /* 等宽字体 = 工程/精密语义,最省力的科技感信号 */
  .hero-right .hr-num {{ font-family:ui-monospace,"Cascadia Mono",Consolas,monospace;
                         font-size:66px; font-weight:700; line-height:.9;
                         letter-spacing:-0.04em; color:{ACCENT};
                         text-shadow:0 0 34px rgba(94,234,212,.34); }}
  .hero-right .hr-cap {{ font-size:14.5px; font-weight:600; color:{INK2}; margin-top:9px;
                         line-height:1.5; }}
  .hero-right .hr-cap span {{ color:{ACCENT}; font-weight:650; }}
  .hero-right .hr-sep {{ height:1px; background:{RULE}; margin:15px 0 12px; }}
  .hero-right .hr-row {{ font-size:14px; font-weight:550; color:{INK2}; line-height:1.8;
                         white-space:nowrap; }}
  .hero-right .hr-row b {{ color:{INK}; font-weight:700; }}
  /* ---------- 区块 ---------- */
  .sec {{ margin-top:26px; }}
  .sec-head {{ display:flex; align-items:baseline; gap:12px; margin-bottom:15px;
               border-bottom:1px solid {RULE}; padding-bottom:8px; }}
  .sec-head .t {{ font-family:ui-monospace,"Cascadia Mono",Consolas,monospace;
                  font-size:14px; font-weight:700; letter-spacing:.10em;
                  text-transform:uppercase; color:{ACCENT}; }}
  .sec-head .n {{ font-size:13.5px; font-weight:500; color:{INK2}; margin-left:auto;
                 font-family:ui-monospace,"Cascadia Mono",Consolas,monospace; }}
  /* ---------- 核心卡:暗底浮起面板 + 微光晕 = 科技质感的关键 ---------- */
  .prow {{ display:grid; grid-template-columns:repeat(7,1fr); gap:12px; }}
  .pcard {{ background:linear-gradient(180deg,{PANEL2},{PANEL}); border:1px solid {EDGE};
            border-radius:11px; padding:24px 8px 19px;
            display:flex; flex-direction:column; align-items:center; gap:10px;
            box-shadow:0 1px 0 rgba(94,234,212,.07) inset, 0 8px 22px rgba(0,0,0,.34);
            transition:none; }}
  .pcard .pi {{ height:30px; display:flex; align-items:center;
                filter:drop-shadow(0 0 6px rgba(94,234,212,.34)); }}
  .pcard .pl {{ font-size:16px; font-weight:650; white-space:nowrap; color:{INK}; }}
  /* ---------- 工具片 ---------- */
  .trow {{ display:flex; gap:9px; flex-wrap:wrap; }}
  .tchip {{ display:flex; align-items:center; gap:7px; background:{PANEL};
            border:1px solid {EDGE}; border-radius:8px; padding:10px 15px; }}
  /* RBAC:霓虹青左边框 + 微光,分类靠形态+发光传达 */
  .tchip.rbac {{ background:rgba(94,234,212,.09); border-color:rgba(94,234,212,.42);
                 box-shadow:inset 3px 0 0 {ACCENT}, 0 0 12px rgba(94,234,212,.07); }}
  .tchip .tl {{ font-size:15px; font-weight:600; white-space:nowrap; color:{INK}; }}
  .tgroup {{ display:flex; align-items:center; gap:12px; margin:13px 0 10px; }}
  .tgroup .gl {{ font-family:ui-monospace,"Cascadia Mono",Consolas,monospace;
                  font-size:12.5px; font-weight:700; letter-spacing:.04em; color:{INK2}; }}
  .tgroup .gr {{ flex:1; height:1px;
                 background:linear-gradient(90deg,{RULE},transparent); }}
  /* ---------- MCP 明细 ---------- */
  .mcp-strip {{ margin-top:28px; display:flex; align-items:baseline; gap:14px;
                background:{PANEL}; border:1px solid {EDGE}; border-radius:10px;
                padding:15px 20px; margin-bottom:14px;
                box-shadow:0 6px 20px rgba(0,0,0,.30); }}
  .mcp-strip .ms-k {{ font-size:14px; font-weight:700; color:{INK}; white-space:nowrap; }}
  .mcp-strip .ms-v {{ font-family:ui-monospace,"Cascadia Mono",Consolas,monospace;
                      font-size:14px; font-weight:700; color:{ACCENT};
                      font-variant-numeric:tabular-nums; white-space:nowrap; }}
  .mcp-strip .ms-n {{ font-size:13.5px; font-weight:500; color:{INK2}; }}
  .foot {{ margin-top:auto; display:flex; justify-content:space-between;
           align-items:center; border-top:1px solid {RULE}; padding-top:16px; }}
  .pills {{ display:flex; gap:7px; flex-wrap:wrap; }}
  .pill {{ font-size:14px; font-weight:550; color:{INK2}; }}
  .sig {{ font-family:ui-monospace,"Cascadia Mono",Consolas,monospace;
          font-size:13.5px; font-weight:600; color:{INK2}; }}
</style></head><body>
  <div class="hero">
    <div>
      <h1>{c["title"]}</h1>
      <div class="claim">{c["claim"]}</div>
      <div class="badges">
        {badges}
      </div>
    </div>
{hero_right}
  </div>

  <div class="sec">
    <div class="sec-head"><span class="t">{c["sec1"]}</span><span class="n">7</span></div>
    <div class="prow">
{pillars}
    </div>
  </div>

  <div class="sec">
    <div class="sec-head"><span class="t">{c["sec2"]}</span><span class="n">{c["sec2_note"].format(nt=len(TOOLS), nu=len(UNIV), nr=len(RBAC))}</span></div>
    <div class="tgroup"><span class="gl">{c["g_univ"]}</span><span class="gr"></span></div>
    <div class="trow">
{univ_row}
    </div>
    <div class="tgroup"><span class="gl">{c["g_rbac"]}</span><span class="gr"></span></div>
    <div class="trow">
{rbac_row}
    </div>
  </div>

  <div class="mcp-strip">
    <span class="ms-k">{c["sec3"]}</span>
    <span class="ms-v">{mcp_detail}</span>
    <span class="ms-n">{c["mcp_note"]}</span>
  </div>

  <div class="foot">
    <div class="pills">
{plats}
    </div>
    <div class="sig">{c["sig"]}</div>
  </div>
</body></html>"""


def render(lang: str, out: pathlib.Path) -> None:
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        sys.exit("缺少依赖:pip install playwright && playwright install chromium")
    html = build(lang)
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(
            viewport={"width": SIZE_W, "height": SIZE_H}, device_scale_factor=2)
        page.set_content(html, wait_until="networkidle")
        page.screenshot(path=str(out))
        browser.close()
    print(f"[overview] {lang} -> {out}")


def main() -> None:
    DOCS.mkdir(parents=True, exist_ok=True)
    render("en", DOCS / "project-overview-en.png")
    render("zh", DOCS / "project-overview-zh.png")


if __name__ == "__main__":
    main()


