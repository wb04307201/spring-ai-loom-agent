# 项目说明图生成 Prompt

使用 万相-图像生成与编辑2.7（wan2.7-image-pro）生成项目架构说明图。

## API 调用方式

- **Endpoint**: `POST https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`
- **Headers**: `Authorization: Bearer $DASHSCOPE_API_KEY`, `Content-Type: application/json`
- **Model**: `wan2.7-image-pro`
- **同步调用**（不使用 `X-DashScope-Async` header）

## 请求体结构

```json
{
  "model": "wan2.7-image-pro",
  "input": {
    "messages": [
      {
        "role": "user",
        "content": [{ "text": "<PROMPT>" }]
      }
    ]
  },
  "parameters": {
    "size": "2976*1408",
    "n": 1,
    "watermark": false,
    "thinking_mode": true
  }
}
```

---

## 中文版 Prompt

```
一张超高清科技风格项目信息图，深蓝黑色背景带有淡青色网格线和微妙的光束效果，无任何六边形或其他几何装饰物。所有文字必须大而清晰可读。从上到下严格按以下布局：

第一行（顶部）：大号白色加粗文字"Spring AI LoomAgent"居中，下方一行青色文字"Spring Boot AI Agent 开箱即用解决方案"。

第二行：4个等大的发光青色边框圆角矩形卡片水平并排排列，每个卡片内部有图标和大标题加小字描述。从左到右：第1个卡片"智能对话"下面小字"SSE流式输出 MCP工具编排 RAG增强"；第2个卡片"知识库"下面小字"JVector向量存储 文档解析 语义检索"；第3个卡片"MCP工具"下面小字"同步异步调用 工具发现 多服务管理"；第4个卡片"技能库"下面小字"模板管理 参数表单 技能绑定"。

第三行：6个圆角矩形卡片水平并排，文字清晰。其中"编译部署"卡片使用金橙色发光边框以突出其重要性（端到端部署流水线：git clone→打包→Docker→健康检查），其余5个使用青色边框："文件工具(16个)" "Git工具(28个)" "Maven工具(6个)" "编译部署" "时间工具" "技能工具"。

第四行：两排青色发光标签。上排技术栈："Spring Boot 3.x" "Spring AI 1.x" "JDK 17+" "H2+Flyway" "JVector" "JGit"；下排关键特性："Cookie会话认证" "JDBC ChatMemory" "REST API"。

第五行：模块架构区，3个青色方块用白色箭头水平连接，文字清晰："spring-ai-loom-agent（含file/git/maven/compile core+mcp抽取模块）"箭头指向"autoconfigure（7个嵌套配置类）"箭头指向"starter"箭头指向"test"。

第六行（底部高亮条）：一行白色文字放在青色发光背景条上，文字大而清晰："接口 + 默认实现模式 · @ConditionalOnMissingBean · 任意组件完全可替换"。

所有文字使用白色或青色，字体大而清晰。整体配色仅用深蓝黑色背景、青色蓝色调、金橙色（仅用于编译部署卡片），风格专业简洁统一。
```

## 英文版 Prompt

```
An ultra HD tech-style project infographic, dark navy-black background with subtle cyan grid lines and soft light beam effects, NO hexagonal or geometric decorations. All text must be LARGE and clearly readable. Strictly follow this layout from top to bottom:

ROW 1 (top): Large bold white text "Spring AI LoomAgent" centered, below it a line of cyan text "Spring Boot AI Agent — Out-of-the-Box Solution".

ROW 2: 4 equal-sized glowing cyan-bordered rounded-rectangle cards arranged horizontally side by side, each card has an icon and large title plus small description text. Left to right: Card 1 "Chat UI" with small text "SSE Streaming MCP Orchestration RAG Augmentation"; Card 2 "Knowledge Base" with "JVector Store Document Parsing Semantic Search"; Card 3 "MCP Tools" with "Sync/Async Calls Tool Discovery Multi-Service"; Card 4 "Skill Library" with "Templates Parameter Forms Skill Binding".

ROW 3: 6 rounded-rectangle cards arranged horizontally, text clearly legible. The "Compile & Deploy" card uses a gold-amber glowing border to highlight its importance (end-to-end pipeline: git clone → build → Docker → health check), the other 5 use cyan borders: "File Tools(16)" "Git Tools(28)" "Maven Tools(6)" "Compile & Deploy" "Time Tools" "Skill Tools".

ROW 4: Two rows of cyan glowing badges. Top row tech stack: "Spring Boot 3.x" "Spring AI 1.x" "JDK 17+" "H2+Flyway" "JVector" "JGit"; Bottom row key features: "Cookie Session Auth" "JDBC ChatMemory" "REST API".

ROW 5: Module architecture, 4 cyan boxes connected horizontally with white arrows, text clear: "spring-ai-loom-agent (includes file/git/maven/compile core+mcp extracted modules)" arrow to "autoconfigure (7 nested config classes)" arrow to "starter" arrow to "test".

ROW 6 (bottom highlight bar): A line of large white text on a cyan glowing background bar, text large and clear: "Interface + Default Implementation · @ConditionalOnMissingBean · Every Component Fully Replaceable".

All text in white or cyan, large and clear fonts. Color scheme uses ONLY dark navy-black background, cyan-blue accents, and gold-amber (for the Compile & Deploy card only). Professional, clean, unified style.
```

---

## 生成结果

| 图片 | 文件 | 分辨率 |
|------|------|--------|
| 中文版 | `docs/project-overview-zh.png` | 2976×1408 |
| 英文版 | `docs/project-overview-en.png` | 2976×1408 |

## 关键经验

1. **API 格式**：wan2.7-image-pro 使用 `input.messages` 数组格式，不是 `input.prompt`
2. **同步调用**：该 API Key 不支持异步调用（`X-DashScope-Async`），需使用同步模式
3. **布局一致性**：中英文 prompt 需严格遵循相同的布局描述，仅替换文字内容
4. **文字可读性**：明确强调"所有文字必须大而清晰可读"，否则小字会模糊
5. **风格统一**：明确排除多余装饰物（六边形等），指定仅用深蓝+青色配色方案
6. **图片尺寸**：使用 `2976*1408` 宽屏比例，适合展示多列布局
