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
}
