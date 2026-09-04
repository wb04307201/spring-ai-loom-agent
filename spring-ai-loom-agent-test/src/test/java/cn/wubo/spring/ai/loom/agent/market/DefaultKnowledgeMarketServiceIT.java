package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.knowledge.DefaultKnowledgeMarketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 镜像 {@link DefaultSkillMarketServiceIT},表换成 {@code loom_market_knowledge}。
 *
 * <p>唯一结构性差异:{@code loom_market_knowledge.id} 是 {@code VARCHAR(36)} UUID,
 * 不是 {@code BIGINT},因此这里断言的是 String 主键,并调用服务的 String 主键孪生方法。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
class DefaultKnowledgeMarketServiceIT {

    @Autowired DefaultKnowledgeMarketService svc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createAppendsRowToMarketKnowledge() {
        String id = svc.create("alice", new MarketCreateRequest(
            "name-" + System.nanoTime(),
            "desc",
            "content",
            "category-x"
        )).id();
        assertNotNull(id);
        assertFalse(id.isBlank());
        String status = jdbc.queryForObject("SELECT status FROM loom_market_knowledge WHERE id=?", String.class, id);
        assertEquals("PENDING", status);
    }

    @Test
    void approveFlipsStatus() {
        String id = svc.create("alice", new MarketCreateRequest(
            "appr-" + System.nanoTime(), "d", "c", null
        )).id();
        svc.approve(id, "admin1");
        String s = jdbc.queryForObject("SELECT status FROM loom_market_knowledge WHERE id=?", String.class, id);
        assertEquals("APPROVED", s);
        String reviewedBy = jdbc.queryForObject("SELECT reviewed_by FROM loom_market_knowledge WHERE id=?", String.class, id);
        assertEquals("admin1", reviewedBy);
    }

    @Test
    void rejectWithoutCommentThrows() {
        String id = svc.create("alice", new MarketCreateRequest(
            "rej-" + System.nanoTime(), "d", "c", null
        )).id();
        assertThrows(IllegalArgumentException.class, () -> svc.reject(id, "admin1", ""));
    }

    @Test
    void setOfficialTogglesFlag() {
        String id = svc.create("alice", new MarketCreateRequest(
            "off-" + System.nanoTime(), "d", "c", null
        )).id();
        svc.setOfficial(id, true, "admin1");
        Boolean official = jdbc.queryForObject("SELECT is_official FROM loom_market_knowledge WHERE id=?", Boolean.class, id);
        assertEquals(true, official);
    }
}
