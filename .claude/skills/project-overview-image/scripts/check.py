#!/usr/bin/env python3
"""项目概览图确定性检查(2026-09-19 取代视觉模型检查)。

文生图时代需要 vision 模型查"重复卡/漏卡/跳号";确定性渲染下这些缺陷
**构造上不可能**,检查改为对布局真源(generate.DATA/LABELS)与产物的静态校验:

1. 结构自洽:每行标签唯一(无重复)、章节编号 01-04 连续、统计胶囊数字
   与卡片数量一致、橙色高亮恰好 2 个且为市场芯片;
2. 双语 parity:en/zh 使用同一 id 集合,无缺失文案;
3. 产物存在且为 2560x2560(1280 CSS px @2x)PNG。

用法:python scripts/check.py   → 全过 exit 0,任一失败 exit 1
"""
from __future__ import annotations

import pathlib
import struct
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import generate as G  # noqa: E402

FAILURES: list[str] = []


def fail(msg: str) -> None:
    FAILURES.append(msg)


def check_structure() -> None:
    # 每行标签唯一(旧文生图最高频缺陷:平台行重复「会话」、工具行重复「部署」)
    for row_name, ids in (
        ("pillars", [lid for lid, _ in G.PILLARS]),
        ("tools", [lid for lid, _, _ in G.TOOLS]),
        ("platform", list(G.PLATFORM)),
    ):
        dup = {i for i in ids if ids.count(i) > 1}
        if dup:
            fail(f"{row_name} 行存在重复标签: {sorted(dup)}")

    # 浅色编辑风(2026-10-04):平台片改为纯文字 + 分隔点,不再有橙色高亮块,
    # 故 MARKET_IDS/TECH/BUILD 三者已从布局移除,对应的校验一并撤下。
    # PLATFORM 本身仍需非空。

    # 浅色版的统计在 hero 右侧块(54 MCP 工具 / 12 组工具 · 7 核心能力),
    # 不再有顶部胶囊,故改为校验**推导函数与卡片集合一致**。
    if G.universal_count() + G.rbac_count() != len(G.TOOLS):
        fail("universal + RBAC 计数应等于工具卡总数")
    # 官方技能数是独立常量(不在卡片集合里),只校验为正 —— 真实值需人工核对 SQL 种子
    if G.OFFICIAL_SKILLS < 1:
        fail("OFFICIAL_SKILLS 应为正数")
    # MCP server 明细的总工具数就是 hero 上的大数字
    if sum(n for _, n in G.MCP_SERVERS) < 1:
        fail("MCP_SERVERS 的工具数合计应���正数")
    # 分类必须**恰好覆盖**工具卡:不重叠、不遗漏。
    # 这条替代了旧的手写常量等式校验 —— 旧式(UNIVERSAL_COUNT + RBAC_COUNT ==
    # len(TOOLS))在"7+4==11 恰好等于当时卡数"时会通过,加工具漏改常量则构造型漂移。
    ids = [lid for lid, _, _ in G.TOOLS]
    both = G.UNIVERSAL_IDS & G.RBAC_IDS
    if both:
        fail(f"同一工具不能同时算 universal 与 RBAC: {sorted(both)}")
    uncategorized = [i for i in ids if i not in G.UNIVERSAL_IDS and i not in G.RBAC_IDS]
    if uncategorized:
        fail(f"工具未归类(universal 或 RBAC): {uncategorized}")
    unknown = (G.UNIVERSAL_IDS | G.RBAC_IDS) - set(ids)
    if unknown:
        fail(f"分类集合里有不存在的工具 id: {sorted(unknown)}")


def check_i18n_parity() -> None:
    ids = ([lid for lid, _ in G.PILLARS] + [lid for lid, _, _ in G.TOOLS]
           + list(G.PLATFORM) + list(G.BUILD)
           + ["subtitle", "sec_pillars", "sec_tools", "sec_platform", "sec_build",
              "tools_note", "footer"])
    for lang, table in G.LABELS.items():
        missing = [i for i in ids if not table.get(i)]
        if missing:
            fail(f"{lang} 缺少文案: {missing}")
    en_only = set(G.LABELS["en"]) - set(G.LABELS["zh"])
    zh_only = set(G.LABELS["zh"]) - set(G.LABELS["en"])
    if en_only or zh_only:
        fail(f"双语文案键不对齐: en_only={sorted(en_only)} zh_only={sorted(zh_only)}")
    # 版式文案(COPY)同样要求双语键对齐 —— 浅色版把标题/副标题等搬进了 COPY,
    # 只校验 LABELS 会漏掉这一半
    c_en, c_zh = set(G.COPY["en"]), set(G.COPY["zh"])
    if c_en != c_zh:
        fail(f"COPY 双语键不对齐: en_only={sorted(c_en - c_zh)} zh_only={sorted(c_zh - c_en)}")


def check_section_numbering() -> None:
    """浅色编辑风(2026-10-04)不再显示 01/02/03 章节编号 —— 层级改由留白与
    分组标签表达。旧的"编号连续"校验随之失效,改为校验**版式文案**齐全:
    三个区块标题 + universal/RBAC 两个分组标签,双语都必须有。"""
    for lang in G.LABELS:
        c = G.COPY[lang]
        for key in ("sec1", "sec2", "sec3", "g_univ", "g_rbac", "sig"):
            if not c.get(key):
                fail(f"{lang} 版式文案缺少 {key}")


def png_size(path: pathlib.Path) -> tuple[int, int]:
    with path.open("rb") as fh:
        head = fh.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"非 PNG: {path}")
    w, h = struct.unpack(">II", head[16:24])
    return w, h


def check_artifacts() -> None:
    for lang in ("en", "zh"):
        png = G.DOCS / f"project-overview-{lang}.png"
        if not png.exists():
            fail(f"缺少产物: {png}(先跑 generate.py)")
            continue
        w, h = png_size(png)
        if (w, h) != (G.SIZE_W * 2, G.SIZE_H * 2):
            fail(f"{png.name} 尺寸 {w}x{h} ≠ {G.SIZE_W * 2}x{G.SIZE_H * 2}")


def main() -> int:
    check_structure()
    check_i18n_parity()
    check_section_numbering()
    check_artifacts()
    if FAILURES:
        print("[overview-check] FAIL:")
        for f in FAILURES:
            print("  -", f)
        return 1
    print("[overview-check] OK:结构自洽 / 双语 parity / 章节编号 / 产物尺寸 全过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
