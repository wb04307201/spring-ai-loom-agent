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
Dark navy infographic poster, square. Flat geometric style.

Colors: deep navy background, neon cyan accents, warm orange highlight on one element. White text.

Top center of the poster has two text lines only:
  Big white bold: Spring AI LoomAgent
  Smaller cyan: Spring Boot AI Agent Out-of-the-Box Solution

Below the title is one horizontal row of four plain rounded rectangle cards, each containing an icon and a single label. All four cards in this row use cyan. Labels from left to right: Chat, Knowledge, MCP, Skill.

Below that is one horizontal row of six plain rounded rectangle cards, each with an icon and a label. The card labelled Deploy (which is the fourth card from the left) is the ONLY card with orange glow and orange fill. All other five cards in this row use cyan. Labels from left to right in this exact order, all six must be different: Files, Git, Maven, Deploy, Time, Skill.

Below that on the left side is one row of five small chip rectangles in this order: Users, Roles, MCP, Market, Sessions. On the same row to the right with a gap are two pill rectangles: an orange filled pill labelled Admin and a cyan filled pill labelled User. No symbol between the chips and the pills.

Below that is one horizontal row of seven pill labels in this exact order: Spring Boot, Spring AI, JDK 17, JVector, JGit, Flyway, ChatMemory. The word JVector must be a single seven-letter word that starts with capital J and capital V, followed by lower case e-c-t-o-r. The pill JDK 17 contains the three letters J-D-K followed by the number 17. The whole row must fit in one line without wrapping.

Below that is one horizontal row of four plain rounded rectangle boxes connected by right-pointing arrows. Each box contains exactly one short word. From left to right: core, config, starter, test.

At the very bottom is one centered text line: Interface. Default. Replaceable.
"""

ZH_LAYOUT = """\
深蓝色信息图海报，正方形。扁平几何风格。

颜色：深蓝色背景，霓虹青色点缀，暖橙色用于一处高亮。白色文字。

海报顶部居中只有两行文字：
  大号白色粗体：Spring AI LoomAgent
  较小青色：Spring Boot AI Agent 开箱即用方案

标题下方是一行四张普通圆角矩形卡片，每张卡片包含一个图标和一个单标签。从左到右标签为：对话、知识库、MCP、技能。

其下方是一行六张普通圆角矩形卡片，每张卡片有一个图标和一个标签。标着"部署"的那张（即从左数第四张）是这一行中唯一有橙色光晕和橙色填充的卡片。其他五张都用青色。从左到右依次为：文件、Git、Maven、部署、时间、技能。这六个标签必须完全不同，不能有重复。

其下方左侧是一行五个小芯片矩形，依次为：用户、角色、MCP、市场、会话。同一行右侧有间隔距离，是两个胶囊矩形：橙色填充胶囊标签"管理员"，青色填充胶囊标签"普通用户"。芯片和胶囊之间没有符号。

其下方是一行七个胶囊标签，依次为：Spring Boot、Spring AI、JDK 17、JVector、JGit、Flyway、ChatMemory。JVector 是一个由七个字母组成的单词，以大写 J 和大写 V 开头，后接小写 e、c、t、o、r。JDK 17 由三个字母 J-D-K 加数字 17 组成。整行必须在一行内显示，不换行。

其下方是一行四个普通圆角矩形方框，用向右箭头连接。每个方框内仅包含一个短词。从左到右：核心、配置、启动、测试。

最底部是一行居中文字：接口 默认 可替换。
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


def generate_one(lang: str, prompt: str, api_key: str, workspace_id: str, out_path: Path) -> None:
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
        generate_one("en", en_prompt, args.api_key, args.workspace_id, out)
        print(f"[OK]  en: {out}", file=sys.stderr)

    if args.only in ("zh", "both"):
        out = out_dir / "project-overview-zh.png"
        if suffix:
            out = out_dir / f"project-overview-zh{suffix}.png"
        print(f"[GEN] zh -> {out}", file=sys.stderr)
        generate_one("zh", zh_prompt, args.api_key, args.workspace_id, out)
        print(f"[OK]  zh: {out}", file=sys.stderr)

    print("[DONE]", file=sys.stderr)


if __name__ == "__main__":
    main()