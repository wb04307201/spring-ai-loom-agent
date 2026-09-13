package cn.wubo.spring.ai.loom.agent.market;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketContentStatusTest {
    @Test
    void fromCaseInsensitive() {
        assertEquals(MarketContentStatus.PENDING, MarketContentStatus.from("pending"));
        assertEquals(MarketContentStatus.APPROVED, MarketContentStatus.from("APPROVED"));
        assertEquals(MarketContentStatus.REJECTED, MarketContentStatus.from("  rejected  "));
    }
    @Test
    void fromNullOrBlankReturnsNull() {
        assertNull(MarketContentStatus.from(null));
        assertNull(MarketContentStatus.from(""));
        assertNull(MarketContentStatus.from(" "));
    }
}