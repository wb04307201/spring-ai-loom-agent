package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.model.ToolCallLog;
import cn.wubo.spring.ai.loom.agent.token.ChatUsageService;
import cn.wubo.spring.ai.loom.agent.tool.IToolCallLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * 全量对话流（单个会话时间线）。
 *
 * <p>4 个数据源 merge：
 * <ol>
 * <li>{@code SPRING_AI_CHAT_MEMORY}（按 conversationId 查所有 message）— User/Assistant（含 thinking + toolCalls）+ Tool（结果）</li>
 * <li>{@code loom_tool_call_log}（+）— 结构化 tool call 数据（args_json / result_text / duration_ms）</li>
 * <li>{@code loom_subtask_history}（按 username+conversationId）— 子任务</li>
 * <li>{@code loom_scheduled_task} JOIN {@code loom_schedule_execution}（按 conversationId）— 定时任务声明 + 每次执行结果</li>
 * </ol>
 *
 * <p>历史数据（之前）没有 {@code loom_tool_call_log}，从 chat_memory.metadata
 * 兜底解析；新数据优先用 {@code loom_tool_call_log}（结构化字段更完整）。
 *
 * <p>事件按 ts 升序合并后分页。{@code types} 参数可过滤事件类型。
 */
@Service
@Slf4j
public class ConversationFlowService {

 private final JdbcTemplate jdbcTemplate;
 private final IToolCallLogRepository toolCallLogRepository;
 private final ChatUsageService chatUsageService;
 private final ObjectMapper objectMapper;
 private static final Set<String> DEFAULT_TYPES = Set.of("USER", "ASSISTANT", "TOOL_CALL", "TOOL_RESULT", "SUBTASK", "SCHEDULE", "SCHEDULE_FIRE", "SYSTEM");
 /**
  * @Lazy 避免 DefaultChat → ConversationFlowService → ... 循环依赖
  */
 private final IChat chat;

 public ConversationFlowService(JdbcTemplate jdbcTemplate, IToolCallLogRepository toolCallLogRepository, ChatUsageService chatUsageService, ObjectMapper objectMapper, @Lazy IChat chat) {
  this.jdbcTemplate = jdbcTemplate;
  this.toolCallLogRepository = toolCallLogRepository;
  this.chatUsageService = chatUsageService;
  this.objectMapper = objectMapper;
  this.chat = chat;
 }

 public FlowResult flow(String conversationId, String username, int page, int size, Set<String> types) {
  if (types == null || types.isEmpty()) types = DEFAULT_TYPES;

  Meta meta = loadMeta(conversationId, username);
  Stats stats = computeStats(conversationId, username);

  List<Event> all = new ArrayList<>();
  // SYSTEM 事件 — 展示该对话实际下发的 system prompt（动态拼装，含技能列表/知识库/工具列表）
  if (types.contains("SYSTEM")) {
   try {
    // 通过 IChat 接口调用 buildDynamicSystemPrompt — 不依赖 instanceof，
    // Spring 代理（Proxy / CGLIB）下 DefaultChat 仍能被识别为 IChat 实现。
    String sysPrompt = chat.buildDynamicSystemPrompt(username, null);
    if (sysPrompt != null && !sysPrompt.isBlank()) {
     Map<String, Object> sysData = new HashMap<>();
     sysData.put("content", sysPrompt);
     sysData.put("length", sysPrompt.length());
     // 锚到该对话 USER 第一条消息前 1ms（保证排序：SYSTEM 在 USER 之前）
     Instant firstUserTs = jdbcTemplate.query("select min(timestamp) from spring_ai_chat_memory where conversation_id = ? and type = 'USER'", rs -> rs.next() ? (rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant()) : null, conversationId);
     Instant sysTs = firstUserTs != null ? firstUserTs.minusMillis(1) : Instant.now();
     all.add(new Event("SYSTEM", sysTs, sysData));
    }
   } catch (Exception e) {
    log.warn("SYSTEM prompt build failed for {}: {}", conversationId, e.getMessage());
   }
  }
  if (types.contains("USER") || types.contains("ASSISTANT") || types.contains("TOOL_CALL") || types.contains("TOOL_RESULT")) {
   List<Event> evs = extractFromChatMemory(conversationId, username, types);
   log.info("extractFromChatMemory: {} events", evs.size());
   all.addAll(evs);
  }
  // 修复：tool_call_log 里的工具调用（ observation 写入）没在事件流里 — 从这里读
  if (types.contains("TOOL_CALL")) {
   List<Event> tc = loadToolCalls(conversationId);
   log.info("loadToolCalls: {} events, all.size() before addAll={}", tc.size(), all.size());
   all.addAll(tc);
   log.info("after addAll loadToolCalls: all.size()={}", all.size());
  }
  if (types.contains("TOOL_RESULT")) all.addAll(loadToolResults(conversationId));
  // 合并 SUBTASK + SCHEDULE 为 1 个 SCHEDULE_FIRE 事件
  // 每次 schedule 触发 = 1 sub-task 启动 + 1 execution 记录，1:1 配对 (ts 几乎一致)。
  // 前端只看到 1 个事件，含 prompt/result/status/taskName 等完整信息。
  if (types.contains("SUBTASK") || types.contains("SCHEDULE") || types.contains("SCHEDULE_FIRE")) {
   all.addAll(loadScheduleFires(username, conversationId));
  }

  // B2：USER/ASSISTANT 的 ts 改用 user_conversation.createdAt / updatedAt。
  // 原 DB 时间戳 (SPRING_AI_CHAT_MEMORY.timestamp DEFAULT CURRENT_TIMESTAMP) 是 Spring AI 流式
  // 处理"结束"时刻 — 不是用户发消息时刻。结果是 tool_call（10:50:20）反而早于 USER（10:50:23），
  // 时间线视觉顺序颠倒。改为用 conversation.createdAt 作为 USER 时刻，
  // conversation.updatedAt 作为最后一个 ASSISTANT 时刻。
  if (meta.createdAt() != null) {
   Instant firstUserTs = meta.createdAt();
   for (int i = 0; i < all.size(); i++) {
    if ("USER".equals(all.get(i).type())) {
     Event e = all.get(i);
     if (e.ts() != null && e.ts().isAfter(firstUserTs)) {
      all.set(i, new Event(e.type(), firstUserTs, e.data()));
     }
     firstUserTs = firstUserTs.plusMillis(1); // 多轮对话时每条 USER +1ms 拉开
    }
   }
  }
  if (meta.updatedAt() != null) {
   Instant lastAssistantTs = meta.updatedAt();
   for (int i = all.size() - 1; i >= 0; i--) {
    if ("ASSISTANT".equals(all.get(i).type())) {
     Event e = all.get(i);
     if (e.ts() != null && e.ts().isBefore(lastAssistantTs)) {
      all.set(i, new Event(e.type(), lastAssistantTs, e.data()));
     }
     lastAssistantTs = lastAssistantTs.minusMillis(1); // 多轮 ASSISTANT 倒数
     break; // 只改最后一个（用户感知的是对话的最终助手时刻）
    }
   }
  }

  all.sort(Comparator.comparing(Event::ts));
  int total = all.size();
  log.info("flow({}) all.size()={} types={}", conversationId, total, types);
  int fromIdx = Math.min(page * size, total);
  int toIdx = Math.min(fromIdx + size, total);
  List<Event> pageEvents = all.subList(fromIdx, toIdx);

  return new FlowResult(meta, stats, pageEvents, page, size, total, toIdx < total);
 }

 private Meta loadMeta(String conversationId, String username) {
  List<Meta> rows = jdbcTemplate.query("select conversation_id, title, created_at, updated_at, username " + "from user_conversation where conversation_id = ? and username = ?", (rs, n) -> new Meta(rs.getString("conversation_id"), rs.getString("title"), rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant(), rs.getString("username")), conversationId, username);
  return rows.isEmpty() ? new Meta(conversationId, null, null, null, username) : rows.get(0);
 }

 private Stats computeStats(String conversationId, String username) {
  // 工具调用数：优先看 loom_tool_call_log，没数据就 0（前的会话在 flow 里看历史，但 stats 卡片只算新数据）
  long toolCallCount = jdbcTemplate.queryForObject("select count(*) from loom_tool_call_log where conversation_id = ?", Long.class, conversationId);
  // 子任务数
  long subtaskCount = jdbcTemplate.queryForObject("select count(*) from loom_subtask_history where username = ? and conversation_id = ?", Long.class, username, conversationId);
  // 修复：定时任务声明数（之前查 execution 触发次数，新创建的 schedule 还没执行 → 0）
  long scheduleCount = jdbcTemplate.queryForObject("select count(*) from loom_scheduled_task " + "where username = ? and conversation_id = ?", Long.class, username, conversationId);
  // 错误数（tool 返回值含 error / subtask 失败）
  long errorCount = jdbcTemplate.queryForObject("select count(*) from loom_tool_call_log " + "where conversation_id = ? and result_is_error = true", Long.class, conversationId);
  errorCount += jdbcTemplate.queryForObject("select count(*) from loom_subtask_history " + "where username = ? and conversation_id = ? and status = 'FAILED'", Long.class, username, conversationId);
  errorCount += jdbcTemplate.queryForObject("select count(*) from loom_schedule_execution e " + "join loom_scheduled_task t on t.task_name = e.task_name " + "where t.username = ? and t.conversation_id = ? and e.success = false", Long.class, username, conversationId);

  ChatUsageService.ConversationUsage u = chatUsageService.byConversation(conversationId);
  return new Stats((int) u.callCount(), u.totalTokens(), toolCallCount, subtaskCount, scheduleCount, (int) errorCount);
 }

 /**
  * 从 chat_memory 提取 USER / ASSISTANT / TOOL_CALL / TOOL_RESULT 事件。
  * 优先用 loom_tool_call_log（+）覆盖 chat_memory 中的 tool call 信息。
  */
 private List<Event> extractFromChatMemory(String conversationId, String username, Set<String> types) {
  // H2 schema：SPRING_AI_CHAT_MEMORY(conversation_id, content, type, timestamp)
  // 没有 metadata 列。AssistantMessage / ToolResponseMessage 整体以 JSON
  // 序列化存在 content 里。H2 没有 JSON_VALUE/JSONPATH，Java 端 ObjectMapper 解析。
  List<ChatMemoryRow> rows = jdbcTemplate.query("select content, type, timestamp " + "from spring_ai_chat_memory where conversation_id = ? order by timestamp", (rs, n) -> new ChatMemoryRow(rs.getString("content"), rs.getString("type"), rs.getTimestamp("timestamp") == null ? null : rs.getTimestamp("timestamp").toInstant()), conversationId);

  // 预加载 + 的 tool call log（结构化 args/result/duration）
  Map<String, ToolCallLog> logByCallId = new HashMap<>();
  for (ToolCallLog l : toolCallLogRepository.findByConversationId(conversationId)) {
   logByCallId.put(l.toolCallId(), l);
  }

  List<Event> events = new ArrayList<>();
  // ：假设从 chat_memory 反推 metadata.thinking 失效（content 是纯文本），
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
      String text = contentNode != null ? contentNode.path("content").asText("") : (row.content() == null ? "" : row.content());
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
      // 原有尝试（保留兼容旧库）
      JsonNode thinking = contentNode.path("metadata").path("thinking");
      if (!thinking.isMissingNode() && !thinking.isNull()) {
       data.put("thinking", thinking.asText());
      }
     } else {
      data.put("content", row.content() == null ? "" : row.content());
     }
     // ：注入 loom_chat_reasoning 的最终思考文本（绑到第一条 ASSISTANT）
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
        events.add(new Event("TOOL_CALL", l != null ? l.createdAt() : row.timestamp(), tcData));
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
     // +：跳过 SPRING_AI_CHAT_MEMORY 的 TOOL 行（Spring AI 流式 chunk
     // 重复 emit 同一 tool_call_id，导致 chat_memory 有 20+ 行 TOOL 消息但
     // 实际只执行 2-4 次）。TOOL_RESULT 唯一来源改为 loom_tool_call_log
     // （loadToolResults 方法），id 用 wrap-* 与 tool_call_log 匹配。
     if (log.isDebugEnabled())
      log.debug("+ skipping chat_memory TOOL row id={}", contentNode != null ? contentNode.path("id").asText(null) : null);
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
  // 保留为底层方法（被 loadScheduleFires 调用），不直接生成 SUBTASK 事件。
  List<SubtaskRow> rows = jdbcTemplate.query("select subtask_id, prompt, status, started_at, finished_at, error_message, result_text " + "from loom_subtask_history where username = ? and conversation_id = ? order by started_at", (rs, n) -> new SubtaskRow(rs.getString("subtask_id"), rs.getString("prompt"), rs.getString("status"), rs.getLong("started_at"), rs.getLong("finished_at"), rs.getString("error_message"), rs.getString("result_text")), username, conversationId);
  List<Event> out = new ArrayList<>();
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

 /* 修复：从 loom_tool_call_log 读 tool_call 事件（ observation 写入的）。
 之前 conversation.html 的 tool_call 事件都从 chat_memory 来（type=TOOL），
 但 写的是 loom_tool_call_log，chat_memory 里没有这些行，所以事件流空。

 ⚠️ 第二轮修复：第一次实现里调 jdbcTemplate.query(sql, rowMapper, args) 但忽略返回值，
 返回的永远是空 List——RowMapper 被调用 N 次但每行对象被 GC。修法：直接接住返回值。
 （debug log 里能看到 "15 events" 是 RowMapper 调用次数，但 API 返回 0 个事件。） */
 private List<Event> loadToolCalls(String conversationId) {
  log.info("loadToolCalls query for convId='{}' length={}", conversationId, conversationId == null ? 0 : conversationId.length());
  try {
   List<Event> events = jdbcTemplate.query("select tool_call_id, tool_name, arguments_json, created_at " + "from loom_tool_call_log where conversation_id = ? order by created_at", (rs, n) -> {
    Map<String, Object> data = new HashMap<>();
    data.put("id", rs.getString("tool_call_id"));
    data.put("name", rs.getString("tool_name"));
    data.put("args", rs.getString("arguments_json"));
    data.put("source", "tool_call_log");
    return new Event("TOOL_CALL", rs.getTimestamp("created_at").toInstant(), data);
   }, conversationId);
   log.info("loadToolCalls({}): {} events", conversationId, events.size());
   return events;
  } catch (Exception e) {
   log.warn("loadToolCalls failed for {}: {}", conversationId, e.getMessage());
   return List.of();
  }
 }

 private List<Event> loadToolResults(String conversationId) {
  // 同 loadToolCalls 的修复 — 接住 jdbcTemplate.query 的返回值
  try {
   return jdbcTemplate.query("select tool_call_id, tool_name, result_text, result_is_error, created_at, duration_ms " + "from loom_tool_call_log where conversation_id = ? order by created_at", (rs, n) -> {
    Map<String, Object> data = new HashMap<>();
    data.put("id", rs.getString("tool_call_id"));
    data.put("name", rs.getString("tool_name"));
    data.put("result", rs.getString("result_text"));
    data.put("isError", rs.getBoolean("result_is_error"));
    long dur = rs.getLong("duration_ms");
    if (!rs.wasNull()) data.put("durationMs", dur);
    data.put("source", "tool_call_log");
    return new Event("TOOL_RESULT", rs.getTimestamp("created_at").toInstant(), data);
   }, conversationId);
  } catch (Exception e) {
   log.warn("loadToolResults failed for {}: {}", conversationId, e.getMessage());
   return List.of();
  }
 }

 private List<Event> loadSchedules(String username, String conversationId) {
  // 保留为底层方法（被 loadScheduleFires 调用），不直接生成 SCHEDULE 事件。
  List<ScheduleRow> rows = jdbcTemplate.query("select t.task_name, t.prompt, e.fire_time, e.duration_ms, e.success, e.error_message " + "from loom_scheduled_task t " + "join loom_schedule_execution e on t.task_name = e.task_name " + "where t.username = ? and t.conversation_id = ? order by e.fire_time", (rs, n) -> new ScheduleRow(rs.getString("task_name"), rs.getString("prompt"), rs.getTimestamp("fire_time") == null ? null : rs.getTimestamp("fire_time").toInstant(), rs.getLong("duration_ms"), rs.getBoolean("success"), rs.getString("error_message")), username, conversationId);
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

 /**
  * 合并 SUBTASK + SCHEDULE 为 1 个 SCHEDULE_FIRE 事件。
  *
  * <p>每次 schedule 触发都产生 1 条 {@code loom_subtask_history}（sub-task 启动）
  * 和 1 条 {@code loom_schedule_execution}（执行记录），两者 timestamp 几乎一致（实测相差几毫秒）。
  * 之前作为两个独立事件展示，用户看着像"两个独立事件"，分不清因果关系。
  * 合并后：1 个 SCHEDULE_FIRE 事件 = 1 次 schedule 触发，含 taskName / sub-task prompt / result /
  * status / 耗时 / 错误等完整信息。
  */
 private List<Event> loadScheduleFires(String username, String conversationId) {
  // 1. 拉 schedule 触发记录
  List<ScheduleRow> executions = jdbcTemplate.query("select t.task_name, t.prompt, e.fire_time, e.duration_ms, e.success, e.error_message " + "from loom_scheduled_task t " + "join loom_schedule_execution e on t.task_name = e.task_name " + "where t.username = ? and t.conversation_id = ? order by e.fire_time", (rs, n) -> new ScheduleRow(rs.getString("task_name"), rs.getString("prompt"), rs.getTimestamp("fire_time") == null ? null : rs.getTimestamp("fire_time").toInstant(), rs.getLong("duration_ms"), rs.getBoolean("success"), rs.getString("error_message")), username, conversationId);
  // 2. 拉 sub-task 记录（按 started_at 排序）
  List<SubtaskRow> subtasks = jdbcTemplate.query("select subtask_id, prompt, status, started_at, finished_at, error_message, result_text " + "from loom_subtask_history where username = ? and conversation_id = ? order by started_at", (rs, n) -> new SubtaskRow(rs.getString("subtask_id"), rs.getString("prompt"), rs.getString("status"), rs.getLong("started_at"), rs.getLong("finished_at"), rs.getString("error_message"), rs.getString("result_text")), username, conversationId);
  // 3. 按 fire_time 接近度（±5 秒）配对
  List<Event> out = new ArrayList<>();
  for (ScheduleRow exec : executions) {
   SubtaskRow matched = null;
   if (exec.fireTime() != null) {
    long fireMs = exec.fireTime().toEpochMilli();
    for (SubtaskRow sub : subtasks) {
     if (matched != null) break;
     long subMs = sub.startedAt();
     if (Math.abs(subMs - fireMs) <= 5000) {
      matched = sub;
     }
    }
   }
   Map<String, Object> data = new HashMap<>();
   data.put("taskName", exec.taskName());
   // shortName = 去掉 loom-sched-{user}-{convId}- 前缀
   String tn = exec.taskName();
   int lastDash = tn.lastIndexOf('-');
   if (lastDash > 0) data.put("shortName", tn.substring(lastDash + 1));
   else data.put("shortName", tn);
   data.put("prompt", exec.prompt());
   data.put("fireTime", exec.fireTime());
   data.put("durationMs", exec.durationMs());
   data.put("success", exec.success());
   data.put("error", exec.errorMessage());
   if (matched != null) {
    data.put("subtaskId", matched.subtaskId());
    data.put("subtaskStatus", matched.status());
    data.put("subtaskResult", matched.resultText());
    data.put("subtaskDurationMs", matched.finishedAt() - matched.startedAt());
   }
   out.add(new Event("SCHEDULE_FIRE", exec.fireTime(), data));
  }
  return out;
 }

 public record Meta(String conversationId, String title, Instant createdAt, Instant updatedAt, String username) {
 }

 public record Stats(int callCount, long totalTokens, long toolCallCount, long subtaskCount, long scheduleCount,
                     int errorCount) {
 }

 public record Event(String type, Instant ts, Map<String, Object> data) {
 }

 public record FlowResult(Meta meta, Stats stats, List<Event> events, int page, int size, long total,
                          boolean hasMore) {
 }

 private record ChatMemoryRow(String content, String type, Instant timestamp) {
 }

 private record SubtaskRow(String subtaskId, String prompt, String status, long startedAt, long finishedAt,
                           String errorMessage, String resultText) {
 }

 private record ScheduleRow(String taskName, String prompt, Instant fireTime, long durationMs, boolean success,
                            String errorMessage) {
 }
}
