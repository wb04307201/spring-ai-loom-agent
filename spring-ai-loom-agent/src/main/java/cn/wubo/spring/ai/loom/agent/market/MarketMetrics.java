package cn.wubo.spring.ai.loom.agent.market;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * M3+ T4.1 — Micrometer-backed metrics for the market subsystem.
 *
 * <p>Provides pre-registered counters and timers for the most-trafficked
 * admin / public operations. Per cleanup spec §9 ADR-T07, this is the
 * lightweight Micrometer start; no Prometheus / OTel tracing yet.
 *
 * <p>Usage from service code:
 * <pre>{@code
 *   @Autowired MarketMetrics marketMetrics;
 *
 *   public MarketSkill approve(Long id, String reviewer) {
 *       marketMetrics.approveCounter("SKILL").increment();
 *       ...
 *   }
 * }</pre>
 *
 * <p>Counters are tagged by {@code market_kind} ({@code SKILL} / {@code KB})
 * so the same metric is split per kind and can be queried at
 * {@code /actuator/metrics/market.<op>} with tag filter.
 *
 * <p>Timers record elapsed wall-clock for hot paths
 * (list / search / approve / reject). Use {@code Timer.record(Runnable)}
 * or {@code Timer.record(long, TimeUnit)} depending on whether the
 * service method returns a value.
 */
public class MarketMetrics {

    private final MeterRegistry registry;

    public MarketMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Approve counter for either SKILL or KB markets. Registered lazily;
     * returns the same Counter for repeat calls (Micrometer dedups).
     */
    public Counter approveCounter(String marketKind) {
        return Counter.builder("market.approve")
                .description("Number of approve operations per market kind")
                .tag("market_kind", marketKind)
                .register(registry);
    }

    /**
     * Reject counter per market kind.
     */
    public Counter rejectCounter(String marketKind) {
        return Counter.builder("market.reject")
                .description("Number of reject operations per market kind")
                .tag("market_kind", marketKind)
                .register(registry);
    }

    /**
     * Public list / search counter — fires on every GET that returns the
     * market list (admin + public).
     */
    public Counter listCounter(String marketKind, String scope) {
        return Counter.builder("market.list")
                .description("Number of list / search operations on the market")
                .tag("market_kind", marketKind)
                .tag("scope", scope)
                .register(registry);
    }

    /**
     * Pull counter — fires when a user subscribes / pulls a market item.
     */
    public Counter pullCounter(String marketKind) {
        return Counter.builder("market.pull")
                .description("Number of pull / subscribe operations")
                .tag("market_kind", marketKind)
                .register(registry);
    }

    /**
     * Timer for the list / search hot path.
     */
    public Timer listTimer(String marketKind, String scope) {
        return Timer.builder("market.list.duration")
                .description("Wall-clock duration of market list / search")
                .tag("market_kind", marketKind)
                .tag("scope", scope)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    /**
     * Convenience: record elapsed nanos into a Timer.
     */
    public void recordList(String marketKind, String scope, long elapsedNanos) {
        listCounter(marketKind, scope).increment();
        listTimer(marketKind, scope).record(elapsedNanos, TimeUnit.NANOSECONDS);
    }
}
