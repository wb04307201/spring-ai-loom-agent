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
        ("build", list(G.BUILD)),
    ):
        dup = {i for i in ids if ids.count(i) > 1}
        if dup:
            fail(f"{row_name} 行存在重复标签: {sorted(dup)}")

    # 橙色高亮恰好 2 个且仅市场芯片
    if len(G.MARKET_IDS) != 2 or not G.MARKET_IDS.issubset(set(G.PLATFORM)):
        fail("橙色市场高亮必须恰好 2 个且属于 platform 行")

    # 统计胶囊与卡片数量一致
    line = G.stats_line()
    expect = (f"{len(G.PILLARS):02d} PILLARS  -  {len(G.TOOLS):02d} TOOLS  -  "
              f"{G.UNIVERSAL_COUNT:02d} UNIVERSAL  -  {G.RBAC_COUNT:02d} RBAC")
    if line != expect:
        fail(f"统计胶囊与数据不一致: {line!r} != {expect!r}")
    if G.UNIVERSAL_COUNT + G.RBAC_COUNT != len(G.TOOLS):
        fail("universal + RBAC 计数应等于工具卡总数")


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


def check_section_numbering() -> None:
    for lang, table in G.LABELS.items():
        nums = [table[k].split()[0] for k in
                ("sec_pillars", "sec_tools", "sec_platform", "sec_build")]
        if nums != ["01", "02", "03", "04"]:
            fail(f"{lang} 章节编号不连续(旧 zh 图实测跳号缺陷): {nums}")


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
        if (w, h) != (G.SIZE * 2, G.SIZE * 2):
            fail(f"{png.name} 尺寸 {w}x{h} ≠ {G.SIZE * 2}x{G.SIZE * 2}")


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
