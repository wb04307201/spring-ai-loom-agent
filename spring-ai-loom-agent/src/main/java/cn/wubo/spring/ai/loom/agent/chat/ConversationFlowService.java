package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.model.ToolCallLog;
import cn.wubo.spring.ai.loom.agent.token.ChatUsageService;
import cn.wubo.spring.ai.loom.agent.tool.IToolCallLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V4.0 全量对话流（单个会话时间线）。
 *
 * <p>4 个数据源 merge：
 * <ol>
 *   <li>{@code SPRING_AI_CHAT_MEMORY}（按 conversationId 查所有 message）— User/Assistant（含 thinking + toolCalls）+ Tool（结果）</li>
 *   <li>{@code loom_tool_call_log}（V4.0+）— 结构化 tool call 数据（args_json / result_text / duration_ms）</li>
 *   <li>{@code loom_subtask_history}（按 username+conversationId）— 子任务</li>
 *   <li>{@code loom_scheduled_task} JOIN {@code loom_schedule_execution}（按 conversationId）— 定时任务声明 + 每次执行结果</li>
 * </ol>
 *
 * <p>历史数据（V4.0 之前）没有 {@code loom_tool_call_log}，从 chat_memory.metadata
 * 兜底解析；新数据优先用 {@code loom_tool_call_log}（结构化字段更完整）。
 *
 * <p>事件按 ts 升序合并后分页。{@code types} 参数可过滤事件类型。
 */
@Service
public class ConversationFlowService {

    private final JdbcTemplate jdbcTemplate;
    private final IToolCallLogRepository toolCallLogRepository;
    private final ChatUsageService chatUsageService;
    private final ObjectMapper objectMapper;

    public ConversationFlowService(JdbcTemplate jdbcTemplate,
                                  IToolCallLogRepository toolCallLogRepository,
                                  ChatUsageService chatUsageService,
                                  ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.toolCallLogRepository = toolCallLogRepository;
        this.chatUsageService = chatUsageService;
        this.objectMapper = objectMapper;
    }

    public record Meta(String conversationId, String title, Instant createdAt, Instant updatedAt, String username) {}
    public record Stats(int callCount, long totalTokens, long toolCallCount, long subtaskCount, long scheduleCount, int errorCount) {}
    public record Event(String type, Instant ts, Map<String, Object> data) {}
    public record FlowResult(Meta meta, Stats stats, List<Event> events, int page, int size, long total, boolean hasMore) {}

    private static final Set<String> DEFAULT_TYPES = Set.of(
            "USER", "ASSISTANT", "TOOL_CALL", "TOOL_RESULT", "SUBTASK", "SCHEDULE");

    public FlowResult flow(String conversationId, String username, int page, int size, Set<String> types) {
        if (types == null || types.isEmpty()) types = DEFAULT_TYPES;

        Meta meta = loadMeta(conversationId, username);
        Stats stats = computeStats(conversationId, username);

        List<Event> all = new ArrayList<>();
        if (types.contains("USER") || types.contains("ASSISTANT") || types.contains("TOOL_CALL") || types.contains("TOOL_RESULT")) {
            all.addAll(extractFromChatMemory(conversationId, username, types));
        }
        if (types.contains("SUBTASK")) all.addAll(loadSubtasks(username, conversationId));
        if (types.contains("SCHEDULE")) all.addAll(loadSchedules(username, conversationId));

        all.sort(Comparator.comparing(Event::ts));
        int total = all.size();
        int fromIdx = Math.min(page * size, total);
        int toIdx = Math.min(fromIdx + size, total);
        List<Event> pageEvents = all.subList(fromIdx, toIdx);

        return new FlowResult(meta, stats, pageEvents, page, size, total, toIdx < total);
    }

    private Meta loadMeta(String conversationId, String username) {
        List<Meta> rows = jdbcTemplate.query(
                "select conversation_id, title, created_at, updated_at, username " +
                        "from user_conversation where conversation_id = ? and username = ?",
                (rs, n) -> new Meta(
                        rs.getString("conversation_id"),
                        rs.getString("title"),
                        rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant(),
                        rs.getString("username")),
                conversationId, username);
        return rows.isEmpty()
                ? new Meta(conversationId, null, null, null, username)
                : rows.get(0);
    }

    private Stats computeStats(String conversationId, String username) {
        // 工具调用数：V4.0 优先看 loom_tool_call_log，没数据就 0（V4.0 前的会话在 flow 里看历史，但 stats 卡片只算新数据）
        long toolCallCount = jdbcTemplate.queryForObject(
                "select count(*) from loom_tool_call_log where conversation_id = ?",
                Long.class, conversationId);
        // 子任务数
        long subtaskCount = jdbcTemplate.queryForObject(
                "select count(*) from loom_subtask_history where username = ? and conversation_id = ?",
                Long.class, username, conversationId);
        // V5.4 P1 修复：定时任务声明数（之前查 execution 触发次数，新创建的 schedule 还没执行 → 0）
        long scheduleCount = jdbcTemplate.queryForObject(
                "select count(*) from loom_scheduled_task " +
                        "where username = ? and conversation_id = ?",
                Long.class, username, conversationId);
        // 错误数（tool 返回值含 error / subtask 失败）
        long errorCount = jdbcTemplate.queryForObject(
                "select count(*) from loom_tool_call_log " +
                        "where conversation_id = ? and result_is_error = true",
                Long.class, conversationId);
        errorCount += jdbcTemplate.queryForObject(
                "select count(*) from loom_subtask_history " +
                        "where username = ? and conversation_id = ? and status = 'FAILED'",
                Long.class, username, conversationId);
        errorCount += jdbcTemplate.queryForObject(
                "select count(*) from loom_schedule_execution e " +
                        "join loom_scheduled_task t on t.task_name = e.task_name " +
                        "where t.username = ? and t.conversation_id = ? and e.success = false",
                Long.class, username, conversationId);

        ChatUsageService.ConversationUsage u = chatUsageService.byConversation(conversationId);
        return new Stats((int) u.callCount(), u.totalTokens(), toolCallCount, subtaskCount, scheduleCount, (int) errorCount);
    }

    /**
     * 从 chat_memory 提取 USER / ASSISTANT / TOOL_CALL / TOOL_RESULT 事件。
     * 优先用 loom_tool_call_log（V4.0+）覆盖 chat_memory 中的 tool call 信息。
     */
    private List<Event> extractFromChatMemory(String conversationId, String username, Set<String> types) {
        // H2 schema：SPRING_AI_CHAT_MEMORY(conversation_id, content, type, timestamp)
        // 没有 metadata 列。AssistantMessage / ToolResponseMessage 整体以 JSON
        // 序列化存在 content 里。H2 没有 JSON_VALUE/JSONPATH，Java 端 ObjectMapper 解析。
        List<ChatMemoryRow> rows = jdbcTemplate.query(
                "select content, type, timestamp " +
                        "from spring_ai_chat_memory where conversation_id = ? order by timestamp",
                (rs, n) -> new ChatMemoryRow(
                        rs.getString("content"),
                        rs.getString("type"),
                        rs.getTimestamp("timestamp") == null ? null : rs.getTimestamp("timestamp").toInstant()),
                conversationId);

        // 预加载 V4.0+ 的 tool call log（结构化 args/result/duration）
        Map<String, ToolCallLog> logByCallId = new HashMap<>();
        for (ToolCallLog l : toolCallLogRepository.findByConversationId(conversationId)) {
            logByCallId.put(l.toolCallId(), l);
        }

        List<Event> events = new ArrayList<>();
        // V5.1：V4.0 假设从 chat_memory 反推 metadata.thinking 失效（content 是纯文本），
        // 改从 loom_chat_reasoning 显式读。reasoning 是一次会话一条，绑到第一条 ASSISTANT。
        String dbReasoning = types.contains("ASSISTANT") ? chatUsageService.getReasoning(conversationId) : null;
        boolean reasoningBound = false;
        for (ChatMemoryRow row : rows) {
            String type = row.type();
            if (type == null) continue;
            JsonNode contentNode = readContentNode(row.content());
            switch (type) {
                case "USER" -> {
                    if (types.contains("USER")) {
                        String text = contentNode != null ? contentNode.path("content").asText("")
                                : (row.content() == null ? "" : row.content());
                        events.add(new Event("USER", row.timestamp(), Map.of("content", text)));
                    }
                }
                case "ASSISTANT" -> {
                    if (!types.contains("ASSISTANT") && !types.contains("TOOL_CALL")) break;
                    Map<String, Object> data = new HashMap<>();
                    if (contentNode != null) {
                        data.put("content", contentNode.path("content").asText(""));
                        data.put("model", contentNode.path("metadata").path("model").asText(null));
                        JsonNode usage = contentNode.path("metadata").path("usage");
                        if (!usage.isMissingNode() && !usage.isNull()) {
                            data.put("promptTokens", usage.path("promptTokens").asLong(0));
                            data.put("completionTokens", usage.path("completionTokens").asLong(0));
                        }
                        // V4.0 原有尝试（保留兼容旧库）
                        JsonNode thinking = contentNode.path("metadata").path("thinking");
                        if (!thinking.isMissingNode() && !thinking.isNull()) {
                            data.put("thinking", thinking.asText());
                        }
                    } else {
                        data.put("content", row.content() == null ? "" : row.content());
                    }
                    // V5.1：注入 loom_chat_reasoning 的最终思考文本（绑到第一条 ASSISTANT）
                    if (!reasoningBound && dbReasoning != null && !dbReasoning.isBlank()) {
                        data.put("thinking", dbReasoning);
                        reasoningBound = true;
                    }
                    // AssistantMessage.content.toolCalls[]
                    JsonNode toolCalls = contentNode != null ? contentNode.path("toolCalls") : null;
                    List<Map<String, Object>> tcList = new ArrayList<>();
                    if (toolCalls != null && toolCalls.isArray()) {
                        for (JsonNode tc : toolCalls) {
                            Map<String, Object> tcData = new HashMap<>();
                            String callId = tc.path("id").asText(null);
                            String name = tc.path("name").asText(null);
                            tcData.put("id", callId);
                            tcData.put("name", name);
                            ToolCallLog l = callId != null ? logByCallId.get(callId) : null;
                            if (l != null) {
                                tcData.put("args", l.argumentsJson());
                                tcData.put("source", "tool_call_log");
                            } else {
                                tcData.put("args", tc.path("arguments").asText(""));
                                tcData.put("source", "chat_memory");
                            }
                            if (types.contains("TOOL_CALL")) {
                                events.add(new Event("TOOL_CALL",
                                        l != null ? l.createdAt() : row.timestamp(),
                                        tcData));
                            }
                            tcList.add(tcData);
                        }
                    }
                    if (types.contains("ASSISTANT")) {
                        data.put("toolCalls", tcList);
                        events.add(new Event("ASSISTANT", row.timestamp(), data));
                    }
                }
                case "TOOL" -> {
                    if (!types.contains("TOOL_RESULT")) break;
                    // ToolResponseMessage: { "id": "call_abc", "name": "toolName", "content": "result" }
                    String callId = contentNode != null ? contentNode.path("id").asText(null) : null;
                    String name = contentNode != null ? contentNode.path("name").asText(null) : null;
                    String result = contentNode != null ? contentNode.path("content").asText(null)
                            : (row.content() == null ? "" : row.content());
                    ToolCallLog l = callId != null ? logByCallId.get(callId) : null;
                    Map<String, Object> data = new HashMap<>();
                    data.put("id", callId);
                    data.put("name", name);
                    if (l != null) {
                        data.put("result", l.resultText());
                        data.put("isError", l.resultIsError());
                        data.put("durationMs", l.durationMs());
                        data.put("source", "tool_call_log");
                        events.add(new Event("TOOL_RESULT", l.createdAt(), data));
                    } else {
                        data.put("result", result);
                        data.put("isError", false);
                        data.put("source", "chat_memory");
                        events.add(new Event("TOOL_RESULT", row.timestamp(), data));
                    }
                }
                default -> { /* 跳过 SYSTEM / 其他 */ }
            }
        }
        return events;
    }

    private JsonNode readContentNode(String content) {
        if (content == null || content.isBlank()) return null;
        try {
            return objectMapper.readTree(content);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Event> loadSubtasks(String username, String conversationId) {
        List<Event> out = new ArrayList<>();
        List<SubtaskRow> rows = jdbcTemplate.query(
                "select subtask_id, prompt, status, started_at, finished_at, error_message, result_text " +
                        "from loom_subtask_history where username = ? and conversation_id = ? order by started_at",
                (rs, n) -> new SubtaskRow(
                        rs.getString("subtask_id"),
                        rs.getString("prompt"),
                        rs.getString("status"),
                        rs.getLong("started_at"),
                        rs.getLong("finished_at"),
                        rs.getString("error_message"),
                        rs.getString("result_text")),
                username, conversationId);
        for (SubtaskRow r : rows) {
            Map<String, Object> data = new HashMap<>();
            data.put("id", r.subtaskId());
            data.put("prompt", r.prompt());
            data.put("status", r.status());
            data.put("startedAt", java.time.Instant.ofEpochMilli(r.startedAt()));
            data.put("finishedAt", java.time.Instant.ofEpochMilli(r.finishedAt()));
            data.put("durationMs", r.finishedAt() - r.startedAt());
            data.put("error", r.errorMessage());
            data.put("result", r.resultText());
            out.add(new Event("SUBTASK", java.time.Instant.ofEpochMilli(r.startedAt()), data));
        }
        return out;
    }

    private List<Event> loadSchedules(String username, String conversationId) {
        // 每次 schedule 触发 = 一个 SCHEDULE 事件（task_name + fire time + result）
        List<ScheduleRow> rows = jdbcTemplate.query(
                "select t.task_name, t.prompt, e.fire_time, e.duration_ms, e.success, e.error_message " +
                        "from loom_scheduled_task t " +
                        "join loom_schedule_execution e on t.task_name = e.task_name " +
                        "where t.username = ? and t.conversation_id = ? order by e.fire_time",
                (rs, n) -> new ScheduleRow(
                        rs.getString("task_name"),
                        rs.getString("prompt"),
                        rs.getTimestamp("fire_time") == null ? null : rs.getTimestamp("fire_time").toInstant(),
                        rs.getLong("duration_ms"),
                        rs.getBoolean("success"),
                        rs.getString("error_message")),
                username, conversationId);
        List<Event> out = new ArrayList<>();
        for (ScheduleRow r : rows) {
            Map<String, Object> data = new HashMap<>();
            data.put("taskName", r.taskName());
            data.put("prompt", r.prompt());
            data.put("fireTime", r.fireTime());
            data.put("durationMs", r.durationMs());
            data.put("success", r.success());
            data.put("error", r.errorMessage());
            out.add(new Event("SCHEDULE", r.fireTime(), data));
        }
        return out;
    }

    private record ChatMemoryRow(String content, String type, Instant timestamp) {}
    private record SubtaskRow(String subtaskId, String prompt, String status, long startedAt, long finishedAt, String errorMessage, String resultText) {}
    private record ScheduleRow(String taskName, String prompt, Instant fireTime, long durationMs, boolean success, String errorMessage) {}
}
