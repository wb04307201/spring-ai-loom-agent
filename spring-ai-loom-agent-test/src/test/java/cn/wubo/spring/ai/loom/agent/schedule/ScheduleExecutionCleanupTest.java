package cn.wubo.spring.ai.loom.agent.schedule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests the @Scheduled(cron = ...) retention loop in
 * {@link ScheduleExecutionCleanup}. The cron schedule itself is exercised by the
 * Spring context in {@code spring-ai-loom-agent-test}; here we just verify the
 * retention / deletion semantics against a mocked
 * {@link ILoomScheduleExecutionRepository}.
 */
class ScheduleExecutionCleanupTest {

    private ILoomScheduleExecutionRepository repo;
    private ScheduleExecutionCleanup cleanup;

    @BeforeEach
    void setUp() {
        repo = mock(ILoomScheduleExecutionRepository.class);
        ScheduleExecutionProperties props = new ScheduleExecutionProperties();
        props.setRetention(Duration.ofDays(30)); // explicit so we know cutoff
        props.setCleanupCron("0 0 3 * * *"); // 03:00 every day — not exercised here
        cleanup = new ScheduleExecutionCleanup(repo, props);
    }

    @Test
    void cleanup_deletesRowsOlderThanRetention() {
        when(repo.deleteOlderThan(any(Instant.class))).thenReturn(7);

        Instant beforeCall = Instant.now();
        cleanup.cleanup();
        Instant afterCall = Instant.now();

        // Capture and validate the cutoff is in the expected window:
        // [beforeCall - retention, afterCall - retention]
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repo).deleteOlderThan(cutoff.capture());
        Instant observed = cutoff.getValue();

        // retention = 30 days. Tight bound: cutoff should be within a few
        // seconds of (now - 30d).
        Instant expectedLow = beforeCall.minus(Duration.ofDays(30)).minus(Duration.ofMinutes(1));
        Instant expectedHigh = afterCall.minus(Duration.ofDays(30)).plus(Duration.ofMinutes(1));
        assertThat(observed).isBetween(expectedLow, expectedHigh);
    }

    @Test
    void cleanup_handlesZeroDeletionSilently() {
        when(repo.deleteOlderThan(any())).thenReturn(0);

        // Should not throw and should propagate the call.
        cleanup.cleanup();

        verify(repo).deleteOlderThan(any());
    }

    @Test
    void cleanup_swallowsRepoExceptionsToAvoidBreakingScheduler() {
        when(repo.deleteOlderThan(any())).thenThrow(new RuntimeException("H2 down"));

        // The @Scheduled task should not fail the scheduler thread on DB issues.
        cleanup.cleanup();

        verify(repo).deleteOlderThan(any());
    }

    @Test
    void scheduleExecutionProperties_defaults_areConservative() {
        ScheduleExecutionProperties p = new ScheduleExecutionProperties();
        assertThat(p.getMaxPerTask()).isEqualTo(1000);
        assertThat(p.getRetention()).isEqualTo(Duration.ofDays(30));
        assertThat(p.getCleanupCron()).isEqualTo("0 0 3 * * *");
    }

    @Test
    void scheduleExecutionProperties_settersAreReflectedOnGet() {
        ScheduleExecutionProperties p = new ScheduleExecutionProperties();
        p.setMaxPerTask(50);
        p.setRetention(Duration.ofDays(7));
        p.setCleanupCron("0 0 4 * * *");

        assertThat(p.getMaxPerTask()).isEqualTo(50);
        assertThat(p.getRetention()).isEqualTo(Duration.ofDays(7));
        assertThat(p.getCleanupCron()).isEqualTo("0 0 4 * * *");
    }
}
