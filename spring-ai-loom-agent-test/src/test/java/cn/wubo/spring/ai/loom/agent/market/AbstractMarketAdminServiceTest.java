package cn.wubo.spring.ai.loom.agent.market;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link AbstractMarketAdminService} — uses pure Mockito (no @SpringBootTest)
 * to verify approve / reject / setOfficial SQL behavior in isolation.
 *
 * <p>The brief's snippet uses {@code protected FakeEntry rowMapper()} which is a typo
 * (a lambda of two params cannot return {@code FakeEntry}); this test uses the correct
 * {@code RowMapper<FakeEntry>} signature.
 */
class AbstractMarketAdminServiceTest {

    static class FakeEntry {
        final Long id;
        final String name;
        final MarketContentStatus status;

        FakeEntry(Long id, String name, MarketContentStatus status) {
            this.id = id;
            this.name = name;
            this.status = status;
        }
    }

    /** Minimal concrete subclass exercising the abstract base. */
    static class TestSvc extends AbstractMarketAdminService<Long, FakeEntry, Void, Void> {
        TestSvc(JdbcTemplate jdbc) { super(jdbc); }

        @Override
        protected String tableName() {
            return "market_skill";
        }

        @Override
        protected RowMapper<FakeEntry> rowMapper() {
            return (rs, n) -> new FakeEntry(
                rs.getLong("id"),
                rs.getString("name"),
                MarketContentStatus.from(rs.getString("status"))
            );
        }

        @Override
        public Long extractId(FakeEntry e) {
            return e.id;
        }
    }

    JdbcTemplate jdbc;
    TestSvc svc;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        svc = new TestSvc(jdbc);
    }

    @Test
    void approveUpdatesStatus() {
        when(jdbc.update(startsWith("UPDATE market_skill SET status='APPROVED'"), any(Object[].class)))
            .thenReturn(1);

        svc.approve(5L, "admin1");

        verify(jdbc).update(startsWith("UPDATE market_skill SET status='APPROVED'"), any(Object[].class));
    }

    @Test
    void rejectRequiresComment() {
        assertThrows(IllegalArgumentException.class,
            () -> svc.reject(5L, "admin1", ""));
        assertThrows(IllegalArgumentException.class,
            () -> svc.reject(5L, "admin1", null));
    }

    @Test
    void setOfficialUpdatesColumn() {
        when(jdbc.update(startsWith("UPDATE market_skill SET is_official="), any(Object[].class)))
            .thenReturn(1);

        svc.setOfficial(5L, true, "admin1");

        verify(jdbc).update(eq("UPDATE market_skill SET is_official=? WHERE id=?"), eq(true), eq(5L));
    }
}