# Spring AI LoomAgent —— 灵梭

<div align="right">
  <a href="README.md">English</a> | 中文
</div>

> Spring Boot 自动配置库，为 Spring AI 应用一键注入 RAG 知识库、MCP 工具调用和 Skill 技能库，开箱即用的聊天 UI。

![Maven Central](https://img.shields.io/maven-central/v/io.github.wb04307201/spring-ai-loom-agent-spring-boot-starter?style=flat-square)
[![star](https://gitee.com/wb04307201/spring-ai-loom-agent/badge/star.svg?theme=dark)](https://gitee.com/wb04307201/spring-ai-loom-agent)
[![fork](https://gitee.com/wb04307201/spring-ai-loom-agent/badge/fork.svg?theme=dark)](https://gitee.com/wb04307201/spring-ai-loom-agent)
[![star](https://img.shields.io/github/stars/wb04307201/spring-ai-loom-agent)](https://github.com/wb04307201/spring-ai-loom-agent)
[![fork](https://img.shields.io/github/forks/wb04307201/spring-ai-loom-agent)](https://github.com/wb04307201/spring-ai-loom-agent)  
![License](https://img.shields.io/badge/License-Apache2.0-blue.svg) ![JDK](https://img.shields.io/badge/JDK-17+-green.svg) ![SpringBoot](https://img.shields.io/badge/Spring%20Boot-3+-green.svg)![SpringAI](https://img.shields.io/badge/Spring%20AI-1+-green.svg)

---

## 功能特性
- **对话交互** — SSE 流式聊天，多轮对话，模型推理过程折叠展示，消息复制/下载
- **RAG 知识库** — 多知识库管理，Tika 文档解析 + 向量化，可选 LLM 元数据增强，JVector 本地向量存储
- **MCP 服务集成** — 同步/异步双模式，运行时按会话启用/禁用
- **Skill 技能库** — 参数化模板 + MCP 工具绑定，LLM 自主发现与调用，运行时动态管理
- **文件管理** — 磁盘存储 + H2 元数据，多模态聊天（图片 Media + 文档文本混合），文件下载，预览
- **前端 UI** — 侧边栏对话历史，图片/文档 `+` 按钮上传与缩略图预览，响应式布局
- **工程化** — Spring Boot 自动配置（全组件可替换），Flyway 迁移，广泛支持多种聊天/嵌入/向量存储后端


## 快速添加聊天界面
### 1. 引入聊天依赖
```xml
<dependency>
    <groupId>io.github.wb04307201</groupId>
    <artifactId>spring-ai-loom-agent-spring-boot-starter</artifactId>
    <version>1.1.23</version>
</dependency>
```

### 2. 添加Spring AI模型依赖
下面以阿里qwen大模型为例进行说明，可以按需替换成其它大语言模型依赖与配置：
```xml
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
    <version>1.1.2.2</version>
</dependency>
```
```yaml
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
    chat:
      options:
        model: qwen3.6-plus
        multi_model: true
        enable_thinking: true
    embedding:
      options:
        model: text-embedding-v2
```

> [使用其他模型可参考](https://docs.spring.io/spring-ai/reference/api/chatmodel.html)

> **注意**: 如需基于文档进行问答，请确保模型支持多模态输入（如 `multi_model: true`），文档内容会通过 System Prompt 注入。

### 3. 启动项目
访问`http://localhost:8080/spring/ai/loom`
![img.png](img.png)
![img_5.png](img_5.png)
![img_1.png](img_1.png)
![img_2.png](img_2.png)

## 文档上传与对话
点击输入框左侧 `+` 按钮，可上传图片或文档文件。上传后在输入框中输入问题发送即可。

### 支持的文档格式
PDF、DOCX、XLSX、PPTX、MD、TXT、HTML、CSV、RTF 等。

### 工作原理
1. **图片**: 作为 Media 类型直接传递给多模态大模型（需模型支持，如 DashScope qwen 系列）
2. **文档**: 通过 Apache Tika 提取文本内容，作为 System Prompt 注入对话上下文
3. **混合场景**: 可同时上传图片和文档，模型会综合图片视觉信息与文档文本内容进行回答

### 文件下载和预览
上传和生成的文件可通过 MCP 工具 `downloadFileUrl` 获取下载链接，也可以通过 MCP 工具 `viewFileUrl` 获取预览链接。

## 更换其它RAG以替换默认实现
下面以qdrant向量数据库为例，添加依赖和配置：
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-qdrant</artifactId>
</dependency>
```

添加配置：
```yaml
spring:
  ai:
    vectorstore:
      qdrant:
        host: localhost
        port: 6334
        collection-name: qwen-collection-name
```

其它rag可选配置如下：
```yaml
spring:
  ai:
    loom:
      agent:
        rag:
          similarityThreshold: 0.50   # 相似度阈值,默认0.0
          top-k: 4                    # top-k，默认4
          defaultPromptTemplate: |
            Context information is below.

            ---------------------
            {context}
            ---------------------

            Given the context information and no prior knowledge, answer the query.

            Follow these rules:

            1. If the answer is not in the context, just say that you don't know.
            2. Avoid statements like "Based on the context..." or "The provided information...".

            Query: {query}

            Answer:
          defaultEmptyContextPromptTemplate: |
            The user query is outside your knowledge base.
            Politely inform the user that you can't answer it.
```

## MCP服务
以时间MCP服务为例，添加依赖：
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

添加配置：
```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          servers-configuration: classpath:mcp-servers.json
```

mcp-servers.json:
```json
{
  "mcpServers": {
    "time": {
      "command": "uvx",
      "args": [
        "mcp-server-time",
        "--local-timezone=Asia/Shanghai"
      ]
    }
  }
}
```

配置MCP服务后，工具栏出现MCP服务按钮，点开后可查看目前拥有的MCP服务信息：
![img_3.png](img_3.png)

可以通过配置为工具添加中文名和描述：
```yaml
spring:
  ai:
    loom:
      agent:
        mcps:
          - name: spring-ai-mcp-client - time
            title: 时间
            description:
              一个提供时间和时区转换功能的模型上下文协议服务。该服务使大型语言模型能够获取当前时间信息，并使用IANA时区名称进行时区转换，同时具备自动检测系统时区的功能。
            tools:
              - name: get_current_time
                description: 获取指定时区的当前时间
              - name: convert_time
                description: 在不同时区之间转换时间
```

## 技能库
可以编写技能加入技能库，技能可以配置参数与使用的工具，配置说明如下：
```yaml
spring:
  ai:
    loom:
      agent:
        skills:
          - name: 网络月度事件报告
            description: 通过网络搜索采集指定主题的月度事件，通过深度分析生成月度事件洞察报告，适用于企业情报监控、行业趋势追踪等场景
            tools:
              - spring-ai-mcp-client - time
              - spring-ai-mcp-client - sequential-thinking
              - spring-ai-mcp-client - bing-search
              - spring-ai-mcp-client - http-mcp
            content: classpath:skills/news-watch.st
            params:
              - name: param1
                label: 主题
                type: text
                required: true
                default-value: 党
```

```text
通过网络搜索获取{param1}当前年每月的重要的事件，通过深度分析生成洞察报告，要求：
- 使用 @get_current_time 获取当前时间
- 使用 @sequentialthinking 来规划所有的步骤，思考和分支
- 可以使用 @bing_search 按照当前年逐月进行一汽的重要的事件搜索，每一轮Thinking之前都先搜索验证
- 可以用 @crawl_webpage 来查看搜索到的网页详情
- 思考轮数不低于5轮，且需要有发散脑暴意识，需要有思考分支
- 每一轮需要根据查询的信息结果，反思自己的决策是否正确
- 进行事件关联分析与结论形成 网络月度事件报告
```

可以通过技能库按钮精准使用技能 ，
技能默认设置了预加载，也通过对话直接使用

![img_4.png](img_4.png)


---

- 其他配置和扩展点说明:[Spring AI LoomAgent 自定义能力总览](CUSTOMIZATION.zh-CN.md)
- 自定义UI界面对接API参考:[Spring AI LoomAgent API 文档](API.zh-CN.md)

