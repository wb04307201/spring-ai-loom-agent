package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.skill.DefaultSkillMarketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = LoomAgentTestApplication.class)
class DefaultSkillMarketServiceIT {

    @Autowired DefaultSkillMarketService svc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createAppendsRowToMarketSkill() {
        long id = svc.create("alice", new MarketCreateRequest(
            "name-" + System.nanoTime(),
            "desc",
            "content",
            "category-x"
        )).id();
        assertTrue(id > 0);
        String status = jdbc.queryForObject("SELECT status FROM market_skill WHERE id=?", String.class, id);
        assertEquals("PENDING", status);
    }

    @Test
    void approveFlipsStatus() {
        long id = svc.create("alice", new MarketCreateRequest(
            "appr-" + System.nanoTime(), "d", "c", null
        )).id();
        svc.approve(id, "admin1");
        String s = jdbc.queryForObject("SELECT status FROM market_skill WHERE id=?", String.class, id);
        assertEquals("APPROVED", s);
        String reviewedBy = jdbc.queryForObject("SELECT reviewed_by FROM market_skill WHERE id=?", String.class, id);
        assertEquals("admin1", reviewedBy);
    }

    @Test
    void rejectWithoutCommentThrows() {
        long id = svc.create("alice", new MarketCreateRequest(
            "rej-" + System.nanoTime(), "d", "c", null
        )).id();
        assertThrows(IllegalArgumentException.class, () -> svc.reject(id, "admin1", ""));
    }

    @Test
    void setOfficialTogglesFlag() {
        long id = svc.create("alice", new MarketCreateRequest(
            "off-" + System.nanoTime(), "d", "c", null
        )).id();
        svc.setOfficial(id, true, "admin1");
        Boolean official = jdbc.queryForObject("SELECT is_official FROM market_skill WHERE id=?", Boolean.class, id);
        assertEquals(true, official);
    }
}
