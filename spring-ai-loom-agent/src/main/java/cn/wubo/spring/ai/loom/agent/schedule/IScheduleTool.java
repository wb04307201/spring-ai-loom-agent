package cn.wubo.spring.ai.loom.agent.schedule;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.ToolGroup;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * LLM-callable tool for managing scheduled tasks.
 * <p>
 * A schedule fires a sub-task (fromScheduler=true) on the configured trigger.
 * Tasks are namespaced per {@code {username}-{conversationId}} and persisted by
 * flex-schedule; min-interval / max-lifetime are enforced by flex-schedule limits.
 * </p>
 * <p>
 * Extends {@link IEmbedTool} so {@code SubTaskConfiguration} can filter it out of the
 * sub-task ChatClient tool set via {@code instanceof IScheduleTool} (preventing
 * LLM-spawned schedules from outlasting their parent sub-task).
 * </p>
 */
@ToolGroup("schedule")
public interface IScheduleTool extends IEmbedTool {

    String createSchedule(
            @ToolParam(description = "任务名,字母数字下划线。在同一会话内需唯一。") String name,
            @ToolParam(description = "调度类型: cron | fixed_delay | fixed_rate | one_shot") String scheduleType,
            @ToolParam(description = "表达式: cron 字符串 / 间隔秒数 / one_shot 的延迟秒数") String expression,
            @ToolParam(description = "触发时作为子任务运行的提示词") String prompt,
            ToolContext toolContext);

    String cancelSchedule(
            @ToolParam(description = "任务名(用户给定的短名,无需前缀)") String name,
            ToolContext toolContext);

    String listSchedules(ToolContext toolContext);

    String getScheduleHistory(
            @ToolParam(description = "任务名") String name,
            @ToolParam(description = "返回多少条,默认 20") Integer limit,
            ToolContext toolContext);

    /**
     * Used internally by the BFF (not exposed as a tool).
     */
    String listSchedulesRaw(String username);
}
