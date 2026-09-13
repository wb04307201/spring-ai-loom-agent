package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 集成测试: {@link DefaultMarketAnnouncementRepository} (T17; M3+ R3 迁移)。
 * <p>
 * T3 声明 {@link MarketAnnouncementRepository} 接口,T7/T8 router 写了 forward refs
 * (由 T18 端点接入)。T17 给出具体实现 — 这套测试覆盖仓储层核心 4 方法:
 * upsert / findOne / delete / listAllForKind。
 * <p>
 * R3: 仓储是 String-native({@code market_id} 列 VARCHAR(36))— 所有 id 用
 * String(十进制字符串模拟 skill,真 UUID 模拟 KB)。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("DefaultMarketAnnouncementRepository IT — T17 (R3 String-native)")
class DefaultMarketAnnouncementRepositoryIT {

    @Autowired DefaultMarketAnnouncementRepository repo;
    @Autowired JdbcTemplate jdbc;

    /** upsert 首次 — INSERT。后续 findOne 返回同一行。 */
    @Test
    @DisplayName("upsert 首次 INSERT + findOne 命中")
    void upsertThenFindOne() {
        String id = "1001";
        repo.upsert("SKILL", id, "title-A", "body-A");
        MarketAnnouncement one = repo.findOne("SKILL", id);

        assertNotNull(one);
        assertEquals("SKILL", one.marketKind());
        assertEquals(id, one.marketId());
        assertEquals("title-A", one.title());
        assertEquals("body-A", one.body());
        assertNotNull(one.createdAt());
    }

    /** upsert 二次 — MERGE INTO UPDATE,title / body 被覆盖,created_at 保留。 */
    @Test
    @DisplayName("upsert 二次 UPDATE title/body,created_at 保留原值")
    void upsertSecondTimeUpdates() {
        String id = "1002";
        repo.upsert("SKILL", id, "v1", "v1-body");
        MarketAnnouncement first = repo.findOne("SKILL", id);
        assertNotNull(first);

        repo.upsert("SKILL", id, "v2", "v2-body");
        MarketAnnouncement second = repo.findOne("SKILL", id);
        assertNotNull(second);
        assertEquals("v2", second.title());
        assertEquals("v2-body", second.body());
        assertEquals(first.createdAt(), second.createdAt(),
                "created_at must be preserved on UPDATE (不在 MERGE 列表里)");
    }

    /** upsert 同 kind 但不同 id — 各自独立行。 */
    @Test
    @DisplayName("upsert 不同 id 各自独立")
    void upsertDifferentIdsAreIndependent() {
        String id1 = "1003";
        String id2 = "1004";
        repo.upsert("SKILL", id1, "t1", "b1");
        repo.upsert("SKILL", id2, "t2", "b2");

        assertEquals("t1", repo.findOne("SKILL", id1).title());
        assertEquals("t2", repo.findOne("SKILL", id2).title());
    }

    /** upsert 不同 kind (SKILL vs KNOWLEDGE) 同 id — 各自独立 (PK = kind + id)。 */
    @Test
    @DisplayName("upsert 不同 kind 同 id 各自独立 (PK = market_kind + market_id)")
    void upsertDifferentKindsAreIndependent() {
        String id = "1005";
        repo.upsert("SKILL", id, "sk-title", "sk-body");
        repo.upsert("KNOWLEDGE", id, "kb-title", "kb-body");

        MarketAnnouncement sk = repo.findOne("SKILL", id);
        MarketAnnouncement kb = repo.findOne("KNOWLEDGE", id);
        assertNotNull(sk);
        assertNotNull(kb);
        assertEquals("SKILL", sk.marketKind());
        assertEquals("KNOWLEDGE", kb.marketKind());
        assertEquals("sk-title", sk.title());
        assertEquals("kb-title", kb.title());
    }

    /** R3: KNOWLEDGE 真 UUID market_id — String-native 仓储一等公民路径。 */
    @Test
    @DisplayName("upsert/findOne KNOWLEDGE 真 UUID market_id (R3 String-native)")
    void upsertKnowledgeUuid() {
        String uuid = UUID.randomUUID().toString();
        repo.upsert("KNOWLEDGE", uuid, "kb-uuid-title", "kb-uuid-body");

        MarketAnnouncement one = repo.findOne("KNOWLEDGE", uuid);
        assertNotNull(one);
        assertEquals("KNOWLEDGE", one.marketKind());
        assertEquals(uuid, one.marketId());
        assertEquals("kb-uuid-title", one.title());

        repo.delete("KNOWLEDGE", uuid);
        assertNull(repo.findOne("KNOWLEDGE", uuid));
    }

    /** findOne 不存在的 (kind, id) — 返回 null (Repository 风格,非 Optional)。 */
    @Test
    @DisplayName("findOne 不存在 → null")
    void findOneReturnsNullForMissing() {
        assertNull(repo.findOne("SKILL", "99999999"));
    }

    /** delete — 删后 findOne 返回 null,不影响同 kind 其它行。 */
    @Test
    @DisplayName("delete 删除单行,不影响其它行")
    void deleteRemovesOnlyOneRow() {
        String id1 = "1010";
        String id2 = "1011";
        repo.upsert("SKILL", id1, "t1", "b1");
        repo.upsert("SKILL", id2, "t2", "b2");

        repo.delete("SKILL", id1);

        assertNull(repo.findOne("SKILL", id1));
        assertNotNull(repo.findOne("SKILL", id2));
    }

    /**
     * listAllForKind — 按 kind 过滤 + 按 created_at DESC 排序 + 分页 + count。
     * 使用 unique kind 名(per-test)避免与同类的其它测试残留冲突。
     * 注意:market_kind 列是 VARCHAR(16),kind 字符串最长 16 字符。
     */
    @Test
    @DisplayName("listAllForKind 按 kind 过滤 + 分页 + count (unique kind)")
    void listAllForKindPaged() {
        // "SL_xxxxxxxxxx" = 12 chars,nanoTime() 末 8 位 = 唯一性保证 (保留 16 字符内)
        String myKind = "SL" + (System.nanoTime() % 1_0000_0000L);
        String otherKind = "KB" + ((System.nanoTime() + 1) % 1_0000_0000L);

        long base = 2000L;
        for (int i = 0; i < 4; i++) {
            repo.upsert(myKind, String.valueOf(base + i), "sk-" + i, "body-" + i);
        }
        repo.upsert(otherKind, String.valueOf(base + 100), "kb-x", "kb-body");

        Page<MarketAnnouncement> page = repo.listAllForKind(myKind, 0, 10);
        assertEquals(4L, page.total(), "total must equal my-kind row count exactly");
        assertEquals(4, page.items().size());
        for (MarketAnnouncement ann : page.items()) {
            assertEquals(myKind, ann.marketKind());
        }
    }

    /** listAllForKind 分页 — 限制 size 后只返回 size 条,但 total 是全 kind 总数。 */
    @Test
    @DisplayName("listAllForKind 分页 size=2 时,items=2,total仍为全部 (unique kind)")
    void listAllForKindRespectsSize() {
        // "KP_xxxxxxxxxx" = 12 chars,末 8 位 nanoTime 保证唯一
        String myKind = "KP" + (System.nanoTime() % 1_0000_0000L);

        long base = 3000L;
        for (int i = 0; i < 5; i++) {
            repo.upsert(myKind, String.valueOf(base + i), "kb-" + i, "b-" + i);
        }

        Page<MarketAnnouncement> page = repo.listAllForKind(myKind, 0, 2);
        assertEquals(5L, page.total(), "total must reflect full kind count");
        assertEquals(2, page.items().size(), "page size must be respected");
        assertEquals(0, page.page());
        assertEquals(2, page.size());
    }

    /** 集成 — 完整 upsert → findOne → list → delete 闭环。 */
    @Test
    @DisplayName("闭环: upsert → findOne → listAllForKind 包含 → delete → findOne null")
    void fullLifecycleRoundTrip() {
        String id = "9000";
        repo.upsert("SKILL", id, "lifecycle", "end-to-end");

        MarketAnnouncement one = repo.findOne("SKILL", id);
        assertNotNull(one);
        assertEquals("lifecycle", one.title());

        Page<MarketAnnouncement> all = repo.listAllForKind("SKILL", 0, 100);
        boolean found = all.items().stream().anyMatch(a -> id.equals(a.marketId()));
        assertEquals(true, found);

        repo.delete("SKILL", id);
        assertNull(repo.findOne("SKILL", id));
    }
}
