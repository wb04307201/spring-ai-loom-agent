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
        String username = (String) toolContext.getContext().get("username");
        String parentConvId = (String) toolContext.getContext().get("parentConversationId");

        String subTaskId = registry.register(username, parentConvId, prompt);
        log.info("子任务启动: id={}, user={}, conv={}", subTaskId, username, parentConvId);

        SubTaskRequest req = new SubTaskRequest(subTaskId, parentConvId, null,
                username, prompt, systemContext, false);

        SubTaskResult result;
        try {
            result = executor.execute(req);
        } catch (Exception e) {
            log.error("子任务异常: id={}", subTaskId, e);
            result = SubTaskResult.failed(req, 0L, System.currentTimeMillis(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        registry.markFinished(subTaskId, result.status(), result.text(), result.errorMessage());
        return formatForMainConversation(result);
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
