package cn.wubo.spring.ai.loom.agent.schedule;

import cn.wubo.flex.schedule.core.ExecutionRecord;
import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.TaskInfo;
import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import cn.wubo.spring.ai.loom.agent.subtask.ISubTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 默认 {@link IScheduleTool} 实现。
 * <p>
 * 基于 flex-schedule 的 {@link FlexScheduledTaskService} 创建/取消/查询定时任务，
 * 并把每个任务同步持久化到 loom-agent 自己的 H2 表 {@code loom_scheduled_task}。
 * 任务名命名空间 {@code loom-sched-{username}-{conversationId}-{name}} 隔离,
 * 触发时作为一个 {@link SubTaskRequest} 交给 {@link ISubTaskExecutor} 执行
 * (fromScheduler=true)。
 * </p>
 * <p>
 * 最短触发间隔 / 最长存活由 flex-schedule 的 limits 强校验(超限时 register 抛异常,
 * 这里捕获后返回友好文案)。
 * </p>
 */
public class DefaultScheduleTool implements IScheduleTool {

    private static final Logger log = LoggerFactory.getLogger(DefaultScheduleTool.class);

    private final FlexScheduledTaskService flexService;
    private final ISubTaskExecutor subTaskExecutor;
    private final ILoomScheduleTriggerRepository loomScheduleTriggerRepository;

    public DefaultScheduleTool(FlexScheduledTaskService flexService,
                               ISubTaskExecutor subTaskExecutor,
                               ILoomScheduleTriggerRepository loomScheduleTriggerRepository) {
        this.flexService = flexService;
        this.subTaskExecutor = subTaskExecutor;
        this.loomScheduleTriggerRepository = loomScheduleTriggerRepository;
    }

    static String fullName(String username, String conversationId, String name) {
        return "loom-sched-" + username + "-" + conversationId + "-" + name;
    }

    @Override
    @Tool(description = "创建一个定时任务。最短 10 分钟执行一次,最长存活 3 天(强校验)。"
            + "类型 cron / fixed_delay / fixed_rate / one_shot。")
    public String createSchedule(String name, String scheduleType, String expression, String prompt, ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        String convId = (String) toolContext.getContext().get("parentConversationId");
        String full = fullName(username, convId, name);

        try {
            switch (scheduleType.toLowerCase()) {
                case "cron" -> flexService.task(full)
                        .cron(expression)
                        .register(() -> runAsSubTask(username, convId, prompt));
                case "fixed_delay" -> flexService.task(full)
                        .fixedDelay(Duration.ofSeconds(Long.parseLong(expression)))
                        .register(() -> runAsSubTask(username, convId, prompt));
                case "fixed_rate" -> flexService.task(full)
                        .fixedRate(Duration.ofSeconds(Long.parseLong(expression)))
                        .register(() -> runAsSubTask(username, convId, prompt));
                case "one_shot" -> flexService.task(full)
                        .oneShot(Duration.ofSeconds(Long.parseLong(expression)))
                        .register(() -> runAsSubTask(username, convId, prompt));
                default -> { return "[定时失败] 不支持的类型: " + scheduleType; }
            }
            persistAfterRegister(full, name, scheduleType, expression, prompt, username, convId);
            return "[定时已创建] " + name + " (" + scheduleType + ": " + expression + ")";
        } catch (Exception e) {
            log.error("定时器创建失败: full={}", full, e);
            return "[定时失败] " + e.getMessage();
        }
    }

    /**
     * Write the row to {@code loom_scheduled_task} immediately after a successful
     * register. We use the same instant for createdAt/updatedAt (per spec the
     * createdAt is the original registration time so max-lifetime can fire across
     * restarts; see {@link ScheduleRestoreListener}). If persistence fails we
     * compensate by cancelling the just-registered task so the two layers stay
     * aligned.
     */
    private void persistAfterRegister(String full, String name, String scheduleType, String expression,
                                      String prompt, String username, String convId) {
        long nowSec = parseLongOrZero(expression);
        Instant now = Instant.now();
        LoomScheduleTriggerRecord record = new LoomScheduleTriggerRecord(
                full,
                scheduleType,
                "cron".equals(scheduleType) ? expression : null,
                ("fixed_delay".equals(scheduleType) || "fixed_rate".equals(scheduleType)) ? nowSec : null,
                null,   // initialDelaySeconds (preserved for future extension)
                "one_shot".equals(scheduleType) ? nowSec : null,
                prompt,
                username,
                convId,
                false,
                now,
                now);
        try {
            loomScheduleTriggerRepository.save(record);
        } catch (Exception e) {
            log.error("持久化定时任务失败,回滚 register: full={}", full, e);
            try {
                flexService.cancel(full);
            } catch (Exception cancelErr) {
                log.warn("注册回滚 cancel 也失败: full={}", full, cancelErr);
            }
            throw e;
        }
    }

    private static long parseLongOrZero(String expression) {
        try {
            return Long.parseLong(expression);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void runAsSubTask(String username, String convId, String prompt) {
        String id = UUID.randomUUID().toString();
        SubTaskRequest req = new SubTaskRequest(id, convId, null, username, prompt, null, true);
        SubTaskResult result = null;
        try {
            // execute() returns a SubTaskResult instead of throwing on failure
            // (it internally catches ExecutionException and reports FAILED via
            // the result). That contract was originally intended for the
            // LLM-tool path; here we use the schedule's instrument() outcome
            // to mark the execution as success/failure, so we re-throw on
            // FAILED so the schedule's ExecutionRecord.success reflects the
            // actual sub-task outcome.
            result = subTaskExecutor.execute(req);
            if (result.status() == SubTaskStatus.FAILED) {
                throw new RuntimeException("调度子任务执行失败: " + result.errorMessage());
            }
        } catch (Exception e) {
            log.error("调度子任务执行失败: id={}", id, e);
            // Re-throw so FlexScheduledTaskRegistrar.instrument()'s catch block
            // records an ExecutionRecord with success=false instead of true.
            // Without this the schedule's "history" UI would show every fire
            // as successful even when the sub-task itself failed.
            throw new RuntimeException(e);
        }
    }

    @Override
    @Tool(description = "取消一个定时任务。")
    public String cancelSchedule(String name, ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        String convId = (String) toolContext.getContext().get("parentConversationId");
        String full = fullName(username, convId, name);
        try {
            flexService.cancel(full);
            try {
                loomScheduleTriggerRepository.delete(full);
            } catch (Exception e) {
                log.warn("取消时删除持久化行失败: full={}", full, e);
            }
            return "[定时已取消] " + name;
        } catch (Exception e) {
            return "[取消失败] " + e.getMessage();
        }
    }

    @Override
    @Tool(description = "列出当前会话下我创建的所有定时任务。")
    public String listSchedules(ToolContext toolContext) {
        return listSchedulesRaw((String) toolContext.getContext().get("username"));
    }

    @Override
    public String listSchedulesRaw(String username) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("定时任务列表:%n%n"));
        List<TaskInfo> all = flexService.listTasks();
        int n = 0;
        for (TaskInfo info : all) {
            if (info.taskName().startsWith("loom-sched-")) {
                String withoutPrefix = info.taskName().substring("loom-sched-".length());
                int firstDash = withoutPrefix.indexOf('-');
                if (firstDash < 0) continue;
                String owner = withoutPrefix.substring(0, firstDash);
                if (!owner.equals(username)) continue;
                sb.append(String.format("- %s (type=%s, schedule=%s)%n",
                        info.taskName(), info.taskType(), info.schedule()));
                n++;
            }
        }
        if (n == 0) sb.append(String.format("(无定时任务)%n"));
        return sb.toString();
    }

    @Override
    @Tool(description = "获取某个定时任务的最近执行历史。")
    public String getScheduleHistory(String name, Integer limit, ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        String convId = (String) toolContext.getContext().get("parentConversationId");
        String full = fullName(username, convId, name);
        int n = limit == null ? 20 : limit;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("'%s' 的最近 %d 条执行记录:%n%n", name, n));
        List<ExecutionRecord> history;
        try {
            history = flexService.getExecutionHistory(full, n);
        } catch (Exception e) {
            return "[查询失败] " + e.getMessage();
        }
        if (history == null || history.isEmpty()) {
            sb.append(String.format("(暂无执行记录)%n"));
            return sb.toString();
        }
        for (ExecutionRecord r : history) {
            sb.append(String.format("- 触发 %s | 结果=%s | 时长=%s%s%n",
                    r.startTime(),
                    r.success() ? "成功" : "失败",
                    r.duration(),
                    r.error() != null ? " | 错误=" + r.error() : ""));
        }
        return sb.toString();
    }
}
