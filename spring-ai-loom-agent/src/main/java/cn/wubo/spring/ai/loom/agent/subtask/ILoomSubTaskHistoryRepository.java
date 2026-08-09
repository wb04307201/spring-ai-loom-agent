package cn.wubo.spring.ai.loom.agent.subtask;

import java.util.List;
import java.util.Set;

/**
 * Persistence boundary for sub-task execution history.
 * <p>
 * Backed by an H2 table (Flyway , {@code loom_subtask_history}) so that history survives application
 * restarts. The in-memory {@link SubTaskRegistry} continues to be the
 * single source of truth for live queries; this repository is a write-through
 * sink + cold-start rehydration source.
 * </p>
 */
public interface ILoomSubTaskHistoryRepository {

 /** Persist (insert) a finished sub-task record. Idempotent on re-insert by {@code subtask_id}. */
 void save(SubTaskRegistry.SubTaskRecord record);

 /** All records for {@code (username, conversationId)}, newest first, capped at {@code limit}. */
 List<SubTaskRegistry.SubTaskRecord> findByUsernameAndConversation(String username, String conversationId, int limit);

 /** Up to {@code limit} most recent records for the given user across all conversations. */
 List<SubTaskRegistry.SubTaskRecord> findByUsername(String username, int limit);

 /** Distinct usernames that have at least one history row. Used at startup to enumerate who to rehydrate. */
 Set<String> findAllUsernames();

 /** Cascade-delete every history row for {@code (username, conversationId)}. Returns affected row count. */
 int deleteAllByConversation(String username, String conversationId);

 /** Delete a single history row by sub-task id (scoped to the calling user
 * so a USER can't delete another USER's record). Returns true if a row
 * was deleted. The matching in-memory record (if any) is also cleared from
 * the registry's history deque. */
 boolean deleteById(String username, String subTaskId);

 /** Defensive schema bootstrap (mirrors V15). Used by callers that bypass Flyway. */
 void ensureSchema();
}