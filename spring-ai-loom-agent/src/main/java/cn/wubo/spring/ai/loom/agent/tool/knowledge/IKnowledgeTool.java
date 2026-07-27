package cn.wubo.spring.ai.loom.agent.tool.knowledge;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

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
