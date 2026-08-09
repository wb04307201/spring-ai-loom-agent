package cn.wubo.spring.ai.loom.agent.schedule;

import java.time.Duration;

/**
 * Tunable knobs for schedule execution-event retention.
 * <p>
 * Centralised here so callers can override via {@code @Bean} substitution or a
 * dedicated properties class without touching the listener / cleanup code.
 * Default values are conservative for a small deployment.
 * </p>
 */
public class ScheduleExecutionProperties {

 /** Newest N execution rows retained per schedule task; older rows trimmed on every fire. */
 private int maxPerTask = 1000;

 /** Daily cleanup deletes rows older than this. */
 private Duration retention = Duration.ofDays(30);

 /** Cron expression for the daily cleanup {@code @Scheduled} task. */
 private String cleanupCron = "0 0 3 * * *"; // 03:00 every day

 public int getMaxPerTask() { return maxPerTask; }
 public void setMaxPerTask(int maxPerTask) { this.maxPerTask = maxPerTask; }

 public Duration getRetention() { return retention; }
 public void setRetention(Duration retention) { this.retention = retention; }

 public String getCleanupCron() { return cleanupCron; }
 public void setCleanupCron(String cleanupCron) { this.cleanupCron = cleanupCron; }
}