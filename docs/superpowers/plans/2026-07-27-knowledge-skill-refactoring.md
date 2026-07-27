# 知识空间工作模式重构 + 技能工具分页实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 RAG 从固定检索改为按需 tool calling，技能工具改为分页模式，统一"摘要注入 + 按需深入"的交互模式。

**Architecture:** 
1. 系统每轮对话注入技能/知识库摘要（名称+描述）到 system prompt
2. LLM 按需调用 `getSkill(name)` / `searchKnowledge(knowledgeId, query)` 获取详情
3. 技能/知识库目录支持分页查询（默认 20 条/页）
4. 移除旧的 `RetrievalAugmentationAdvisor` 和固定 RAG 模式

**Tech Stack:** Spring Boot 3.x, Spring AI 1.x, JVector, H2, Vue.js (前端)

## Global Constraints

- JDK: 17+
- Spring Boot: 3.x
- Spring AI: 1.x
- 数据库迁移：新增 Flyway 脚本（V2.1__knowledge_description.sql）
- 分页默认 size: 20
- 知识库描述：前端必填
- 向后兼容：不保留旧 `skillContents()` 方法

---

## File Structure

### 数据库层
- `spring-ai-loom-agent/src/main/resources/db/migration/V2.1__knowledge_description.sql` - 新增 knowledge.description 字段、user_conversation.enabled_knowledge_ids 字段

### 后端模型层
- `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/KnowledgeRecord.java` - 增加 description 字段
- `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentProperties.java` - 更新 RagProperty（删除 promptTemplate 相关字段）

### 后端接口层
- `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/knowledge/IKnowledge.java` - insert 方法增加 description 参数
- `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/knowledge/DefaultKnowledge.java` - 实现新 insert 方法
- `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/skill/ISkillTool.java` - 删除 skillContents()，新增 listSkills(page, size)
- `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/skill/DefaultSkillTool.java` - 实现 listSkills 分页逻辑
- `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/knowledge/IKnowledgeTool.java` - 新建接口
- `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/knowledge/DefaultKnowledgeTool.java` - 新建实现

### 自动配置层
- `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java` - 移除 RetrievalAugmentationAdvisor，注册 IKnowledgeTool bean，更新 DefaultChat 依赖

### Chat 层
- `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChat.java` - 动态注入技能/知识库摘要到 system prompt，移除 RAG advisor 逻辑

### 前端层
- `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js` - 知识库管理 UI 改为多选，创建时增加描述输入，移除 enableRag 开关
- `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/index.html` - 更新知识库管理模态框 HTML

---

## Task 1: 数据库迁移 - 增加知识库描述字段

**Files:**
- Create: `spring-ai-loom-agent/src/main/resources/db/migration/V2.1__knowledge_description.sql`

**Interfaces:**
- Consumes: 现有 knowledge 表、user_conversation 表
- Produces: knowledge 表增加 description 列，user_conversation 表增加 enabled_knowledge_ids 列

- [ ] **Step 1: 创建数据库迁移脚本**

```sql
-- V2.1__knowledge_description.sql

-- 知识库增加描述字段
ALTER TABLE knowledge ADD COLUMN description VARCHAR(500) NULL;

-- 对话增加启用知识库ID列表（JSON格式存储）
ALTER TABLE user_conversation ADD COLUMN enabled_knowledge_ids VARCHAR(1000) NULL;
```

- [ ] **Step 2: 验证迁移脚本**

Run: `mvn clean install -Dgpg.skip=true -pl spring-ai-loom-agent-test && mvn spring-boot:run -pl spring-ai-loom-agent-test`

Expected: 应用启动成功，Flyway 执行迁移，`flyway_schema_history` 表记录 V2.1

- [ ] **Step 3: 提交**

```bash
git add spring-ai-loom-agent/src/main/resources/db/migration/V2.1__knowledge_description.sql
git commit -m "feat(db): add knowledge description and conversation enabled_knowledge_ids"
```

---

## Task 2: 后端模型 - KnowledgeRecord 增加 description

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/KnowledgeRecord.java`

**Interfaces:**
- Consumes: V2.1 数据库迁移
- Produces: `KnowledgeRecord(String id, String username, String name, String description)`

- [ ] **Step 1: 更新 KnowledgeRecord**

```java
package cn.wubo.spring.ai.loom.agent.model;

public record KnowledgeRecord(
    String id, 
    String username, 
    String name, 
    String description
) {}
```

- [ ] **Step 2: 提交**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/KnowledgeRecord.java
git commit -m "feat(model): add description to KnowledgeRecord"
```

---

## Task 3: 后端接口 - IKnowledge 和 DefaultKnowledge 更新

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/knowledge/IKnowledge.java`
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/knowledge/DefaultKnowledge.java`

**Interfaces:**
- Consumes: KnowledgeRecord(id, username, name, description)
- Produces: `IKnowledge.insert(name, description)` 方法签名

- [ ] **Step 1: 更新 IKnowledge 接口**

```java
package cn.wubo.spring.ai.loom.agent.knowledge;

import cn.wubo.spring.ai.loom.agent.model.KnowledgeRecord;
import java.util.List;

public interface IKnowledge {
    List<KnowledgeRecord> list();
    KnowledgeRecord insert(String name, String description);
    int delete(String id);
}
```

- [ ] **Step 2: 更新 DefaultKnowledge 实现**

```java
@Override
public KnowledgeRecord insert(String name, String description) {
    // 检查重名
    String checkSql = "SELECT COUNT(*) FROM knowledge WHERE username = ? AND name = ?";
    Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, UserContextHolder.get(), name);
    if (count != null && count > 0) {
        throw new LoomAgentRuntimeException(409, "知识库名称已存在");
    }
    
    // 插入新知识库
    String id = UUID.randomUUID().toString();
    String insertSql = "INSERT INTO knowledge (id, username, name, description) VALUES (?, ?, ?, ?)";
    jdbcTemplate.update(insertSql, id, UserContextHolder.get(), name, description);
    
    return new KnowledgeRecord(id, UserContextHolder.get(), name, description);
}
```

- [ ] **Step 3: 提交**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/knowledge/
git commit -m "feat(knowledge): update IKnowledge.insert to accept description"
```

---

## Task 4: 后端工具 - ISkillTool 分页重构

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/skill/ISkillTool.java`
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/skill/DefaultSkillTool.java`

**Interfaces:**
- Consumes: ISkillStorage 接口
- Produces: `listSkills(page, size)` 分页方法，删除 `skillContents()`

- [ ] **Step 1: 更新 ISkillTool 接口**

```java
package cn.wubo.spring.ai.loom.agent.tool.skill;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.ToolContext;

public interface ISkillTool extends IEmbedTool {
    
    @Tool(description = "分页列出所有可用的技能，包含技能名和描述。默认每页20条。")
    String listSkills(
        @ToolParam(description = "页码，从1开始") Integer page,
        @ToolParam(description = "每页数量，-1表示全部") Integer size,
        ToolContext toolContext
    );
    
    @Tool(description = "根据技能名称获取详细的技能信息，包含技能名称、描述和完整内容。")
    String getSkill(
        @ToolParam(description = "技能名") String name, 
        ToolContext toolContext
    );
}
```

- [ ] **Step 2: 更新 DefaultSkillTool 实现**

```java
@Override
public String listSkills(Integer page, Integer size, ToolContext toolContext) {
    String username = (String) toolContext.getContext().get("username");
    List<SkillRecord> allSkills = skillStorage.list(username).stream()
        .filter(SkillRecord::load)
        .toList();
    
    int total = allSkills.size();
    int pageSize = (size == null || size <= 0) ? 20 : size;
    int currentPage = (page == null || page < 1) ? 1 : page;
    
    List<SkillRecord> pageSkills;
    int totalPages;
    
    if (pageSize == -1) {
        // 返回全部
        pageSkills = allSkills;
        totalPages = 1;
        currentPage = 1;
    } else {
        // 分页
        totalPages = (int) Math.ceil((double) total / pageSize);
        int fromIndex = (currentPage - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, total);
        pageSkills = (fromIndex < total) ? allSkills.subList(fromIndex, toIndex) : List.of();
    }
    
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("技能目录（共 %d 个，第 %d/%d 页）:%n%n", total, currentPage, totalPages));
    sb.append(String.format("%-20s %-50s%n", "技能名", "技能描述"));
    sb.append("-".repeat(70)).append("\n");
    
    for (SkillRecord skill : pageSkills) {
        sb.append(String.format("%-20s %-50s%n", skill.name(), skill.description()));
    }
    
    if (totalPages > 1 && pageSize != -1) {
        sb.append(String.format("%n提示：共 %d 页，调用 @listSkills {\"page\": %d} 查看下一页，或 @listSkills {\"size\": -1} 查看全部", 
            totalPages, currentPage + 1));
    }
    
    return sb.toString();
}
```

- [ ] **Step 3: 提交**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/skill/
git commit -m "feat(skill): refactor to paginated listSkills, remove skillContents"
```

---

## Task 5: 后端工具 - 新建 IKnowledgeTool

**Files:**
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/knowledge/IKnowledgeTool.java`
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/knowledge/DefaultKnowledgeTool.java`

**Interfaces:**
- Consumes: VectorStore, IKnowledge, LoomAgentProperties.RagProperty
- Produces: `listKnowledgeBases(page, size)` 和 `searchKnowledge(knowledgeId, query, topK?)` 方法

- [ ] **Step 1: 创建 IKnowledgeTool 接口**

```java
package cn.wubo.spring.ai.loom.agent.tool.knowledge;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.ToolContext;

public interface IKnowledgeTool extends IEmbedTool {
    
    @Tool(description = "分页列出当前用户启用的知识库，包含知识库名称和描述。默认每页20条。")
    String listKnowledgeBases(
        @ToolParam(description = "页码，从1开始") Integer page,
        @ToolParam(description = "每页数量，-1表示全部") Integer size,
        ToolContext toolContext
    );
    
    @Tool(description = "在指定知识库中检索相关文档片段。当用户的问题可能涉及知识库中的内容时调用此工具。")
    String searchKnowledge(
        @ToolParam(description = "知识库ID") String knowledgeId,
        @ToolParam(description = "检索查询关键词") String query,
        @ToolParam(description = "返回结果数量，不传则使用全局默认值") Integer topK,
        ToolContext toolContext
    );
}
```

- [ ] **Step 2: 创建 DefaultKnowledgeTool 实现**

```java
package cn.wubo.spring.ai.loom.agent.tool.knowledge;

import cn.wubo.spring.ai.loom.agent.knowledge.IKnowledge;
import cn.wubo.spring.ai.loom.agent.model.KnowledgeRecord;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.ToolContext;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

public class DefaultKnowledgeTool implements IKnowledgeTool {
    
    private final IKnowledge knowledge;
    private final VectorStore vectorStore;
    private final LoomAgentProperties.RagProperty ragProperty;
    
    public DefaultKnowledgeTool(IKnowledge knowledge, VectorStore vectorStore, 
                                LoomAgentProperties.RagProperty ragProperty) {
        this.knowledge = knowledge;
        this.vectorStore = vectorStore;
        this.ragProperty = ragProperty;
    }
    
    @Override
    public String listKnowledgeBases(Integer page, Integer size, ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        List<KnowledgeRecord> allKb = knowledge.list(); // 已经按 username 过滤
        
        int total = allKb.size();
        int pageSize = (size == null || size <= 0) ? 20 : size;
        int currentPage = (page == null || page < 1) ? 1 : page;
        
        List<KnowledgeRecord> pageKb;
        int totalPages;
        
        if (pageSize == -1) {
            pageKb = allKb;
            totalPages = 1;
            currentPage = 1;
        } else {
            totalPages = (int) Math.ceil((double) total / pageSize);
            int fromIndex = (currentPage - 1) * pageSize;
            int toIndex = Math.min(fromIndex + pageSize, total);
            pageKb = (fromIndex < total) ? allKb.subList(fromIndex, toIndex) : List.of();
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("知识库目录（共 %d 个，第 %d/%d 页）:%n%n", total, currentPage, totalPages));
        sb.append(String.format("%-20s %-50s%n", "知识库名称", "知识库描述"));
        sb.append("-".repeat(70)).append("\n");
        
        for (KnowledgeRecord kb : pageKb) {
            sb.append(String.format("%-20s %-50s%n", kb.name(), kb.description()));
        }
        
        if (totalPages > 1 && pageSize != -1) {
            sb.append(String.format("%n提示：共 %d 页，调用 @listKnowledgeBases {\"page\": %d} 查看下一页", 
                totalPages, currentPage + 1));
        }
        
        return sb.toString();
    }
    
    @Override
    public String searchKnowledge(String knowledgeId, String query, Integer topK, ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        int k = (topK == null || topK <= 0) ? ragProperty.getTopK() : topK;
        
        // 构建搜索请求
        SearchRequest searchRequest = SearchRequest.builder()
            .query(query)
            .topK(k)
            .similarityThreshold(ragProperty.getSimilarityThreshold())
            .build();
        
        // 执行搜索，使用 SpEL 过滤
        List<Document> results = vectorStore.similaritySearch(searchRequest);
        
        // 过滤：只返回当前知识库的结果
        results = results.stream()
            .filter(doc -> {
                String docKnowledgeId = (String) doc.getMetadata().get("knowledgeId");
                String docUsername = (String) doc.getMetadata().get("username");
                return knowledgeId.equals(docKnowledgeId) && username.equals(docUsername);
            })
            .limit(k)
            .toList();
        
        if (results.isEmpty()) {
            return String.format("知识库 [%s] 中未找到与 \"%s\" 相关的内容。", knowledgeId, query);
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("知识库检索结果（共 %d 条）:%n%n", results.size()));
        
        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            sb.append(String.format("【片段 %d】%n%s%n%n", i + 1, doc.getText()));
        }
        
        return sb.toString();
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/knowledge/
git commit -m "feat(tool): add IKnowledgeTool with listKnowledgeBases and searchKnowledge"
```

---

## Task 6: 自动配置 - 注册 IKnowledgeTool，移除 RAG Advisor

**Files:**
- Modify: `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java`

**Interfaces:**
- Consumes: IKnowledgeTool 接口、IKnowledge、VectorStore、LoomAgentProperties
- Produces: IKnowledgeTool bean，移除 RetrievalAugmentationAdvisor bean

- [ ] **Step 1: 移除 RetrievalAugmentationAdvisor bean**

在 `RagConfiguration` 内部类中，删除或注释掉 `retrievalAugmentationAdvisor` 方法。

- [ ] **Step 2: 在 ToolConfiguration 中注册 IKnowledgeTool**

```java
@ConditionalOnProperty(name = "spring.ai.loom.agent.knowledge.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnMissingBean(IKnowledgeTool.class)
@ConditionalOnBean(VectorStore.class)
@Bean
public IKnowledgeTool defaultKnowledgeTool(IKnowledge knowledge, VectorStore vectorStore, 
                                           LoomAgentProperties properties) {
    return new DefaultKnowledgeTool(knowledge, vectorStore, properties.getRag());
}
```

- [ ] **Step 3: 提交**

```bash
git add spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java
git commit -m "feat(config): register IKnowledgeTool, remove RetrievalAugmentationAdvisor"
```

---

## Task 7: Chat 层 - 动态注入技能/知识库摘要

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChat.java`

**Interfaces:**
- Consumes: ISkillStorage, IKnowledge, LoomAgentProperties.defaultSystem
- Produces: 每轮对话动态拼接 system prompt（包含技能/知识库摘要）

- [ ] **Step 1: 在 DefaultChat 中注入依赖**

```java
private final ISkillStorage skillStorage;
private final IKnowledge knowledge;

public DefaultChat(..., ISkillStorage skillStorage, IKnowledge knowledge, ...) {
    // ... existing code
    this.skillStorage = skillStorage;
    this.knowledge = knowledge;
}
```

- [ ] **Step 2: 在 stream() 方法中动态构建 system prompt**

```java
@Override
public Flux<String> stream(ChatRequestRecord chatRequestRecord, String username, 
                           ServerSentEvent<String> event) {
    // 构建动态 system prompt
    String dynamicSystemPrompt = buildDynamicSystemPrompt(username);
    
    // ... existing code ...
    
    requestSpec.system(dynamicSystemPrompt);
    
    // ... rest of the code ...
}

private String buildDynamicSystemPrompt(String username) {
    StringBuilder sb = new StringBuilder();
    
    // 基础 system prompt
    sb.append(properties.getDefaultSystem()).append("\n\n");
    
    // 注入技能摘要（前 20 条）
    List<SkillRecord> skills = skillStorage.list(username).stream()
        .filter(SkillRecord::load)
        .limit(20)
        .toList();
    
    if (!skills.isEmpty()) {
        sb.append("【技能】（共 ").append(skillStorage.list(username).size()).append(" 个");
        if (skills.size() < skillStorage.list(username).size()) {
            sb.append("，显示前 ").append(skills.size()).append(" 个");
        }
        sb.append("）\n");
        
        for (SkillRecord skill : skills) {
            sb.append("• ").append(skill.name()).append(" - ").append(skill.description()).append("\n");
        }
        sb.append("\n");
    }
    
    // 注入知识库摘要（前 20 条）
    List<KnowledgeRecord> knowledgeBases = knowledge.list();
    if (!knowledgeBases.isEmpty()) {
        sb.append("【知识库】（共 ").append(knowledgeBases.size()).append(" 个");
        if (knowledgeBases.size() > 20) {
            sb.append("，显示前 20 个");
        }
        sb.append("）\n");
        
        knowledgeBases.stream().limit(20).forEach(kb -> {
            sb.append("• ").append(kb.name()).append(" - ").append(kb.description()).append("\n");
        });
        sb.append("\n");
    }
    
    return sb.toString();
}
```

- [ ] **Step 3: 移除 RAG advisor 相关代码**

删除 `if (retrievalAugmentationAdvisor.isPresent() && StringUtils.hasText(chatRequestRecord.knowledgeId()))` 相关逻辑。

- [ ] **Step 4: 提交**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChat.java
git commit -m "feat(chat): inject skill/knowledge summary into system prompt dynamically"
```

---

## Task 8: 配置属性 - 更新 RagProperty

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentProperties.java`

**Interfaces:**
- Consumes: 现有 RagProperty 配置
- Produces: 保留 similarityThreshold + topK，删除 promptTemplate 相关字段

- [ ] **Step 1: 更新 RagProperty 内部类**

```java
public static class RagProperty {
    private double similarityThreshold = 0.0F;
    private int topK = 4;
    
    // 删除以下字段：
    // private String defaultPromptTemplate;
    // private String defaultEmptyContextPromptTemplate;
    // private boolean enabledKeyword;
    // private boolean enabledSummary;
    
    // getters and setters...
}
```

- [ ] **Step 2: 提交**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentProperties.java
git commit -m "refactor(config): simplify RagProperty, remove prompt templates"
```

---

## Task 9: 前端 - 知识库管理 UI 改造

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js`
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/index.html`

**Interfaces:**
- Consumes: 后端 API `/spring/ai/loom/knowledge`
- Produces: 知识库创建时输入描述，列表显示描述

- [ ] **Step 1: 更新 app.js - knowledge.create() 方法**

```javascript
async create() {
    const name = prompt('请输入知识库名称：');
    if (!name) return;
    
    const description = prompt('请输入知识库描述（必填）：');
    if (!description) {
        alert('知识库描述不能为空');
        return;
    }
    
    try {
        await api.createKnowledge(name, description);
        this.loadList();
    } catch (error) {
        alert('创建失败：' + error.message);
    }
}
```

- [ ] **Step 2: 更新 app.js - api.createKnowledge() 方法**

```javascript
async createKnowledge(name, description) {
    const response = await fetch('/spring/ai/loom/knowledge', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, description })
    });
    if (!response.ok) throw new Error(await response.text());
    return response.json();
}
```

- [ ] **Step 3: 更新知识库列表渲染，显示描述**

```javascript
loadList() {
    // ... existing code ...
    listHtml += `
        <div class="knowledge-item" onclick="knowledge.select('${kb.id}', '${kb.name}')">
            <div class="knowledge-name">${kb.name}</div>
            <div class="knowledge-desc">${kb.description || '无描述'}</div>
        </div>
    `;
}
```

- [ ] **Step 4: 提交**

```bash
git add spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/
git commit -m "feat(ui): add description input for knowledge creation, show in list"
```

---

## Task 10: 前端 - 知识库多选 + 移除 enableRag

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js`
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/index.html`

**Interfaces:**
- Consumes: 后端知识库列表 API
- Produces: state.enabledKnowledgeIds 多选状态，移除 state.enableRag

- [ ] **Step 1: 更新 state 定义**

```javascript
const state = {
    // ... existing fields ...
    // selectedKnowledgeId: null,  // 删除
    // enableRag: false,           // 删除
    enabledKnowledgeIds: [],      // 新增
};
```

- [ ] **Step 2: 更新知识库选择 UI 为多选 checkbox**

```javascript
selectKnowledgeForChat(id) {
    const index = state.enabledKnowledgeIds.indexOf(id);
    if (index > -1) {
        state.enabledKnowledgeIds.splice(index, 1);
    } else {
        state.enabledKnowledgeIds.push(id);
    }
    // 更新 UI
    this.renderKnowledgeList();
}

renderKnowledgeList() {
    // 渲染时根据 state.enabledKnowledgeIds 显示 checkbox 状态
}
```

- [ ] **Step 3: 更新 chat.send() 方法**

```javascript
const record = {
    message: text,
    conversationId: state.conversationId,
    mcps: state.selectedMcps,
    // enableRag: state.enableRag,              // 删除
    // knowledgeId: state.selectedKnowledgeId,  // 删除
    enabledKnowledgeIds: state.enabledKnowledgeIds,  // 新增
    fileIds: state.pendingImages.length > 0 ? state.pendingImages.map(img => img.fileId).filter(Boolean) : null,
};
```

- [ ] **Step 4: 移除 enableRag 开关 UI**

在 index.html 中删除 `enableRag` 相关的 checkbox 或按钮。

- [ ] **Step 5: 提交**

```bash
git add spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/
git commit -m "feat(ui): change knowledge selection to multi-select, remove enableRag"
```

---

## Task 11: 后端接口 - 更新 ChatRequest 和 Controller

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/ChatRequest.java`
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/controller/SseController.java`

**Interfaces:**
- Consumes: 前端发送的 enabledKnowledgeIds
- Produces: 后端接收 enabledKnowledgeIds 列表

- [ ] **Step 1: 更新 ChatRequest record**

```java
public record ChatRequest(
    String message,
    String conversationId,
    List<String> mcps,
    List<String> enabledKnowledgeIds,  // 替换 knowledgeId
    List<String> fileIds
) {}
```

- [ ] **Step 2: 更新 Controller 中传递参数**

确保 `enabledKnowledgeIds` 从 request 传递到 `DefaultChat.stream()`。

- [ ] **Step 3: 提交**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/controller/
git commit -m "feat(api): update ChatRequest to use enabledKnowledgeIds"
```

---

## Task 12: 更新默认 system prompt

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentProperties.java`

**Interfaces:**
- Consumes: 现有 defaultSystem 配置
- Produces: 简化版 system prompt（移除强制工作流，保留核心指引）

- [ ] **Step 1: 更新 defaultSystem 默认值**

```java
private String defaultSystem = """
你是一个智能助手。以下是你当前可用的能力：

【技能】
（由系统动态注入）

【知识库】
（由系统动态注入）

【工具】
（MCP 工具列表，由系统动态注入）

当用户意图匹配某项技能时，调用 @getSkill 获取详细执行指南。
当用户问题涉及某个知识库时，调用 @searchKnowledge 检索相关信息。
都不匹配时，直接基于通用知识回答。
""";
```

- [ ] **Step 2: 提交**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentProperties.java
git commit -m "refactor(config): simplify defaultSystem prompt"
```

---

## Task 13: 集成测试

**Files:**
- Create: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/knowledge/KnowledgeToolTest.java`

**Interfaces:**
- Consumes: 完整的后端服务
- Produces: 验证知识库工具功能正常

- [ ] **Step 1: 创建测试类**

```java
@SpringBootTest
public class KnowledgeToolTest {
    
    @Autowired
    private IKnowledgeTool knowledgeTool;
    
    @Autowired
    private IKnowledge knowledge;
    
    @Test
    public void testListKnowledgeBases() {
        // 创建测试知识库
        knowledge.insert("测试知识库", "用于测试的知识库描述");
        
        // 测试分页列出
        ToolContext context = new ToolContext(Map.of("username", "admin"));
        String result = knowledgeTool.listKnowledgeBases(1, 20, context);
        
        assertTrue(result.contains("测试知识库"));
        assertTrue(result.contains("用于测试的知识库描述"));
    }
    
    @Test
    public void testSearchKnowledge() {
        // 需要先上传文件到知识库，这里简化测试
        // 实际测试需要完整的文件上传 + 向量化流程
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=KnowledgeToolTest`

Expected: 测试通过

- [ ] **Step 3: 提交**

```bash
git add spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/knowledge/
git commit -m "test: add KnowledgeTool integration tests"
```

---

## Task 14: 文档更新

**Files:**
- Modify: `README.md`
- Modify: `README.zh-CN.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: 新功能说明
- Produces: 更新后的文档

- [ ] **Step 1: 更新 CLAUDE.md 中的架构说明**

在 "Core Interfaces" 表格中：
- 更新 `ISkillTool` 描述：新增 `listSkills(page, size)` 分页方法
- 新增 `IKnowledgeTool` 行：`listKnowledgeBases(page, size)` + `searchKnowledge(knowledgeId, query, topK?)`
- 更新 RAG 相关说明：移除 `RetrievalAugmentationAdvisor`，改为 tool-based 检索

- [ ] **Step 2: 更新配置属性说明**

```markdown
- `knowledge` — `enabled` (boolean, default **true**). Set to `false` to disable knowledge tool group
```

- [ ] **Step 3: 提交**

```bash
git add README.md README.zh-CN.md CLAUDE.md
git commit -m "docs: update architecture and configuration docs"
```

---

## Task 15: 最终验证 + 发布

**Files:**
- 无新文件

**Interfaces:**
- Consumes: 完整实现
- Produces: 验证通过，准备发布

- [ ] **Step 1: 完整构建**

Run: `mvn clean install -Dgpg.skip=true`

Expected: 构建成功

- [ ] **Step 2: 启动测试应用**

Run: `mvn spring-boot:run -pl spring-ai-loom-agent-test`

Expected: 应用启动成功，无报错

- [ ] **Step 3: 手动测试**

1. 创建知识库（输入名称 + 描述）
2. 上传文件到知识库
3. 在聊天界面多选启用知识库
4. 发送与知识库相关的问题
5. 验证 LLM 调用 `searchKnowledge` 工具并返回正确结果

- [ ] **Step 4: 提交最终版本**

```bash
git commit --allow-empty -m "release: knowledge tool refactoring complete"
git tag v2.1.0
git push origin main --tags
```

---

## 总结

本计划包含 15 个任务，涵盖：
- 数据库迁移（1 个）
- 后端模型/接口/工具（6 个）
- 自动配置（1 个）
- Chat 层动态注入（1 个）
- 配置更新（1 个）
- 前端 UI（2 个）
- 后端 API（1 个）
- 测试（1 个）
- 文档（1 个）
- 验证发布（1 个）

预计实施时间：2-3 天（取决于测试和调试）
