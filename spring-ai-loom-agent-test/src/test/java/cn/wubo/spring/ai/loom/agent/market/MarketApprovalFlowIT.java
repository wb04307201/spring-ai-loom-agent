package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = LoomAgentTestApplication.class)
class MarketApprovalFlowIT {

    @Autowired JdbcTemplate jdbc;

    @Test
    void archiveTablesExist() {
        Integer skillCols = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_name='MARKET_SKILL_ARCHIVE'",
            Integer.class);
        Integer kbCols = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_name='LOOM_MARKET_KNOWLEDGE_ARCHIVE'",
            Integer.class);
        assertTrue(skillCols != null && skillCols >= 11, "market_skill_archive 应已建表");
        assertTrue(kbCols != null && kbCols >= 10, "loom_market_knowledge_archive 应已建表");
    }
}
