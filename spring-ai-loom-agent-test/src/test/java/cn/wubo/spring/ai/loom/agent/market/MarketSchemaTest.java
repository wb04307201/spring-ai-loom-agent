package cn.wubo.spring.ai.loom.agent.market;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MarketSchemaTest {

    @Autowired JdbcTemplate jdbc;

    private boolean columnExists(String table, String column) {
        Integer cnt = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE UPPER(TABLE_NAME)=UPPER(?) AND UPPER(COLUMN_NAME)=UPPER(?)",
            Integer.class, table, column);
        return cnt != null && cnt > 0;
    }

    @Test
    void marketSkillHasNewColumns() {
        assertTrue(columnExists("market_skill", "is_official"));
        assertTrue(columnExists("market_skill", "featured_rank"));
        assertTrue(columnExists("market_skill", "category"));
        assertTrue(columnExists("market_skill", "created_by_kind"));
    }

    @Test
    void marketKnowledgeHasNewColumns() {
        assertTrue(columnExists("loom_market_knowledge", "is_official"));
        assertTrue(columnExists("loom_market_knowledge", "featured_rank"));
        assertTrue(columnExists("loom_market_knowledge", "category"));
        assertTrue(columnExists("loom_market_knowledge", "created_by_kind"));
    }

    @Test
    void newTablesExist() {
        Integer s = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME)='MARKET_SKILL_STATS'",
            Integer.class);
        Integer r = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME)='MARKET_SKILL_REVIEW'",
            Integer.class);
        Integer kbs = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME)='LOOM_MARKET_KNOWLEDGE_STATS'",
            Integer.class);
        Integer kbr = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME)='LOOM_MARKET_KNOWLEDGE_REVIEW'",
            Integer.class);
        Integer ann = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME)='MARKET_CONTENT_ANNOUNCEMENT'",
            Integer.class);
        assertEquals(1, s);   assertEquals(1, r);
        assertEquals(1, kbs); assertEquals(1, kbr);
        assertEquals(1, ann);
    }

    @Test
    void userKnowledgeAccessCheckIndexExists() {
        Integer idx = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE UPPER(TABLE_NAME)='LOOM_USER_KNOWLEDGE' AND UPPER(INDEX_NAME)='IDX_USER_KNOWLEDGE_ACCESS_CHECK'",
            Integer.class);
        assertEquals(1, idx);
    }

    @Test
    void userKnowledgeHasAccessCount() {
        assertTrue(columnExists("loom_user_knowledge", "access_count"));
    }

    @Test
    void loomMarketKnowledgeTagTableExists() {
        Integer t = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME)='LOOM_MARKET_KNOWLEDGE_TAG'",
            Integer.class);
        assertEquals(1, t);
    }

    // ==== M4 T4: skill tag system (mirror of the KB tag table) ====

    @Test
    void marketSkillTagTableExists() {
        Integer t = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME)='MARKET_SKILL_TAG'",
            Integer.class);
        assertEquals(1, t);
        assertTrue(columnExists("market_skill_tag", "market_skill_id"));
        assertTrue(columnExists("market_skill_tag", "tag"));
    }

    @Test
    void marketSkillTagIndexExists() {
        Integer idx = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE UPPER(TABLE_NAME)='MARKET_SKILL_TAG' AND UPPER(INDEX_NAME)='IDX_MARKET_SKILL_TAG'",
            Integer.class);
        assertEquals(1, idx);
    }

    @Test
    void marketSkillTagHasExactlyOneFk() {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
            "WHERE UPPER(TABLE_NAME)='MARKET_SKILL_TAG' AND CONSTRAINT_TYPE='FOREIGN KEY'",
            Integer.class);
        assertEquals(1, n);
    }

    // ==== M3+ technical debt cleanup (spec § 4.1 + § 4.2) ====

    @Test
    void marketSkillHasUpdatedAt() {
        assertTrue(columnExists("market_skill", "updated_at"));
    }

    @Test
    void loomUserKnowledgeHasUpdatedAt() {
        assertTrue(columnExists("loom_user_knowledge", "updated_at"));
    }

    @Test
    void marketContentAnnouncementMarketIdIsVarchar() {
        String type = jdbc.queryForObject(
            "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE UPPER(TABLE_NAME)='MARKET_CONTENT_ANNOUNCEMENT' AND UPPER(COLUMN_NAME)='MARKET_ID'",
            String.class);
        assertEquals("CHARACTER VARYING", type);
        Integer size = jdbc.queryForObject(
            "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE UPPER(TABLE_NAME)='MARKET_CONTENT_ANNOUNCEMENT' AND UPPER(COLUMN_NAME)='MARKET_ID'",
            Integer.class);
        assertNotNull(size);
        assertTrue(size >= 36);
    }

    @Test
    void loomMarketKnowledgeStatsMarketIdIsVarchar() {
        String type = jdbc.queryForObject(
            "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE UPPER(TABLE_NAME)='LOOM_MARKET_KNOWLEDGE_STATS' AND UPPER(COLUMN_NAME)='MARKET_ID'",
            String.class);
        assertEquals("CHARACTER VARYING", type);
        Integer size = jdbc.queryForObject(
            "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE UPPER(TABLE_NAME)='LOOM_MARKET_KNOWLEDGE_STATS' AND UPPER(COLUMN_NAME)='MARKET_ID'",
            Integer.class);
        assertNotNull(size);
        assertTrue(size >= 36);
    }

    @Test
    void loomMarketKnowledgeReviewMarketIdIsVarchar() {
        String type = jdbc.queryForObject(
            "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE UPPER(TABLE_NAME)='LOOM_MARKET_KNOWLEDGE_REVIEW' AND UPPER(COLUMN_NAME)='MARKET_ID'",
            String.class);
        assertEquals("CHARACTER VARYING", type);
        Integer size = jdbc.queryForObject(
            "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE UPPER(TABLE_NAME)='LOOM_MARKET_KNOWLEDGE_REVIEW' AND UPPER(COLUMN_NAME)='MARKET_ID'",
            Integer.class);
        assertNotNull(size);
        assertTrue(size >= 36);
    }

    // Regression guard: ensure exactly 1 FK on market_id, no duplicate.
    // The T1.1 first pass silently added a 2nd FK because DROP CONSTRAINT
    // targeted the wrong name (CONSTRAINT_F is unrelated PK). Removing the
    // unnecessary DROP/ADD block restores the original single FK.

    @Test
    void loomMarketKnowledgeStatsHasExactlyOneMarketIdFk() {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
            "WHERE UPPER(TABLE_NAME)='LOOM_MARKET_KNOWLEDGE_STATS' AND CONSTRAINT_TYPE='FOREIGN KEY'",
            Integer.class);
        assertEquals(1, n);
    }

    @Test
    void loomMarketKnowledgeReviewHasExactlyOneMarketIdFk() {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
            "WHERE UPPER(TABLE_NAME)='LOOM_MARKET_KNOWLEDGE_REVIEW' AND CONSTRAINT_TYPE='FOREIGN KEY'",
            Integer.class);
        assertEquals(1, n);
    }
}
