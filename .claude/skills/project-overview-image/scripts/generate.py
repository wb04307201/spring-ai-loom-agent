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

SIZE = 1280  # CSS px;截图 device_scale_factor=2 → 2560x2560 物理像素

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
}

# ---------------------------------------------------------------- 结构(语言无关)
PILLARS = [  # 核心区卡片:(label_id, icon)
    ("chat", "bubble"), ("knowledge", "ribbon"), ("files", "doc"),
    ("mcp", "plug"), ("skill", "spark"), ("canvas", "palette"), ("rbac", "shield"),
]
TOOLS = [  # 工具区卡片:(label_id, icon, 方法数徽章)
    ("tool_file", "doc", 16), ("tool_knowledge", "ribbon", 1), ("tool_git", "branch", 28),
    ("tool_maven", "grid", 6), ("tool_deploy", "deploy", 1), ("tool_time", "clock", 2),
    ("tool_skill", "spark", 2), ("tool_subtask", "layers", 4), ("tool_schedule", "cal", 4),
    ("tool_askuser", "ask", 1), ("tool_render", "monitor", 1),
]
UNIVERSAL_COUNT = 7   # file/knowledge/time/skill/subtask/schedule/askUser
RBAC_COUNT = 4        # git/maven/compile/render
PLATFORM = ["user", "role", "session", "market_skill", "market_kb", "console"]
MARKET_IDS = {"market_skill", "market_kb"}  # 全海报唯一两个橙色芯片
TECH = ["Spring Boot", "Spring AI", "JDK 17", "JVector", "JGit", "H2", "Flyway", "ChatMemory"]
BUILD = ["build_core", "build_config", "build_starter", "build_test"]

# ---------------------------------------------------------------- 双语文案
LABELS = {
    "en": {
        "subtitle": "Spring Boot AI Agent Out-of-the-Box Solution",
        "sec_pillars": "01 PILLARS", "sec_tools": "02 TOOLS",
        "sec_platform": "03 PLATFORM", "sec_build": "04 BUILD",
        "tools_note": "7 universal  ·  04 RBAC",
        "chat": "Chat", "knowledge": "Knowledge", "files": "Files", "mcp": "MCP",
        "skill": "Skill", "canvas": "Canvas", "rbac": "RBAC",
        "tool_file": "Files", "tool_knowledge": "Knowledge", "tool_git": "Git",
        "tool_maven": "Maven", "tool_deploy": "Deploy", "tool_time": "Time",
        "tool_skill": "Skill", "tool_subtask": "Sub-task", "tool_schedule": "Schedule",
        "tool_askuser": "Ask-user", "tool_render": "Html render",
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
        "tools_note": "7 通用  ·  4 RBAC",
        "chat": "对话", "knowledge": "知识库", "files": "文件", "mcp": "MCP",
        "skill": "技能", "canvas": "画板", "rbac": "权限",
        "tool_file": "文件", "tool_knowledge": "知识库", "tool_git": "Git",
        "tool_maven": "Maven", "tool_deploy": "部署", "tool_time": "时间",
        "tool_skill": "技能", "tool_subtask": "子任务", "tool_schedule": "定时",
        "tool_askuser": "问答", "tool_render": "HTML渲染",
        "user": "用户", "role": "角色", "session": "会话",
        "market_skill": "技能市场", "market_kb": "知识市场", "console": "管理控制台",
        "build_core": "核心", "build_config": "自动配置",
        "build_starter": "Starter", "build_test": "测试应用",
        "footer": "接口  ·  默认  ·  可替换",
    },
}


def stats_line() -> str:
    """统计胶囊由 DATA 自动计算 —— 与卡片数量构造上一致,不会过时。"""
    return (f"{len(PILLARS):02d} PILLARS  -  {len(TOOLS):02d} TOOLS  -  "
            f"{UNIVERSAL_COUNT:02d} UNIVERSAL  -  {RBAC_COUNT:02d} RBAC")


def _icon(name: str) -> str:
    return (f'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" '
            f'stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">'
            f'{ICONS[name]}</svg>')


def build_html(lang: str) -> str:
    t = LABELS[lang]
    pillar_cards = "\n".join(
        f'      <div class="card"><span class="ico">{_icon(ic)}</span>'
        f'<span class="lbl">{t[lid]}</span></div>'
        for lid, ic in PILLARS)
    tool_cards = "\n".join(
        f'      <div class="card tool"><span class="badge">{n}</span>'
        f'<span class="ico">{_icon(ic)}</span><span class="lbl">{t[lid]}</span></div>'
        for lid, ic, n in TOOLS)
    platform_chips = "\n".join(
        f'      <div class="chip{" market" if pid in MARKET_IDS else ""}">{t[pid]}</div>'
        for pid in PLATFORM)
    tech_chips = "\n".join(f'      <div class="tech">{name}</div>' for name in TECH)
    build_row = f'\n      <span class="arrow">→</span>\n      '.join(
        f'<div class="buildbox">{t[bid]}</div>' for bid in BUILD)

    return f"""<!DOCTYPE html>
<html lang="{lang}">
<head>
<meta charset="utf-8">
<style>
  * {{ margin: 0; padding: 0; box-sizing: border-box; }}
  html, body {{ width: {SIZE}px; height: {SIZE}px; overflow: hidden; }}
  body {{
    background: #232a3d;
    font-family: "Inter", "Segoe UI", "Microsoft YaHei", "PingFang SC", sans-serif;
    color: #ffffff;
    padding: 56px 64px 40px;
    display: flex; flex-direction: column;
    justify-content: space-between;  /* 大区呼吸空白均匀分布,不留整块死空 */
  }}
  .hero {{ text-align: center; }}
  .hero h1 {{ font-size: 64px; font-weight: 800; letter-spacing: 0.5px; }}
  .hero .sub {{ font-size: 27px; font-weight: 700; color: #45e3cf; margin-top: 10px; }}
  .hero .stats {{
    display: inline-block; margin-top: 26px; padding: 9px 26px;
    border: 1.6px solid #45e3cf; border-radius: 999px;
    font-size: 17px; font-weight: 700; letter-spacing: 1.2px; color: #ffffff;
  }}
  section {{ margin-bottom: 6px; }}
  .sec-head {{ display: flex; align-items: baseline; gap: 18px; margin-bottom: 14px; }}
  .sec-head .tag {{ font-size: 24px; font-weight: 800; color: #45e3cf; letter-spacing: 1px; }}
  .sec-head .note {{ font-size: 17px; font-weight: 700; color: #ffffff; }}
  .row {{ display: flex; gap: 14px; }}
  .card {{
    flex: 1; background: #45e3cf; color: #16202e; border-radius: 14px;
    min-height: 96px; padding: 14px 6px 12px;
    display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px;
    position: relative;
  }}
  .card .ico svg {{ width: 34px; height: 34px; display: block; }}
  .card .lbl {{ font-size: 17px; font-weight: 700; white-space: nowrap; }}
  .card.tool {{ min-height: 104px; }}
  .card .badge {{
    position: absolute; top: -10px; left: -6px;
    width: 26px; height: 26px; border-radius: 50%;
    background: #eafcf9; color: #16202e; border: 1.4px solid #16202e;
    font-size: 13px; font-weight: 800;
    display: flex; align-items: center; justify-content: center;
  }}
  .chiprow {{ display: flex; gap: 14px; margin-bottom: 14px; }}
  .chip {{
    flex: 1; background: #45e3cf; color: #16202e; border-radius: 10px;
    padding: 13px 4px; text-align: center; font-size: 17px; font-weight: 700; white-space: nowrap;
  }}
  .chip.market {{ background: #f5a020; color: #201409; }}
  .techrow {{ display: flex; gap: 12px; }}
  .tech {{
    flex: 1; border: 1.5px solid #45e3cf; border-radius: 999px;
    padding: 10px 4px; text-align: center;
    font-size: 15.5px; font-weight: 700; color: #45e3cf; white-space: nowrap;
  }}
  .buildrow {{ display: flex; align-items: center; gap: 16px; }}
  .buildbox {{
    flex: 1; background: #45e3cf; color: #16202e; border-radius: 12px;
    padding: 18px 4px; text-align: center; font-size: 19px; font-weight: 800; white-space: nowrap;
  }}
  .arrow {{ color: #45e3cf; font-size: 30px; font-weight: 800; }}
  footer {{ text-align: center; }}
  footer .line {{ border-top: 1.6px solid #45e3cf; margin-bottom: 16px; }}
  footer .txt {{ font-size: 20px; font-weight: 700; letter-spacing: 2px; }}
</style>
</head>
<body>
  <div class="hero">
    <h1>Spring AI LoomAgent</h1>
    <div class="sub">{t['subtitle']}</div>
    <div class="stats">{stats_line()}</div>
  </div>
  <section>
    <div class="sec-head"><span class="tag">{t['sec_pillars']}</span></div>
    <div class="row">
{pillar_cards}
    </div>
  </section>
  <section>
    <div class="sec-head"><span class="tag">{t['sec_tools']}</span><span class="note">{t['tools_note']}</span></div>
    <div class="row">
{tool_cards}
    </div>
  </section>
  <section>
    <div class="sec-head"><span class="tag">{t['sec_platform']}</span></div>
    <div class="chiprow">
{platform_chips}
    </div>
    <div class="techrow">
{tech_chips}
    </div>
  </section>
  <section>
    <div class="sec-head"><span class="tag">{t['sec_build']}</span></div>
    <div class="buildrow">
      {build_row}
    </div>
  </section>
  <footer>
    <div class="line"></div>
    <div class="txt">{t['footer']}</div>
  </footer>
</body>
</html>
"""


def render(lang: str, out: pathlib.Path) -> None:
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        sys.exit("缺少依赖:pip install playwright && playwright install chromium")
    html = build_html(lang)
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(
            viewport={"width": SIZE, "height": SIZE}, device_scale_factor=2)
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
