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

    @Autowired cn.wubo.spring.ai.loom.agent.skill.DefaultSkillMarketService skillSvc;
    @Autowired cn.wubo.spring.ai.loom.agent.knowledge.DefaultKnowledgeMarketService kbSvc;

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

    @Test
    void adminCreateApprovedSkill() {
        long id = skillSvc.createApproved("admin1", new MarketCreateRequest(
            "adm-" + System.nanoTime(), "d", "c", "cat-x")).id();
        String status = jdbc.queryForObject("SELECT status FROM market_skill WHERE id=?", String.class, id);
        String kind = jdbc.queryForObject("SELECT created_by_kind FROM market_skill WHERE id=?", String.class, id);
        String by = jdbc.queryForObject("SELECT reviewed_by FROM market_skill WHERE id=?", String.class, id);
        assertEquals("APPROVED", status);
        assertEquals("ADMIN", kind);
        assertEquals("admin1", by);
    }

    @Test
    void adminCreateApprovedKnowledge() {
        String id = kbSvc.createApproved("admin1", new MarketCreateRequest(
            "admkb-" + System.nanoTime(), "d", null, "cat-y")).id();
        String status = jdbc.queryForObject("SELECT status FROM loom_market_knowledge WHERE id=?", String.class, id);
        assertEquals("APPROVED", status);
    }
}
