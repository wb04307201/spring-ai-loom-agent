package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

public class DefaultSubTaskTool implements ISubTaskTool {

    private static final Logger log = LoggerFactory.getLogger(DefaultSubTaskTool.class);

    private final ISubTaskExecutor executor;
    private final SubTaskRegistry registry;

    public DefaultSubTaskTool(ISubTaskExecutor executor, SubTaskRegistry registry) {
        this.executor = executor;
        this.registry = registry;
    }

    @Tool(description = "把一段任务委派给一个'子模型'去执行。子任务拥有与主对话相同的"
            + "工具访问(文件/MCP/Skill/时间等),但不能再次启动子任务或创建定时器。"
            + "主对话会同步等待子任务完成,然后拿到最终文本。")
    @Override
    public String startSubTask(String prompt, String systemContext, ToolContext toolContext) {
        // Validate prompt at the tool boundary. Spring AI's ChatClient throws
        // IllegalArgumentException("text cannot be null or empty") for empty
        // prompts deep inside the executor; without this check, every empty
        // tool-call from the LLM pollutes history with a FAILED row whose only
        // fault is a missing argument. Returning a clear error here lets the
        // LLM retry with a real instruction.
        if (prompt == null || prompt.isBlank()) {
            String user = readContextString(toolContext, "username");
            log.warn("子任务 prompt 为空,拒绝执行: user={}", user);
            return "[子任务失败] prompt 不能为空,请提供具体的任务指令再调用 start_sub_task。";
        }

        // Null-safe context extraction: the tool may be invoked from paths where
        // the caller forgot to populate `username` / `parentConversationId` (direct
        // unit tests, scheduler callbacks, custom tool-call wiring). Tolerate and
        // log instead of NPE-ing through the whole sub-task lifecycle.
        String username = readContextString(toolContext, "username");
        String parentConvId = readContextString(toolContext, "parentConversationId");

        // Sub-task id is supplied by the tool (we want to log it BEFORE
        // executor.execute returns). The executor's SubTaskRegistry.register
        // call uses the same id so LLM-tool and schedule-callback paths
        // converge on the same history stream.
        String subTaskId = java.util.UUID.randomUUID().toString();
        log.info("子任务启动: id={}, user={}, conv={}", subTaskId, username, parentConvId);

        SubTaskRequest req = new SubTaskRequest(subTaskId, parentConvId, null,
                username, prompt, systemContext, false);

        SubTaskResult result;
        try {
            SubTaskResult raw = executor.execute(req);
            // Defensive: a custom executor could theoretically return null. Synthesize a
            // FAILED result so the registry transitions to a terminal state instead of
            // leaving the active record stuck.
            result = (raw != null) ? raw
                    : SubTaskResult.failed(req, 0L, System.currentTimeMillis(),
                            "Executor 返回 null 结果");
        } catch (Exception e) {
            log.error("子任务异常: id={}", subTaskId, e);
            result = SubTaskResult.failed(req, 0L, System.currentTimeMillis(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // The executor already calls registry.markFinished on every path
        // (success / interrupt / failure / cancellation), so this tool does
        // NOT need to call it again. Calling it twice would either no-op
        // (best case) or overwrite the recorded error message (worst case).
        return formatForMainConversation(result);
    }

    /**
     * Reads a string value from {@code ToolContext.getContext()}, returning the
     * supplied fallback if the context is null, missing, or non-String.
     */
    private static String readContextString(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) return "";
        Object value = toolContext.getContext().get(key);
        return (value instanceof String s) ? s : "";
    }

    private String formatForMainConversation(SubTaskResult r) {
        return switch (r.status()) {
            case COMPLETED -> "[子任务已完成 conv=%s] %s".formatted(r.conversationId(), r.text());
            case FAILED    -> "[子任务失败 conv=%s] %s".formatted(r.conversationId(), r.errorMessage());
            case CANCELLED -> "[子任务已取消 conv=%s] 用户手动取消".formatted(r.conversationId());
            default -> "[子任务状态异常] " + r.status();
        };
    }
}
