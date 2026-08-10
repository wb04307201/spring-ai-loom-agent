package cn.wubo.spring.ai.loom.agent.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link LastChunkMessageChatMemoryAdvisor} — the most breakage-prone
 * component in the streaming pipeline. Pins the contract:
 * <ul>
 *   <li>All upstream chunks pass through downstream IMMEDIATELY (no buffering).</li>
 *   <li>chat_memory is written exactly once on stream completion (1 USER + 1 ASSISTANT).</li>
 *   <li>Empty stream → no write.</li>
 *   <li>Upstream error → no partial write.</li>
 *   <li>Empty conversationId → silently skipped.</li>
 * </ul>
 */
class LastChunkMessageChatMemoryAdvisorTest {

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private LastChunkMessageChatMemoryAdvisor advisor;
    private String convId;

    @BeforeEach
    void setUp() {
        // Each test gets its own H2 DB to avoid cross-test pollution.
        String url = "jdbc:h2:mem:test-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new DriverManagerDataSource(url, "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE spring_ai_chat_memory (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                conversation_id VARCHAR(255),
                content CLOB,
                type VARCHAR(16)
            )
            """);
        advisor = new LastChunkMessageChatMemoryAdvisor(jdbcTemplate, 0);
        convId = "conv-" + System.nanoTime();
    }

    @AfterEach
    void tearDown() {
        if (dataSource instanceof DriverManagerDataSource ds) {
            try { ds.getConnection().close(); } catch (Exception ignored) {}
        }
    }

    /** Builds a ChatClientResponse whose assistant text is `text`. */
    private static ChatClientResponse resp(String text, Map<String, Object> ctx) {
        AssistantMessage msg = new AssistantMessage(text);
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(msg)));
        return new ChatClientResponse(chatResponse, ctx);
    }

    /** Builds a minimal ChatClientRequest carrying a USER message and the conv id. */
    private static ChatClientRequest userRequest(String userText, String convId) {
        UserMessage userMsg = new UserMessage(userText);
        Prompt prompt = new Prompt(List.of(userMsg));
        Map<String, Object> context = new HashMap<>();
        context.put("ChatMemory.CONVERSATION_ID", convId);
        return new ChatClientRequest(prompt, context);
    }

    /** Wraps a Flux as a StreamAdvisorChain. StreamAdvisorChain has 3 abstract methods; this impl returns empty advisors and identity copy. */
    private static StreamAdvisorChain chain(Flux<ChatClientResponse> flux) {
        return new StreamAdvisorChain() {
            @Override public Flux<ChatClientResponse> nextStream(ChatClientRequest req) { return flux; }
            @Override public List<org.springframework.ai.chat.client.advisor.api.StreamAdvisor> getStreamAdvisors() { return Collections.emptyList(); }
            @Override public StreamAdvisorChain copy(org.springframework.ai.chat.client.advisor.api.StreamAdvisor a) { return this; }
        };
    }

    @Test
    void chunks_pass_through_immediately_with_no_buffering() {
        // 验证：每个 chunk 都立即到达下游 + 上游完成后才写库
        Flux<ChatClientResponse> upstream = Flux.just(
                resp("Hello", Map.of("ChatMemory.CONVERSATION_ID", convId)),
                resp("Hello world", Map.of("ChatMemory.CONVERSATION_ID", convId)),
                resp("Hello world!", Map.of("ChatMemory.CONVERSATION_ID", convId))
        );
        List<String> seen = new ArrayList<>();
        advisor.adviseStream(userRequest("hi", convId), chain(upstream))
                .doOnNext(r -> seen.add(r.chatResponse().getResult().getOutput().getText()))
                .blockLast();

        // 顺序保持
        assertThat(seen).containsExactly("Hello", "Hello world", "Hello world!");
        // 写库发生在流完成后
        Long rows = jdbcTemplate.queryForObject(
                "select count(*) from spring_ai_chat_memory where conversation_id = ?", Long.class, convId);
        assertThat(rows).isEqualTo(2L); // 1 USER + 1 ASSISTANT
    }

    @Test
    void writes_exactly_one_user_and_one_assistant_row_per_conversation() {
        Flux<ChatClientResponse> upstream = Flux.just(
                resp("part-1 ", Map.of("ChatMemory.CONVERSATION_ID", convId)),
                resp("part-2 ", Map.of("ChatMemory.CONVERSATION_ID", convId)),
                resp("part-3", Map.of("ChatMemory.CONVERSATION_ID", convId))
        );
        advisor.adviseStream(userRequest("user says hi", convId), chain(upstream))
                .blockLast();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select type, content from spring_ai_chat_memory where conversation_id = ? order by id", convId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("type")).isEqualTo("USER");
        assertThat(rows.get(0).get("content")).isEqualTo("user says hi");
        assertThat(rows.get(1).get("type")).isEqualTo("ASSISTANT");
        assertThat(rows.get(1).get("content")).isEqualTo("part-1 part-2 part-3");
    }

    @Test
    void empty_stream_writes_nothing() {
        advisor.adviseStream(userRequest("anything", convId), chain(Flux.empty())).blockLast();

        Long rows = jdbcTemplate.queryForObject(
                "select count(*) from spring_ai_chat_memory where conversation_id = ?", Long.class, convId);
        assertThat(rows).isZero();
    }

    @Test
    void upstream_error_writes_nothing() {
        Flux<ChatClientResponse> upstream = Flux.concat(
                Flux.just(resp("partial", Map.of("ChatMemory.CONVERSATION_ID", convId))),
                Flux.error(new RuntimeException("upstream blew up"))
        );
        try {
            advisor.adviseStream(userRequest("user", convId), chain(upstream)).blockLast();
        } catch (Exception ignored) {}

        // 上游 error → doOnComplete 不触发 → 0 行写入（避免 partial text 持久化）
        Long rows = jdbcTemplate.queryForObject(
                "select count(*) from spring_ai_chat_memory where conversation_id = ?", Long.class, convId);
        assertThat(rows).isZero();
    }

    @Test
    void empty_conversation_id_skips_write() {
        advisor.adviseStream(userRequest("user", convId),
                chain(Flux.just(resp("text", new HashMap<>())))) // no CONVERSATION_ID
                .blockLast();

        Long rows = jdbcTemplate.queryForObject(
                "select count(*) from spring_ai_chat_memory", Long.class);
        assertThat(rows).isZero();
    }

    @Test
    void downstream_sees_chunks_in_order() {
        Flux<ChatClientResponse> upstream = Flux.just(
                resp("a", Map.of("ChatMemory.CONVERSATION_ID", convId)),
                resp("b", Map.of("ChatMemory.CONVERSATION_ID", convId)),
                resp("c", Map.of("ChatMemory.CONVERSATION_ID", convId)),
                resp("d", Map.of("ChatMemory.CONVERSATION_ID", convId))
        );

        List<String> seen = new ArrayList<>();
        advisor.adviseStream(userRequest("u", convId), chain(upstream))
                .doOnNext(r -> seen.add(r.chatResponse().getResult().getOutput().getText()))
                .blockLast();

        assertThat(seen).containsExactly("a", "b", "c", "d");
    }
}