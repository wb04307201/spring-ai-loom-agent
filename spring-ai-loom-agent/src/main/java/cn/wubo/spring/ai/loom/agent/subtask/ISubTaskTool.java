package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;

/**
 * Marker sub-interface of {@link IEmbedTool} for the LLM-callable "start a sub-task" tool.
 * <p>
 * Concrete method signatures ({@code startSubTask}) are filled in by Task 3.1.
 * For now, this marker exists so {@code SubTaskConfiguration} can filter the sub-task
 * ChatClient tool set via {@code instanceof ISubTaskTool} (preventing LLM recursion).
 * </p>
 */
public interface ISubTaskTool extends IEmbedTool {
}