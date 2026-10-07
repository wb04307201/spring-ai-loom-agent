package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.model.ToolCallLog;
import cn.wubo.spring.ai.loom.agent.tool.IToolCallLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@code loom_tool_call_log} 里的工具调用注入历史消息列表。
 *
 * <p><b>为什么需要这个类</b>：主聊天界面回看历史走 {@code GET /conversation/{id}}，
 * 数据源是 {@code SPRING_AI_CHAT_MEMORY}。这张表**没有 metadata 列**
 * （schema 仅 conversation_id / content / type / timestamp / sequence_id），
 * 工具调用信息完全不在其中 —— 所以历史里 askUser 卡片与子任务 chip 会消失。
 *
 * <p><b>注入方式</b>：仿 {@link ChatReasoningHistory#attachThinking} 的模式，
 * 但工具调用不是"附加到某条消息的 metadata"，而是**独立条目**（一个工具调用
 * 有自己的参数、结果、耗时、错误状态）。因此本类返回独立的条目列表，
 * 由调用方按时间戳与消息合并排序。
 *
 * <p><b>条目形态</b>（前端按 {@code messageType} 分派渲染）：
 * <pre>{@code
 * { "messageType": "TOOL_CALL",
 *   "toolName": "askUser", "toolCallId": "wrap-...",
 *   "arguments": { ... },        // 已解析为 Map，解析失败时降级为 {"_raw": "..."}
 *   "result": "...", "isError": false, "durationMs": 97817,
 *   "truncated": false, "createdAt": "2026-10-06T10:00:00Z" }
 * }</pre>
 *
 * <p><b>截断策略</b>：{@code result} 上限 {@value #MAX_RESULT_CHARS} 字符、
 * {@code arguments} 上限 {@value #MAX_ARGS_CHARS} 字符，超出则截断并置
 * {@code truncated=true}。{@code askUser} 的 {@code optionsJson} **豁免截断** ——
 * 它是卡片选项的唯一来源，截断会导致选项丢失。此策略与
 * {@code loom_tool_call_log} 写入时的 64KB 截断同构（多一层防御）。
 */
public final class ToolCallHistory {

    private static final Logger log = LoggerFactory.getLogger(ToolCallHistory.class);

    /** result 截断上限。sequentialthinking 单条可达数 KB，7 条就能撑爆响应。 */
    static final int MAX_RESULT_CHARS = 2_048;

    /** arguments 整体触发截断的阈值（askUser 的 optionsJson 豁免）。 */
    static final int MAX_ARGS_CHARS = 1_024;

    /** 单个参数字段截断后的目标长度。必须小于 {@link #MAX_ARGS_CHARS}，否则整体超限时单字段仍可能逃过截断。 */
    static final int MAX_FIELD_CHARS = 256;

    /** 前端据此分派渲染器。 */
    public static final String MESSAGE_TYPE = "TOOL_CALL";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolCallHistory() {
    }

    /**
     * 读出会话的工具调用，转成可直接渲染的条目列表。
     *
     * @param conversationId 会话 id
     * @param repo           tool call log 仓库
     * @return 条目列表；无记录或读取失败时返回空列表（不抛异常，降级为"无工具调用"）
     */
    public static List<Map<String, Object>> inject(String conversationId, IToolCallLogRepository repo) {
        if (conversationId == null || conversationId.isBlank() || repo == null) return List.of();
        try {
            List<ToolCallLog> rows = repo.findByConversationId(conversationId);
            if (rows == null || rows.isEmpty()) return List.of();
            List<Map<String, Object>> out = new ArrayList<>(rows.size());
            for (ToolCallLog r : rows) out.add(toEntry(r));
            return out;
        } catch (Exception e) {
            // 与 attachThinking 同样的降级策略：注入失败不影响历史回看主流程。
            // 注意打异常类型 —— 只打 getMessage() 时 NPE/CME 这类会输出 "err=null"，
            // 排查时拿不到任何线索（本方法曾被 ConcurrentModificationException 吞过）。
            log.warn("注入工具调用失败，按无工具调用返回: conv={} err={}: {}",
                    conversationId, e.getClass().getSimpleName(), e.getMessage());
            return List.of();
        }
    }

    /**
     * 测试友好的重载：跳过消息配对，直接按 conversationId 注入。
     *
     * @param conversationId 会话 id
     * @param repo           tool call log 仓库
     */
    static List<Map<String, Object>> inject(List<Message> messages, IToolCallLogRepository repo) {
        // messages 不参与配对：chat memory 无 metadata 列，无法按 tool_call_id 关联，
        // 故工具调用以独立条目注入，由前端按 createdAt 排序合并。
        // 生产路径见 inject(String, IToolCallLogRepository)。
        return inject(TEST_CONVERSATION_ID, repo);
    }

    /** 测试假仓库使用的固定会话 id。 */
    static final String TEST_CONVERSATION_ID = "conv-1";

    private static Map<String, Object> toEntry(ToolCallLog r) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("messageType", MESSAGE_TYPE);
        e.put("toolName", r.toolName());
        e.put("toolCallId", r.toolCallId());
        e.put("arguments", parseArgs(r.toolName(), r.argumentsJson()));
        e.put("result", truncate(r.resultText(), MAX_RESULT_CHARS));
        e.put("isError", r.resultIsError());
        e.put("durationMs", r.durationMs() == null ? 0L : r.durationMs());
        e.put("truncated", isTruncated(r.resultText(), MAX_RESULT_CHARS));
        e.put("createdAt", r.createdAt() == null ? null : r.createdAt().toString());
        return e;
    }

    /**
     * 解析 argumentsJson 为 Map。
     *
     * <p><b>关键顺序</b>：必须**先完整解析、再按字段截断** —— 反过来（先截断再解析）
     * 会让截断后的 JSON 语法不完整、解析失败，{@code askUser} 的 {@code optionsJson}
     * 也就还原不出来（卡片选项丢失）。本方法曾被变异测试抓出这个顺序错误。
     *
     * <p>解析失败时降级为 {@code {"_raw": <原文>}}，保证前端始终能拿到可渲染结构。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseArgs(String toolName, String json) {
        if (json == null || json.isBlank()) return Map.of();

        Map<String, Object> parsed = null;
        try {
            Object o = MAPPER.readValue(json, Object.class);
            if (o instanceof Map<?, ?> m) parsed = new HashMap<>((Map<String, Object>) m);
        } catch (Exception e) {
            log.debug("argumentsJson 解析失败，降级为 _raw: tool={} err={}", toolName, e.getMessage());
        }

        if (parsed == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("_raw", truncate(json, MAX_ARGS_CHARS));
            out.put("_parseError", true);
            return out;
        }

        // 已完整解析 —— 现在才做按字段截断。
        // askUser 的 optionsJson 豁免：它是卡片选项的唯一来源，截断会导致选项丢失。
        //
        // 阈值语义（两处容易混淆）：
        //   MAX_ARGS_CHARS  = 整个 argumentsJson 的触发阈值（超过它才进入截断流程）
        //   MAX_FIELD_CHARS = 单个字段的截断目标长度
        // 必须分开 —— 否则"整体超限但某字段恰好低于整体阈值"时该字段不会被截断。
        //
        // 注意：不能在遍历 entrySet 的同时 put 新 key（ConcurrentModificationException，
        // 曾被 catch 吞成"注入失败 err=null"）。故先收集、后写入。
        if (json.length() > MAX_ARGS_CHARS) {
            boolean truncated = false;
            List<String> toTruncate = new ArrayList<>();
            for (Map.Entry<String, Object> en : parsed.entrySet()) {
                if (isExemptFromTruncation(toolName, en.getKey())) continue;
                if (en.getValue() instanceof String s && s.length() > MAX_FIELD_CHARS) {
                    toTruncate.add(en.getKey());
                }
            }
            for (String key : toTruncate) {
                parsed.put(key, truncate(String.valueOf(parsed.get(key)), MAX_FIELD_CHARS));
                truncated = true;
            }
            if (truncated) parsed.put("_truncated", true);
        }
        return parsed;
    }

    /**
     * 豁免截断的字段。
     *
     * <p>{@code askUser.optionsJson} 是卡片选项的唯一来源 —— 截断后前端渲染不出选项，
     * 卡片会退化成空白。它的体积可控（2-4 个选项），不需要防御性截断。
     */
    private static boolean isExemptFromTruncation(String toolName, String fieldName) {
        return "askUser".equals(toolName) && "optionsJson".equals(fieldName);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean isTruncated(String s, int max) {
        return s != null && s.length() > max;
    }
}
