package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * LLM-callable tool that delegates a subtask to a sub-model.
 * <p>
 * The sub-task shares the main conversation's ChatMemory (write-only namespace
 * "{conversationId}--sub--{subTaskId}"), runs synchronously, and returns the
 * final answer as a string for the main conversation to consume.
 * </p>
 */
public interface ISubTaskTool extends IEmbedTool {

    /**
     * Starts a subtask. The main conversation is blocked until the sub-task
     * finishes (or is cancelled).
     *
     * @param prompt        What the sub-task should accomplish.
     * @param systemContext Optional extra system guidance (or {@code null}).
     * @param toolContext   Spring AI tool context (carries username, etc.)
     */
    String startSubTask(
            @ToolParam(description = "子任务要完成的指令,例如'总结以下长文...')") String prompt,
            @ToolParam(description = "可选的额外系统指令,例如\"只关注技术细节\"。不需要可传 null。") String systemContext,
            ToolContext toolContext);
}
