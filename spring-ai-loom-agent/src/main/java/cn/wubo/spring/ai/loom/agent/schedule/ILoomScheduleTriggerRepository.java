package cn.wubo.spring.ai.loom.agent.schedule;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for loom-agent's prompt-triggered scheduled sub-tasks.
 *
 * <p>Replaces the previous (vestigial) flex-schedule-owned H2 table. loom-agent
 * owns the schema, the rows, the restore lifecycle, and the deletion cascade on
 * conversation removal.</p>
 *
 * <p>The implementation is wired in
 * {@link cn.wubo.spring.ai.loom.agent.schedule.ScheduleConfiguration}
 * via {@code @ConditionalOnMissingBean} so consumers may substitute their own
 * (e.g. a Redis-backed impl when running loom-agent as a clustered deployment).</p>
 */
public interface ILoomScheduleTriggerRepository {

 /** Inserts or updates the row for {@code record.taskName()}. */
 void save(LoomScheduleTriggerRecord record);

 /** Returns the row matching {@code taskName}, or empty if none. */
 Optional<LoomScheduleTriggerRecord> findByName(String taskName);

 /** All rows, ordered by task_name for stable iteration. Used by the restore listener. */
 List<LoomScheduleTriggerRecord> findAll();

 /** Scoped lookup for endpoints that list a single conversation's schedules. */
 List<LoomScheduleTriggerRecord> findByUserAndConv(String username, String conversationId);

 /** Deletes the row matching {@code taskName}. Returns the affected row count. */
 int delete(String taskName);

 /**
 * Deletes every row whose {@code (username, conversationId)} matches.
 * Used by the conversation-deletion lifecycle hook to cascade.
 * Returns the affected row count.
 */
 int deleteAllForConversation(String username, String conversationId);

 /** Total row count. Used by tests + ops dashboards. */
 int count();

 /** Convenience existence check. */
 boolean exists(String taskName);
}
