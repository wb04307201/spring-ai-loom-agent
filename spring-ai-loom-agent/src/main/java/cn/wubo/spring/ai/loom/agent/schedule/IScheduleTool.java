package cn.wubo.spring.ai.loom.agent.schedule;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;

/**
 * Marker sub-interface of {@link IEmbedTool} for the LLM-callable "create a scheduled task" tool.
 * <p>
 * Concrete method signatures are filled in by Task 4.1.
 * For now, this marker exists so {@code SubTaskConfiguration} can filter the sub-task
 * ChatClient tool set via {@code instanceof IScheduleTool} (preventing LLM-spawned
 * schedules from outlasting their parent sub-task).
 * </p>
 */
public interface IScheduleTool extends IEmbedTool {
}