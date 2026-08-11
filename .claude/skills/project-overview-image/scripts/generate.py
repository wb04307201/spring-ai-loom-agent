#!/usr/bin/env python3
"""
Generate 2 project overview images (English + Chinese) using 阿里云百炼 wan2.7-image.

Output: <project>/docs/project-overview-{en,zh}.png

wan2.7-image endpoint (sync):
  POST https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation
  Body: {model, input.messages[role=user, content[{text}]], parameters.{n, size}}

Set WORKSPACE_ID env var (or DASHSCOPE_WORKSPACE_ID) to your business space ID.
Set DASHSCOPE_API_KEY env var.

Use RUN_ID env var to generate candidate files (project-overview-{en,zh}-r{N}.png).
"""
import os
import re
import sys
import json
import argparse
import urllib.request
from pathlib import Path

# ===== 配置 =====
# 同步端点（wan2.7-image 用同步 multimodal-generation/generation）
SUBMIT_URL_TPL = (
    "https://{workspace_id}.cn-beijing.maas.aliyuncs.com"
    "/api/v1/services/aigc/multimodal-generation/generation"
)
PRIMARY_MODEL = "wan2.7-image"
FALLBACK_MODEL = "qwen-image"  # 旧端点 fallback


# ===== 6 大板块布局（语义描述，无坐标）=====

EN_LAYOUT = """\
A dark navy infographic poster, square aspect ratio. Modern enterprise tech poster aesthetic.

Overall palette: deep navy background, neon cyan for nearly every graphical element, white for the main typographic content. There are EXACTLY TWO warm orange accents on the poster, reserved for the **Skill Market** and **KB Market** pills in ZONE 4 (PLATFORM). These two orange pills sit side by side, forming a visual "Market block". Every other element is cyan or white. Do not use orange anywhere else.

Card icon rule (applies to every card in every zone below):
  Each card has EXACTLY two regions. Region 1 is an icon area containing one simple geometric icon ONLY, with absolutely no text, letters, numbers, or ghost characters inside. Region 2 is a label at the bottom edge of the card, exactly one short label, appearing only ONCE.

Generous breathing space between zones. Within a zone the elements sit close to each other.

ZONE 1: HERO (top third of the poster, centered). In this order top to bottom:
  - One very large bold white single-line title: Spring AI LoomAgent
  - One smaller cyan tagline directly below: Spring Boot AI Agent Out-of-the-Box Solution
  - A clear empty gap
  - At the center, a thin horizontal outline pill (a rounded rectangle with a hairline cyan border, no fill) containing on a single line, in small uppercase: 06 PILLARS  -  09 TOOLS  -  07 ON  -  02 OPT-IN

ZONE 2: small uppercase section label on the left in cyan: 01 PILLARS. Below it one horizontal row of six plain rounded rectangle cards, all cyan, all the same size, in this exact order from left to right: Chat, Knowledge, Files, MCP, Skill, RBAC.

ZONE 3: small uppercase section label on the left in cyan: 02 TOOLS. To the right of that label on the same horizontal line, a small caption reading: 7 on  2 opt-in. Below the label, ONE horizontal row containing EXACTLY 9 plain rounded rectangle cards — no more, no fewer, all nine visible in the order listed — in this exact order from left to right. Every card is equal to every other card (no card is highlighted, no card is a different color, no card has a star):
  Files, Knowledge, Git, Maven, Deploy, Time, Skill, Sub-task, Schedule
  For each of these nine cards, put a small badge with the tool count (just the digit) inside the icon area at the top: 16, 1, 28, 6, 1, 2, 2, 4, 4. The card label sits at the bottom edge. The single most common rendering error is dropping one card (often Time) to fit the row; render all nine even if each card becomes narrower, prefer smaller width over omission, never omit a card or merge two cards into one.

ZONE 4: small uppercase section label on the left in cyan: 03 PLATFORM. Below it, two stacked rows:
  Row A: one horizontal row of six small chip rectangles on the left in this order: Users, Roles, Sessions, Skill Market, KB Market, Admin Console. The **Skill Market** and **KB Market** pills are the ONLY orange-filled elements on the whole poster, sitting side by side to form a visual "Market block". All other chips are cyan-filled.
  Row B: ONE horizontal row containing EXACTLY 8 thin cyan pill labels — no more, no fewer, all eight visible — in this exact order: Spring Boot, Spring AI, JDK 17, JVector, JGit, H2, Flyway, ChatMemory. JVector is one seven-letter word starting with capital J, then capital V, then lower-case e c t o r. The single most common rendering error is dropping one of these labels; render all eight even if the row becomes tighter, prefer smaller padding over omission.

ZONE 5: small uppercase section label on the left in cyan: 04 BUILD. Below it, one horizontal row of four plain rounded rectangle boxes connected by right-pointing arrows, in this order: core, config, starter, test.

FOOTER at the very bottom: one thin horizontal cyan hairline spanning most of the poster width, with a centered white tagline written ON the line: Interface  ·  Default  ·  Replaceable. Below the line, a small amount of empty breathing space and nothing else.
"""

ZH_LAYOUT = """\
深蓝色信息图海报，正方形。现代企业科技海报风格。

整体配色：深蓝色背景，霓虹青色作为几乎所有图形的颜色，白色作为主文字。海报上 EXACTLY 只有两个暖橙色高亮，保留给 04 区（PLATFORM）里的"技能市场"和"知识市场"芯片。这两个橙色芯片并排相邻，形成视觉上的"市场板块"。其他任何元素都不能用橙色。

卡片图标规则（下面每个区的所有卡片都遵守）：
  每张卡片严格只有两个区域：(1) 图标区 —— 一个干净简单的几何图标，绝对不允许任何文字、字母、数字、伪字符出现在图标里；(2) 标签区 —— 在卡片底边恰好一个简短标签。同一张卡片里标签只能出现一次。

每个大区之间留出明显的空白呼吸。区内部元素紧凑排列。

01 区 HERO（海报上 1/3，居中）。从上到下依次为：
  - 一行超大白色粗体标题：Spring AI LoomAgent
  - 紧接下方一行较小的青色副标题：Spring Boot AI Agent 开箱即用方案
  - 一段空白
  - 居中位置一个细轮廓横向胶囊（圆角矩形，只有青色描边，没有填充），里面单行小号大写英文：06 PILLARS  -  09 TOOLS  -  07 ON  -  02 OPT-IN

02 区：左侧一行青色小号大写章节标签：01 核心。其下方一行六张圆角矩形卡片，全部青色等大，从左到右依次为：对话、知识库、文件、MCP、技能、权限。

03 区：左侧一行青色小号大写章节标签：02 工具。在章节标签右侧同一行小号说明文字：7 启用  2 手动。下方 EXACTLY 9 张圆角矩形卡片组成一行 — 不能多也不能少，9 张全部可见 — 全部青色等大，从左到右依次为。每张卡片完全相同（没有高亮、没有其他颜色、没有五角星）：
  文件、知识库、Git、Maven、部署、时间、技能、子任务、定时
  每张卡片在图标区顶端放一个小数字徽章：16、1、28、6、1、2、2、4、4。卡片标签贴在底部。最常见的渲染错误是漏掉一张卡（经常是"时间"）；即使每张更窄也要全部画出来，绝对不能省略一张或两张并一张。

04 区：左侧一行青色小号大写章节标签：03 平台。其下方两行堆叠：
  第 A 行：一行六个小芯片矩形，依次为：用户、角色、会话、技能市场、知识市场、管理控制台。**技能市场**和**知识市场**是全海报 EXACTLY 唯一的两个橙色填充芯片，并排相邻形成"市场板块"。其他芯片都是青色填充。
  第 B 行：一行 EXACTLY 8 个细青色胶囊标签（不能多也不能少，8 个全部可见），依次为：Spring Boot、Spring AI、JDK 17、JVector、JGit、H2、Flyway、ChatMemory。JVector 是一个单词：以大写 J 和大写 V 开头，后接小写 e、c、t、o、r。最常见的渲染错误是漏掉一个；如果空间紧张就缩小 padding 而不是省略。

05 区：左侧一行青色小号大写章节标签：04 构建。其下方一行四个圆角矩形方框，用向右箭头连接，从左到右：核心、配置、启动、测试。

最底部页脚：一根贯穿海报大部分宽度的细青色水平线，线上正中位置写一行白色文字：接口  ·  默认  ·  可替换。线下方留一点空白呼吸空间，其他什么也不写。
"""


NO_META_EN = """\
Strict rules. Any violation makes this image unusable:
- DO NOT include version numbers like v1, v2, 1.0, 3.5.
- DO NOT include years or dates like 2024 or 2025.
- DO NOT include hex color codes like #0f172a or rgb().
- DO NOT include any text that looks like coordinates, code, or debug output.
- Every text element MUST be a real English word or short phrase.
- Project name MUST be exactly "Spring AI LoomAgent" with capital A and capital I.
- Keep names like JVector, JGit, Maven, Flyway, REST, MCP in their original form.
- Use simple geometric icons. No real product logos.
- All text must be horizontal and readable. No rotated text.
- Render every zone listed above. Do not omit any.
"""

NO_META_ZH = """\
严格要求。任何一条违反都让图片作废：
- 不要出现版本号，例如 v1、v2、1.0、3.5。
- 不要出现年份或日期，例如 2024 或 2025。
- 不要出现 hex 颜色代码，例如 #0f172a 或 rgb。
- 不要出现任何看起来像坐标、代码或调试输出的文字。
- 每个文字必须是真实中文或英文词汇。
- 项目名必须是 Spring AI LoomAgent。
- 专有名词保持原样：JVector、JGit、Maven、Flyway、REST、MCP。
- 只使用简单几何图标，不能出现真实产品 logo。
- 所有文字必须水平方向且可读，不能旋转。
- 上面列出的每个区块都要画出来，不能省略任何一个。
"""


def build_en_prompt() -> str:
    return (
        "Modern dark themed infographic poster, square aspect ratio.\n\n"
        + EN_LAYOUT
        + "\n\n"
        + NO_META_EN
        + "\n\nOverall mood: clean grid, neon-on-dark, looks like a tech conference slide."
    )


def build_zh_prompt() -> str:
    return (
        "现代深色信息图海报，正方形。\n\n"
        + ZH_LAYOUT
        + "\n\n"
        + NO_META_ZH
        + "\n\n整体风格：干净的栅格，霓虹色配深色背景，类似技术大会幻灯片。"
    )


def call_wan27(prompt: str, api_key: str, workspace_id: str, size: str = "1280*1280") -> bytes:
    """调 wan2.7-image 同步端点。返回 PNG 字节。"""
    submit_url = SUBMIT_URL_TPL.format(workspace_id=workspace_id)
    payload = {
        "model": PRIMARY_MODEL,
        "input": {
            "messages": [
                {"role": "user", "content": [{"text": prompt}]}
            ]
        },
        "parameters": {"n": 1, "size": size},
    }
    req = urllib.request.Request(
        submit_url,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = json.loads(resp.read().decode("utf-8"))

    # 同步响应直接返回图像
    choices = (data.get("output") or {}).get("choices") or []
    if not choices:
        raise RuntimeError(f"No choices in response: {data}")
    content = choices[0].get("message", {}).get("content") or []
    for item in content:
        if item.get("type") == "image" and item.get("image"):
            url = item["image"]
            with urllib.request.urlopen(url, timeout=60) as img_resp:
                return img_resp.read()
    raise RuntimeError(f"No image URL in response: {data}")


def call_qwen_image_fallback(prompt: str, api_key: str) -> bytes:
    """回退到旧 qwen-image 端点（异步）。"""
    submit_url = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis"
    task_url_tpl = "https://dashscope.aliyuncs.com/api/v1/tasks/{task_id}"
    payload = {
        "model": FALLBACK_MODEL,
        "input": {"prompt": prompt},
        "parameters": {"size": "1328*1328", "n": 1},
    }
    req = urllib.request.Request(
        submit_url,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "X-DashScope-Async": "enable",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    task_id = (data.get("output") or {}).get("task_id")
    if not task_id:
        raise RuntimeError(f"No task_id: {data}")

    import time
    deadline = time.time() + 240
    while time.time() < deadline:
        time.sleep(3)
        req2 = urllib.request.Request(
            task_url_tpl.format(task_id=task_id),
            headers={"Authorization": f"Bearer {api_key}"},
            method="GET",
        )
        with urllib.request.urlopen(req2, timeout=30) as r:
            d2 = json.loads(r.read().decode("utf-8"))
        status = (d2.get("output") or {}).get("task_status")
        if status == "SUCCEEDED":
            url = d2["output"]["results"][0]["url"]
            with urllib.request.urlopen(url, timeout=60) as img_resp:
                return img_resp.read()
        if status == "FAILED":
            raise RuntimeError(f"Task failed: {d2}")
    raise RuntimeError("Task timeout")


def generate_one(prompt: str, api_key: str, workspace_id: str, out_path: Path) -> None:
    """优先 wan2.7-image，失败回退 qwen-image。"""
    if workspace_id:
        try:
            print(f"  [model={PRIMARY_MODEL}] submitting...", file=sys.stderr)
            png = call_wan27(prompt, api_key, workspace_id)
            out_path.write_bytes(png)
            print(f"  [model={PRIMARY_MODEL}] OK -> {out_path} ({len(png):,} bytes)", file=sys.stderr)
            return
        except Exception as e:
            print(f"  [model={PRIMARY_MODEL}] FAILED: {e}", file=sys.stderr)
    # 回退
    print(f"  [model={FALLBACK_MODEL}] submitting...", file=sys.stderr)
    png = call_qwen_image_fallback(prompt, api_key)
    out_path.write_bytes(png)
    print(f"  [model={FALLBACK_MODEL}] OK -> {out_path} ({len(png):,} bytes)", file=sys.stderr)


def normalize_workspace_id(ws: str | None) -> str | None:
    """Strip a trailing '.cn-<region>' segment if present.

    MaaS URL template already adds `.cn-beijing.maas.aliyuncs.com`,
    so workspace IDs that already include the region (e.g. `ws-xxx.cn-beijing`)
    would otherwise produce duplicate `.cn-beijing.cn-beijing.maas.aliyuncs.com`
    hosts, triggering SSL hostname mismatch errors.
    """
    if not ws:
        return ws
    # Only strip when the trailing segment matches `.cn-<word>` to avoid
    # mangling bare IDs that just happen to contain dots.
    if re.match(r"^.*\.cn-[a-z0-9]+$", ws, flags=re.IGNORECASE):
        return ws.rsplit(".", 1)[0]
    return ws


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--project", default=".", help="project root path")
    p.add_argument("--out-dir", default=None, help="output dir (default: <project>/docs)")
    p.add_argument("--api-key", default=os.environ.get("DASHSCOPE_API_KEY"))
    p.add_argument("--workspace-id", default=os.environ.get("DASHSCOPE_WORKSPACE_ID")
                                     or os.environ.get("WORKSPACE_ID"))
    p.add_argument("--size", default="1280*1280", help="wan2.7-image size (default 1280*1280)")
    p.add_argument("--only", choices=["en", "zh", "both"], default="both")
    args = p.parse_args()

    if not args.api_key:
        sys.exit("DASHSCOPE_API_KEY not set")
    args.workspace_id = normalize_workspace_id(args.workspace_id)
    if not args.workspace_id:
        print("[WARN] DASHSCOPE_WORKSPACE_ID not set, will use qwen-image fallback",
              file=sys.stderr)

    project_root = Path(args.project).resolve()
    out_dir = Path(args.out_dir).resolve() if args.out_dir else project_root / "docs"
    out_dir.mkdir(parents=True, exist_ok=True)

    en_prompt = build_en_prompt()
    zh_prompt = build_zh_prompt()
    print(f"[INFO] en_prompt: {len(en_prompt)} chars", file=sys.stderr)
    print(f"[INFO] zh_prompt: {len(zh_prompt)} chars", file=sys.stderr)
    print(f"[INFO] workspace_id: {args.workspace_id or '(fallback)'}", file=sys.stderr)

    suffix = f"-r{os.environ.get('RUN_ID', '')}" if os.environ.get("RUN_ID") else ""

    if args.only in ("en", "both"):
        out = out_dir / "project-overview-en.png"
        if suffix:
            out = out_dir / f"project-overview-en{suffix}.png"
        print(f"[GEN] en -> {out}", file=sys.stderr)
        generate_one(en_prompt, args.api_key, args.workspace_id, out)
        print(f"[OK]  en: {out}", file=sys.stderr)

    if args.only in ("zh", "both"):
        out = out_dir / "project-overview-zh.png"
        if suffix:
            out = out_dir / f"project-overview-zh{suffix}.png"
        print(f"[GEN] zh -> {out}", file=sys.stderr)
        generate_one(zh_prompt, args.api_key, args.workspace_id, out)
        print(f"[OK]  zh: {out}", file=sys.stderr)

    print("[DONE]", file=sys.stderr)


if __name__ == "__main__":
    main()