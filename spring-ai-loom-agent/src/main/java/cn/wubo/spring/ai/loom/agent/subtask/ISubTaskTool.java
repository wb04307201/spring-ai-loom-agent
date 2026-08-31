package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.ToolGroup;
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
@ToolGroup(value = "subtask", defaultGranted = true, description = "start_sub_task / list_sub_tasks / cancel_sub_task / get_sub_task_history — 委派子任务给子模型")
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
            @ToolParam(description = "子任务要完成的指令,例如'总结以下长文...'") String prompt,
            @ToolParam(description = "可选的额外系统指令,例如\"只关注技术细节\"。不需要可传 null。") String systemContext,
            ToolContext toolContext);

    /**
     * Lists active (RUNNING) sub-tasks in the current conversation.
     * Scoped to (username, conversationId) from the tool context to prevent
     * cross-user / cross-conversation information leakage.
     */
    String listSubTasks(ToolContext toolContext);

    /**
     * Cancels a running sub-task by id. Only succeeds if the sub-task belongs
     * to the caller AND to the current conversation (double isolation).
     */
    String cancelSubTask(
            @ToolParam(description = "要取消的子任务 ID(从 list_sub_tasks 返回结果中获取)") String subTaskId,
            ToolContext toolContext);

    /**
     * Returns recent sub-task history (completed / failed / cancelled) for the
     * current conversation.
     */
    String getSubTaskHistory(
            @ToolParam(description = "返回多少条,默认 10") Integer limit,
            ToolContext toolContext);
}
