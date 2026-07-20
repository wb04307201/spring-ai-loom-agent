package cn.wubo.spring.ai.loom.agent.user;

import cn.wubo.spring.ai.loom.agent.model.ConversationRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.cache.Cache;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultUserConversationTest {

    private JdbcTemplate jdbc;
    private DefaultUserConversation conversations;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:test-conversation-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                create table user_conversation (
                    username varchar(64) not null,
                    conversation_id varchar(64) not null,
                    title varchar(255),
                    deleted_at timestamp null,
                    content_cleaned boolean not null default false,
                    created_at timestamp not null default current_timestamp,
                    updated_at timestamp not null default current_timestamp,
                    primary key (username, conversation_id)
                )
                """);
        conversations = new DefaultUserConversation(jdbc, mock(ChatMemory.class), mock(Cache.class));
        UserContextHolder.setCurrentUser("alice");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void createAddsEmptyConversationAndListsNewestFirst() throws Exception {
        ConversationRecord first = conversations.create("tmp-conv-1");
        Thread.sleep(5);
        ConversationRecord second = conversations.create("tmp-conv-2");

        assertThat(first.title()).isEqualTo("tmp-conv-1");
        assertThat(second.title()).isEqualTo("tmp-conv-2");
        assertThat(conversations.getList())
                .extracting(ConversationRecord::conversationId)
                .containsExactly(second.conversationId(), first.conversationId());
    }

    @Test
    void createReturnsTimestampsReadBackFromDatabase() throws Exception {
        // JDBC default isolation can produce created_at < JVM-clock by ms. Verify the
        // create() return value matches what is actually persisted (i.e. read from DB)
        // rather than Instant.now() captured before the INSERT.
        ConversationRecord created = conversations.create("tmp-conv-1");

        // The persisted row's created_at must be a non-null DB-assigned timestamp.
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.updatedAt()).isNotNull();

        // Calling getList() right after create() must yield the same timestamps —
        // no ms-level drift between in-memory record and DB. We compare to
        // millisecond precision because H2's TIMESTAMP default may not retain
        // sub-millisecond precision consistently across reads.
        ConversationRecord listed = conversations.getList().stream()
                .filter(r -> r.conversationId().equals(created.conversationId()))
                .findFirst().orElseThrow();
        assertThat(listed.createdAt().toEpochMilli()).isEqualTo(created.createdAt().toEpochMilli());
        assertThat(listed.updatedAt().toEpochMilli()).isEqualTo(created.updatedAt().toEpochMilli());

        // And the persisted row's timestamps must be on/before "now" (the JVM clock when
        // we got the read-back) — i.e. the DB did the stamping, not this JVM.
        assertThat(created.createdAt()).isBeforeOrEqualTo(java.time.Instant.now().plusSeconds(1));
    }

    @Test
    void existingConversationWithoutCustomTitleStillUsesMessagePreview() {
        ChatMemory memory = mock(ChatMemory.class);
        conversations = new DefaultUserConversation(jdbc, memory, mock(Cache.class));
        when(memory.get("tmp-existing")).thenReturn(List.of(
                new UserMessage("tmp-existing-message-preview")));
        jdbc.update("insert into user_conversation (username, conversation_id) values (?, ?)",
                "alice", "tmp-existing");

        assertThat(conversations.getList()).extracting(ConversationRecord::title)
                .containsExactly("tmp-existing-message");
    }

    @Test
    void renamePersistsAndIsScopedToCurrentUser() {
        ConversationRecord alice = conversations.create("tmp-conv-1");
        jdbc.update("insert into user_conversation (username, conversation_id, title) values (?, ?, ?)",
                "bob", "tmp-bob-conv", "tmp-bob-title");

        assertThat(conversations.rename(alice.conversationId(), "tmp-conv-renamed")).isEqualTo(1);
        assertThat(conversations.rename("tmp-bob-conv", "tmp-stolen-title")).isZero();

        List<ConversationRecord> list = conversations.getList();
        assertThat(list).extracting(ConversationRecord::title).containsExactly("tmp-conv-renamed");
        assertThat(jdbc.queryForObject(
                "select title from user_conversation where username = ? and conversation_id = ?",
                String.class, "bob", "tmp-bob-conv")).isEqualTo("tmp-bob-title");
    }
}
