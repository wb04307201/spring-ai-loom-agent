package cn.wubo.spring.ai.loom.agent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class LoomAgentProperties {

    private String defaultSystem = """
            你是一个智能助手，具备「技能检索→匹配→执行」的自动化决策能力。请严格遵守以下工作流：

            ━━━━━━━━━━━━━━━━━━━━━━
            【核心工作流（每轮对话必执行）】
            ━━━━━━━━━━━━━━━━━━━━━━
            🔁 思考阶段（隐式执行，用户不可见）：
            1️⃣ 首先调用 @skillContents {} 获取当前可用技能目录
            2️⃣ 分析用户意图，判断目录中是否存在匹配技能（匹配标准见下方）
            3️⃣ 分支决策：
               ├─ ✅ 有匹配技能 → 调用 @getSkill {"skill_name": "匹配的技能名"} 获取详细信息
               │                → 基于技能信息 + 用户问题，生成最终回复
               │
               └─ ❌ 无匹配技能 → 跳过技能调用，直接基于通用知识回答用户

            ━━━━━━━━━━━━━━━━━━━━━━
            【匹配判断标准】
            ━━━━━━━━━━━━━━━━━━━━━━
            ✅ 视为「匹配」的条件（满足任一即可）：
            • 用户问题包含技能关键词（如技能描述中的核心动词/名词）
            • 用户意图与技能功能语义高度相关（如"写邮件"→/write_email）
            • 用户明确要求使用某类能力（如"用专业语气回复"→角色类技能）

            ❌ 视为「不匹配」的情况：
            • 用户仅闲聊、问候、表达情绪
            • 问题超出所有技能覆盖范围
            • 技能目录为空或加载失败
            
            ━━━━━━━━━━━━━━━━━━━━━━
            【工具匹配判断】
            ━━━━━━━━━━━━━━━━━━━━━━
            格式: @工具名称
               ├─ ✅ 有匹配工具 → 调用对应工具
               └─ ❌ 无匹配工具 → 是否有替代工具，询问用户是否使用
            """;
    private boolean init = true;
    private RagProperty rag = new RagProperty();
    private List<McpProperty> mcps = new ArrayList<>();
    private List<SkillProperty> skills = new ArrayList<>();
    private JVectorProperties jvector = new JVectorProperties();
    private String timezone = "Asia/Shanghai";
    private String gitUsername;
    private String gitToken;

    @Data
    public static class RagProperty {
        private double similarityThreshold = 0.0F;
        private int topK = 4;
        private String defaultPromptTemplate = """
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
                """;
        private String defaultEmptyContextPromptTemplate = """
                The user query is outside your knowledge base.
                Politely inform the user that you can't answer it.
                """;
        private boolean enabledKeyword;
        private boolean enabledSummary;
    }

    @Data
    public static class McpProperty {
        private String name;
        private String title;
        private String description;
        private boolean defaultSelected = true;
        private List<ToolProperty> tools = new ArrayList<>();

        @Data
        public static class ToolProperty {
            private String name;
            private String description;
        }
    }

    @Data
    public static class SkillProperty {
        private String name;
        private String description;
        private boolean load = true;
        private String content;
    }

    @Data
    public static class JVectorProperties {
        private String indexPath = ".local/jvector-index";
        private int m = 16;
        private int efConstruction = 100;
        private int efSearch = 10;

    }
}
