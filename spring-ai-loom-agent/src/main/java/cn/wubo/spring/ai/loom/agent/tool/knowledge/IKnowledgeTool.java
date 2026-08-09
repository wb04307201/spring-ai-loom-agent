package cn.wubo.spring.ai.loom.agent.tool.knowledge;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public interface IKnowledgeTool extends IEmbedTool {

 /**
 * 注意：曾有 listKnowledgeBases(page, size) 工具，已删除。
 * 原因：KB 列表已在 system prompt【知识库】段中展示（带 ID+名称+摘要）；
 * 再让 LLM 调用工具列一遍是冗余。LLM 应当直接用 prompt 中的 KB 信息决策。
 */

 @Tool(description = "在指定知识库中检索相关文档片段。当用户的问题可能涉及知识库中的内容时调用此工具。")
 String searchKnowledge(
 @ToolParam(description = "知识库ID") String knowledgeId,
 @ToolParam(description = "检索查询关键词") String query,
 @ToolParam(description = "返回结果数量，不传则使用全局默认值") Integer topK,
 ToolContext toolContext
 );
}
