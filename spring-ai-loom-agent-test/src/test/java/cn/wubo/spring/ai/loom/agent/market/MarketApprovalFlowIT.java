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

    @Test
    void submitLandsPending() {
        long id = skillSvc.submit("alice",
            new cn.wubo.spring.ai.loom.agent.model.MarketSkillSubmitRequest(
                "sub-" + System.nanoTime(), "d", "c")).id();
        String status = jdbc.queryForObject("SELECT status FROM market_skill WHERE id=?", String.class, id);
        assertEquals("PENDING", status);
        Object reviewedBy = jdbc.queryForObject("SELECT reviewed_by FROM market_skill WHERE id=?", Object.class, id);
        assertNull(reviewedBy, "投稿未审,reviewed_by 应为 null");
    }

    @Test
    void pullRejectsNonApproved() {
        long id = skillSvc.submit("alice",
            new cn.wubo.spring.ai.loom.agent.model.MarketSkillSubmitRequest(
                "pull-" + System.nanoTime(), "d", "c")).id();
        var ex = assertThrows(cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException.class,
            () -> skillSvc.pull("bob", id));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void resubmitRejectedArchivesOldRow() {
        String name = "resub-" + System.nanoTime();
        long id1 = skillSvc.submit("alice",
            new cn.wubo.spring.ai.loom.agent.model.MarketSkillSubmitRequest(name, "d", "c1")).id();
        skillSvc.reject(id1, "admin1", "内容不合规");
        // 重投同名
        long id2 = skillSvc.submit("alice",
            new cn.wubo.spring.ai.loom.agent.model.MarketSkillSubmitRequest(name, "d", "c2")).id();
        assertNotEquals(id1, id2, "重投应新建行(新 id)");
        String s2 = jdbc.queryForObject("SELECT status FROM market_skill WHERE id=?", String.class, id2);
        assertEquals("PENDING", s2);
        Integer archived = jdbc.queryForObject(
            "SELECT COUNT(*) FROM market_skill_archive WHERE id=?", Integer.class, id1);
        assertEquals(1, archived, "旧 REJECTED 行应进 archive");
        String arcComment = jdbc.queryForObject(
            "SELECT review_comment FROM market_skill_archive WHERE id=?", String.class, id1);
        assertEquals("内容不合规", arcComment);
        Integer mainStillHasOld = jdbc.queryForObject(
            "SELECT COUNT(*) FROM market_skill WHERE id=?", Integer.class, id1);
        assertEquals(0, mainStillHasOld, "旧 id 已从主表删除");
    }
}
