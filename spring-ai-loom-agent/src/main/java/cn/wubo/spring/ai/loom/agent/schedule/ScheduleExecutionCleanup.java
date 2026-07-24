package cn.wubo.spring.ai.loom.agent.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

/**
 * Daily cleanup of {@link ILoomScheduleExecutionRepository}.
 * <p>
 * Runs at the cron in {@link ScheduleExecutionProperties#getCleanupCron()} (default
 * 03:00 every day). Deletes execution rows older than
 * {@link ScheduleExecutionProperties#getRetention()} (default 30 days) so the
 * {@code loom_schedule_execution} table doesn't grow unboundedly for chatty
 * cron / fixed schedules.
 * </p>
 */
public class ScheduleExecutionCleanup {

    private static final Logger log = LoggerFactory.getLogger(ScheduleExecutionCleanup.class);

    private final ILoomScheduleExecutionRepository repo;
    private final ScheduleExecutionProperties props;

    public ScheduleExecutionCleanup(ILoomScheduleExecutionRepository repo, ScheduleExecutionProperties props) {
        this.repo = repo;
        this.props = props;
    }

    @Scheduled(cron = "${spring.ai.loom.agent.schedule.execution.cleanup-cron:0 0 3 * * *}")
    public void cleanup() {
        Instant cutoff = Instant.now().minus(props.getRetention());
        try {
            int n = repo.deleteOlderThan(cutoff);
            if (n > 0) {
                log.info("ScheduleExecutionCleanup: deleted {} execution row(s) older than {}",
                        n, cutoff);
            } else {
                log.debug("ScheduleExecutionCleanup: nothing to delete (cutoff={})", cutoff);
            }
        } catch (Exception e) {
            log.warn("ScheduleExecutionCleanup failed: {}", e.getMessage());
        }
    }
}