---
name: project-overview-image
description: 基于项目代码 + README + CLAUDE.md 生成中英文 2 张项目概览图。当前 DashScope 文生图模型在多文字 + 信息图场景下文字易错位（详见 README.md），已回退 docs/project-overview-{en,zh}.png 到 mermaid 版本作为 baseline；skill 框架已搭好（generate.py + check.py + 异步 + 重试 + 文本检查），等更好模型可一键启用。
---

# project-overview-image

根据最新的项目代码 / README / CLAUDE.md，自动生成中英文 2 张项目概览图（PNG）覆盖 `docs/project-overview-en.png` 和 `docs/project-overview-zh.png`。

## 流程

1. **抽取项目信息**
   - 读 `README.md` / `CLAUDE.md` / 模块结构（spring-ai-loom-agent 下的 `*Configuration.java` 类列表）
   - 抓取关键信息：
     - 项目名：Spring AI LoomAgent
     - 一句话定位：Spring Boot AI Agent · Out-of-the-Box Solution
     - 核心 4 大功能：Chat UI / Knowledge Base / MCP Tools / Skill Library + Market
     - RBAC + 用户类型
     - Admin 控制台（sidebar 5 区块）
     - 6 个工具组（File 16 / Git 28 / Maven 6 / Compile & Deploy / Time / Skill）
     - 文件管理
     - 数据层（H2 + Flyway 双版本 V1.0 / V1.1）
     - 底层栈（Spring Boot 3.x / Spring AI 1.x / JDK 17+ / JVector / JGit）
     - 支撑能力（Cookie Session Auth / JDBC ChatMemory / REST API）

2. **构建 prompt（en + zh 双版本）**
   - 中英除保留专业名（`Spring AI LoomAgent` / `JVector` / `JGit` / `Maven` / `Tika` / `SSE` / `REST API` / `RBAC` / `Flyway` / `ChatMemory` 等）外，UI 文案 / 描述 / 标签均用对应语言
   - 明确要求：**不要出现版本号、日期、年份等元数据**
   - 风格：现代信息图（深色背景 / 霓虹色块 / 卡片式布局），中英风格**完全一致**

3. **调用阿里云百炼 万相 API 生成图**
   - 模型：`wanx2.1-t2i-turbo`（基础）或 `qwen-image`（更好支持中英文）— 用 `qwen-image`
   - 端点：`https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-generation`
   - Header: `Authorization: Bearer ${DASHSCOPE_API_KEY}`（环境变量）
   - 请求：
     ```json
     {
       "model": "qwen-image",
       "input": {"prompt": "..."},
       "parameters": {"size": "1328*1328", "n": 1}
     }
     ```
   - 输出：URL → curl 下载 → 保存

4. **检查图片（必须通过才能结束，否则重试）**
   - **单图内容检查**：
     - 包含关键信息（项目名、核心 4 大功能、6 工具组、RBAC / Admin / 数据层 等关键词）
     - 无版本号 / 日期 / 年份 / 时间戳
     - 中文版含中文字符、英文版含英文词
   - **跨图一致性**：
     - 区块数量和顺序一致（8-10 个主要区块）
     - 风格（颜色 / 布局 / 字体大小）一致
     - 同一关键术语在两图中都出现
   - 用 `Read` 工具 + `mcp__MiniMax__understand_image` 视觉分析
   - 失败 → 重新生成（最多 3 次），每次调整 prompt

## 文件结构

```
project-overview-image/
├── SKILL.md                       # 本文件
├── scripts/
│   ├── generate.py                # 调万相 API 生成 2 张图
│   └── check.py                   # 单图 + 跨图内容/风格检查
├── examples/                       # 输出样例（可选）
└── README.md                      # 用户文档
```

## 使用方式

skill 触发（任意一种）：

- "更新项目概览图"
- "刷新 README 顶部的 overview 图"
- "生成 docs/project-overview-{en,zh}.png"
- "用万相生成 Spring AI LoomAgent 的项目概览"

执行流程：

1. skill 加载 → 读 README.md / CLAUDE.md / module 结构
2. 调 `python scripts/generate.py` 生成 2 张 PNG
3. 调 `python scripts/check.py` 验证
4. 检查失败 → 调 generate.py 重试（最多 3 次，prompt 微调）
5. 通过 → 输出成功

## 依赖

- 环境变量 `DASHSCOPE_API_KEY`（阿里云百炼 API key）
- Python 3.x + `requests`（已预装）
- `mcp__MiniMax__understand_image` 工具（用于视觉分析）

## 检查清单

- [ ] 中英双图都已生成
- [ ] 包含项目名 `Spring AI LoomAgent`
- [ ] 包含核心 4 大功能 + 6 工具组
- [ ] 包含 RBAC / Admin 控制台 / 数据层
- [ ] **无版本号 / 日期 / 年份**（生成 prompt 明确禁止）
- [ ] 中文版主要中文 + 英文版主要英文
- [ ] 风格一致（颜色 / 布局 / 字体）
- [ ] 区块数量一致
- [ ] 两图大小一致
