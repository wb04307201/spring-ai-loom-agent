package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class DefaultSubTaskTool implements ISubTaskTool {

 private static final Logger log = LoggerFactory.getLogger(DefaultSubTaskTool.class);
 private static final DateTimeFormatter TIME_FMT =
 DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

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

 @Tool(description = "列出当前会话中正在运行的子任务。返回每个子任务的 ID、状态和指令摘要,"
 + " ID 可用于 cancel_sub_task。")
 @Override
 public String listSubTasks(ToolContext toolContext) {
 String username = readContextString(toolContext, "username");
 String convId = readContextString(toolContext, "parentConversationId");
 List<SubTaskRegistry.SubTaskRecord> active =
 registry.listActiveByConversation(username, convId);

 if (active.isEmpty()) {
 return "(当前会话无运行中的子任务)";
 }

 StringBuilder sb = new StringBuilder("当前会话运行中的子任务:\n\n");
 for (SubTaskRegistry.SubTaskRecord rec : active) {
 String promptPreview = rec.prompt() != null && rec.prompt().length() > 60
 ? rec.prompt().substring(0, 60) + "..."
 : rec.prompt();
 sb.append(String.format("- ID: %s | 状态: %s | 指令: %s%n",
 rec.subTaskId(), rec.status(), promptPreview));
 }
 return sb.toString();
 }

 @Tool(description = "取消当前会话中一个正在运行的子任务。需要通过 list_sub_tasks 先获取子任务 ID。")
 @Override
 public String cancelSubTask(String subTaskId, ToolContext toolContext) {
 if (subTaskId == null || subTaskId.isBlank()) {
 return "[取消失败] 子任务 ID 不能为空";
 }
 String username = readContextString(toolContext, "username");
 String convId = readContextString(toolContext, "parentConversationId");

 // 会话级隔离:先确认该子任务属于当前会话,避免跨会话取消
 SubTaskRegistry.SubTaskRecord rec = registry.get(subTaskId);
 if (rec == null) {
 return "[取消失败] 未找到子任务: " + subTaskId;
 }
 if (rec.conversationId() == null || !rec.conversationId().equals(convId)) {
 // 返回与"未找到"相同的消息,避免泄露子任务是否存在于其他会话
 log.warn("拒绝跨会话取消子任务: caller conv={}, task conv={}, subTaskId={}",
 convId, rec.conversationId(), subTaskId);
 return "[取消失败] 未找到子任务: " + subTaskId;
 }
 if (rec.status() != SubTaskStatus.RUNNING) {
 return "[取消失败] 子任务已不在运行中(状态: " + rec.status() + ")";
 }

 boolean cancelled = registry.kill(username, subTaskId);
 if (cancelled) {
 log.info("子任务已取消: id={}, user={}, conv={}", subTaskId, username, convId);
 return "[子任务已取消] " + subTaskId;
 }
 return "[取消失败] 子任务可能已完成或不属于当前用户";
 }

 @Tool(description = "获取当前会话的子任务历史(已完成/失败/已取消)。默认返回最近 10 条。")
 @Override
 public String getSubTaskHistory(Integer limit, ToolContext toolContext) {
 String username = readContextString(toolContext, "username");
 String convId = readContextString(toolContext, "parentConversationId");
 int n = (limit == null || limit < 1) ? 10 : limit;

 List<SubTaskRegistry.SubTaskRecord> history =
 registry.listHistoryByConversation(username, convId, n);

 if (history.isEmpty()) {
 return "(当前会话无子任务历史)";
 }

 StringBuilder sb = new StringBuilder("当前会话子任务历史(最近 ").append(n).append(" 条):\n\n");
 for (SubTaskRegistry.SubTaskRecord rec : history) {
 String promptPreview = rec.prompt() != null && rec.prompt().length() > 40
 ? rec.prompt().substring(0, 40) + "..."
 : rec.prompt();
 String startedAt = rec.startedAt() > 0
 ? TIME_FMT.format(Instant.ofEpochMilli(rec.startedAt()))
 : "?";
 String duration = rec.finishedAt() > 0 && rec.startedAt() > 0
 ? ((rec.finishedAt() - rec.startedAt()) / 1000) + "s"
 : "-";

 sb.append(String.format("- [%s] ID: %s | 状态: %s | 耗时: %s | 指令: %s",
 startedAt, rec.subTaskId(), rec.status(), duration, promptPreview));

 if (rec.status() == SubTaskStatus.FAILED
 && rec.errorMessage() != null && !rec.errorMessage().isBlank()) {
 sb.append(" | 错误: ").append(rec.errorMessage());
 }
 sb.append('\n');
 }
 return sb.toString();
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
 case FAILED -> "[子任务失败 conv=%s] %s".formatted(r.conversationId(), r.errorMessage());
 case CANCELLED -> "[子任务已取消 conv=%s] 用户手动取消".formatted(r.conversationId());
 default -> "[子任务状态异常] " + r.status();
 };
 }
}
