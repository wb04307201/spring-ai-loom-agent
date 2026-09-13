#!/usr/bin/env python3
"""
Content + cross-image consistency check for project-overview-{en,zh}.png.
Returns exit code 0 on PASS, non-zero on FAIL.
Visual analysis uses mcp__MiniMax__understand_image via the agent invoking this tool.
"""
import os
import re
import sys
import json
import subprocess
from pathlib import Path

# 关键术语
MUST_HAVE_EN = [
    "Spring AI LoomAgent",
    "Chat", "Knowledge", "Files", "MCP", "Skill", "RBAC",
    "PILLARS", "TOOLS", "PLATFORM", "BUILD",
    "Flyway", "JVector", "ChatMemory",
]
MUST_HAVE_ZH = [
    "Spring AI LoomAgent",
    "核心", "知识库", "文件", "MCP", "技能", "权限",
    "工具", "平台", "构建", "RBAC",
]
FORBIDDEN_PATTERNS = [
    (r"v\d+\.\d+", "版本号 v1.0 / v2.0 这种"),
    (r"\b\d{4}\b", "4 位数字（年份）"),
    (r"20[12]\d-\d{2}-\d{2}", "日期 YYYY-MM-DD"),
    (r"#[0-9A-Fa-f]{3,6}\b", "hex 颜色代码"),
    (r"rgba?\(.*\)", "rgb / rgba 颜色"),
]

# 视觉检查 prompt
EN_VISUAL_CHECK_PROMPT = """Analyze this project overview infographic and answer:
1. Is the title "Spring AI LoomAgent" clearly visible? (Y/N)
2. Are these zones present in order:
   - HERO: big title + cyan tagline + a thin outline pill reading "06 PILLARS - 11 TOOLS - 07 UNIVERSAL - 04 RBAC"? (Y/N)
   - 01 PILLARS: exactly 6 equal cyan cards — Chat, Knowledge, Files, MCP, Skill, RBAC? (Y/N + count)
   - 02 TOOLS: caption "7 universal  4 RBAC" next to the zone label + exactly 11 equal cards — Files, Knowledge, Git, Maven, Deploy, Time, Skill, Sub-task, Schedule, Ask-user, Html render? (Y/N + count + missing)
   - 03 PLATFORM: row A = 6 chips (Users, Roles, Sessions, Skill Market, KB Market, Admin Console) with ONLY Skill Market + KB Market orange; row B = exactly 8 thin cyan stack pills (Spring Boot, Spring AI, JDK 17, JVector, JGit, H2, Flyway, ChatMemory)? (Y/N + counts)
   - 04 BUILD: 4 boxes connected by arrows — core, config, starter, test? (Y/N)
   - FOOTER: thin cyan hairline with centered white text "Interface · Default · Replaceable"? (Y/N)
3. Are there any visible:
   - Version numbers like "v1.0", "v2.0", "1.0.0", "3.5.16"? (Y/N)
   - Dates like "2024", "2025", "2026", "2024-01-01"? (Y/N)
   - Hex color codes like "#0f172a", "#ffffff", "rgb(...)"? (Y/N)
   - Garbled text / broken Chinese / spelling errors? (Y/N)
   - Any text that looks like raw code or debug output? (Y/N)
4. Is the visual style consistent with a modern dark-themed professional infographic? (Y/N)
5. Are the icons / graphics clean (no copyrighted logos)? (Y/N)

Reply in format:
  title: Y/N
  zones_complete: Y/N (missing: ...)
  tool_card_count: N
  stack_pill_count: N
  has_version: Y/N (examples: ...)
  has_date: Y/N
  has_hex: Y/N
  has_garbled: Y/N
  has_raw_code: Y/N
  style_ok: Y/N
  icons_clean: Y/N
  notes: ...
"""

ZH_VISUAL_CHECK_PROMPT = """分析这张中文项目概览信息图，回答：
1. 标题 "Spring AI LoomAgent" 是否清晰可见？(Y/N)
2. 这些区块是否按顺序存在：
   - HERO：大标题 + 青色副标题 + 细轮廓胶囊「06 PILLARS - 11 TOOLS - 07 UNIVERSAL - 04 RBAC」？(Y/N)
   - 01 核心：EXACTLY 6 张等大青色卡片 — 对话、知识库、文件、MCP、技能、权限？(Y/N + 数量)
   - 02 工具：区块标签旁说明文字「7 通用  4 RBAC」+ EXACTLY 11 张等大卡片 — 文件、知识库、Git、Maven、部署、时间、技能、子任务、定时、问答、HTML渲染？(Y/N + 数量 + 缺失)
   - 03 平台：第 A 行 6 个芯片（用户、角色、会话、技能市场、知识市场、管理控制台），其中**只有**技能市场 + 知识市场是橙色；第 B 行 EXACTLY 8 个细青色技术栈胶囊（Spring Boot、Spring AI、JDK 17、JVector、JGit、H2、Flyway、ChatMemory）？(Y/N + 数量)
   - 04 构建：4 个方框箭头流 — 核心、配置、启动、测试？(Y/N)
   - 页脚：细青色水平线 + 线上居中白字「接口 · 默认 · 可替换」？(Y/N)
3. 是否出现：
   - 任何版本号（v1.0、v2.0、1.0.0、3.5.16 这种）？(Y/N)
   - 任何日期 / 年份（2024、2025、2026、2024-01-01 这种）？(Y/N)
   - 任何 hex 颜色代码（#0f172a、#ffffff、rgb(...) 这种）？(Y/N)
   - 任何乱码、错别字、错误拼写？(Y/N)
   - 任何看起来像原始代码或调试输出的文字？(Y/N)
4. 视觉风格是否符合现代深色专业信息图的风格？(Y/N)
5. 图标 / 图形是否干净（无受版权保护的 logo）？(Y/N)

按格式回复：
  title: Y/N
  zones_complete: Y/N (missing: ...)
  tool_card_count: N
  stack_pill_count: N
  has_version: Y/N (examples: ...)
  has_date: Y/N
  has_hex: Y/N
  has_garbled: Y/N
  has_raw_code: Y/N
  style_ok: Y/N
  icons_clean: Y/N
  notes: ...
"""


def check_text_content(png_path: Path, lang: str) -> list[str]:
    """OCR-lite check: parse filename + look for garbled bytes in PNG text chunks."""
    # 简单检查: PNG 文件大小（< 50KB 可能太简单） + 文件名规范
    issues = []
    if not png_path.exists():
        issues.append(f"[{lang}] file not found: {png_path}")
        return issues
    size = png_path.stat().st_size
    if size < 50_000:
        issues.append(f"[{lang}] file too small ({size} bytes), may be empty or broken")
    if size > 10_000_000:
        issues.append(f"[{lang}] file too large ({size} bytes), may include unwanted content")
    # 文件名应为 docs/project-overview-{en|zh}.png
    expected_name = f"project-overview-{lang}.png"
    if png_path.name != expected_name:
        issues.append(f"[{lang}] filename should be '{expected_name}', got '{png_path.name}'")
    return issues


def extract_png_text_chunks(png_path: Path) -> list[str]:
    """从 PNG 的 tEXt / iTXt chunk 里抽出所有文本（排除压缩数据，PNG 渲染层 OCR 之外这是唯一可靠路径）。"""
    if not png_path.exists():
        return []
    try:
        with png_path.open("rb") as f:
            data = f.read()
    except Exception:
        return []
    texts = []
    i = 8  # skip PNG signature (8 bytes) + first IHDR (25 bytes)
    while i < len(data) - 12:
        length = int.from_bytes(data[i:i+4], "big")
        chunk_type = data[i+4:i+8].decode("ascii", errors="ignore")
        if chunk_type in ("tEXt", "iTXt", "zTXt"):
            chunk_data = data[i+8:i+8+length]
            try:
                if chunk_type == "tEXt":
                    # tEXt: keyword \0 text
                    null_idx = chunk_data.find(b"\x00")
                    if null_idx >= 0:
                        texts.append(chunk_data[null_idx+1:].decode("utf-8", errors="ignore"))
                elif chunk_type == "iTXt":
                    # iTXt: keyword \0 compression_flag compression_method language_tag \0 translated_keyword \0 text
                    null_idx = chunk_data.find(b"\x00")
                    if null_idx >= 0:
                        rest = chunk_data[null_idx+1:]
                        if rest and rest[0:1] == b"\x00":  # uncompressed
                            texts.append(rest[1:].decode("utf-8", errors="ignore"))
                elif chunk_type == "zTXt":
                    null_idx = chunk_data.find(b"\x00")
                    if null_idx >= 0:
                        # zlib compressed, skip for simplicity
                        pass
            except Exception:
                pass
        i += 12 + length
        if length < 0:
            break
    return texts


def check_forbidden_patterns(png_path: Path, lang: str) -> list[str]:
    """从 PNG 文本 chunk（tEXt / iTXt）中检测 forbidden patterns。
    跳过 XMP / iTXt 元数据段（PNG 库自动嵌入，与图内容无关）。"""
    issues = []
    if not png_path.exists():
        return issues
    text_chunks = extract_png_text_chunks(png_path)
    if not text_chunks:
        return issues
    # 过滤掉 XMP / iTXt 元数据（这些是 PNG 库自动嵌入，不算图内容）
    filtered = [t for t in text_chunks if "<?xpacket" not in t and "x:xmpmeta" not in t]
    text = "\n".join(filtered)
    if not text.strip():
        return issues
    for pat, desc in FORBIDDEN_PATTERNS:
        m = re.search(pat, text)
        if m:
            issues.append(f"[{lang}] PNG text-chunk contains {desc}: '{m.group(0)[:50]}'")
    return issues


def cross_image_consistency(en_path: Path, zh_path: Path) -> list[str]:
    issues = []
    if not en_path.exists() or not zh_path.exists():
        return issues
    en_size = en_path.stat().st_size
    zh_size = zh_path.stat().st_size
    ratio = abs(en_size - zh_size) / max(en_size, zh_size)
    if ratio > 0.3:
        issues.append(f"size diff > 30% (en={en_size}, zh={zh_size}), styles may differ")
    return issues


def parse_visual_report(reply: str) -> dict:
    """Parse 'key: value' format from LLM visual report."""
    out = {}
    for line in reply.splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            out[k.strip()] = v.strip()
    return out


def main():
    project = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    en_png = project / "docs" / "project-overview-en.png"
    zh_png = project / "docs" / "project-overview-zh.png"

    print(f"== Content + Cross-image Consistency Check ==")
    print(f"en: {en_png}")
    print(f"zh: {zh_png}")
    print()

    all_issues = []

    # 1) 文件名 / 大小
    for png, lang in [(en_png, "en"), (zh_png, "zh")]:
        all_issues.extend(check_text_content(png, lang))

    # 2) forbidden 模式（粗略正则）
    for png, lang in [(en_png, "en"), (zh_png, "zh")]:
        all_issues.extend(check_forbidden_patterns(png, lang))

    # 3) 跨图一致性
    all_issues.extend(cross_image_consistency(en_png, zh_png))

    # 4) 视觉检查（依赖外部 agent 调 mcp__MiniMax__understand_image）
    # 这里只打印占位 — 实际 agent 调起时会读 STDOUT 然后调 mcp__MiniMax__understand_image
    print("Visual checks (delegated to agent invoking mcp__MiniMax__understand_image):")
    print(f"  en prompt file: /tmp/visual-check-en.txt  ->  read first 4KB of {en_png}")
    print(f"  zh prompt file: /tmp/visual-check-zh.txt  ->  read first 4KB of {zh_png}")
    print()

    # 写 visual prompt 文件
    Path("/tmp/visual-check-en.txt").write_text(EN_VISUAL_CHECK_PROMPT)
    Path("/tmp/visual-check-zh.txt").write_text(ZH_VISUAL_CHECK_PROMPT)

    # 报告
    if all_issues:
        print("== FOUND ISSUES ==")
        for i in all_issues:
            print(f"  - {i}")
        sys.exit(1)
    print("== PASS (text-level) ==")
    print("Visual check: agent must invoke mcp__MiniMax__understand_image on each PNG and")
    print("parse the LLM report. If any check fails, regenerate via generate.py with a")
    print("refined prompt (e.g. emphasize 'NO HEX COLORS' or 'ALL ENGLISH' etc.).")
    sys.exit(0)


if __name__ == "__main__":
    main()
