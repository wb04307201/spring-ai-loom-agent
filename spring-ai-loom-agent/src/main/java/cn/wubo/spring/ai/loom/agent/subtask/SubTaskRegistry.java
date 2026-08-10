package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * In-memory registry of sub-task lifecycles.
 * <p>
 * Tracks active runs and archives completed records into a bounded ring
 * (per-user FIFO). Cancellation is delegated to an optional cancel hook
 * (typically {@code ISubTaskExecutor#cancel(String)}) so the worker thread can
 * actually be interrupted rather than just marked CANCELLED.
 * </p>
 * <p>
 * Thread-safety: all state lives in {@link ConcurrentHashMap} /
 * {@link AtomicInteger} / a synchronized deque.
 * </p>
 */
public class SubTaskRegistry {

    private final int maxConcurrent;
    private final int maxHistory;

    private final ConcurrentHashMap<String, SubTaskRecord> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SubTaskRecord> history = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<SubTaskRecord>> historyByUser = new ConcurrentHashMap<>();
    private final AtomicInteger activeCount = new AtomicInteger(0);

    /**
     * Optional hook invoked on kill(). Receives the sub-task id; should interrupt the
     * underlying worker thread (typically {@code executor::cancel}). May be null for
     * tests or when the executor is not yet wired — kill() will fall back to the
     * legacy attachFuture path.
     */
    private final Consumer<String> cancelHook;

    /**
     * Optional hook invoked on {@link #markFinished} with the terminal record.
     * Production wiring uses this to write-through to {@code loom_subtask_history}
     * so history survives restarts. May be null (legacy / tests).
     */
    private final Consumer<SubTaskRecord> writeHook;

    /**
     * Backward-compatible constructor with no cancel hook.
     */
    public SubTaskRegistry(int maxConcurrent, int maxHistory) {
        this(maxConcurrent, maxHistory, null, null);
    }

    /**
     * Backward-compatible constructor with cancel hook only.
     */
    public SubTaskRegistry(int maxConcurrent, int maxHistory, Consumer<String> cancelHook) {
        this(maxConcurrent, maxHistory, cancelHook, null);
    }

    public SubTaskRegistry(int maxConcurrent, int maxHistory, Consumer<String> cancelHook, Consumer<SubTaskRecord> writeHook) {
        this.maxConcurrent = maxConcurrent;
        this.maxHistory = maxHistory;
        this.cancelHook = cancelHook;
        this.writeHook = writeHook;
    }

    private static String safeUsername(String s) {
        return s == null ? "" : s;
    }

    private static String safeConv(String s) {
        return s == null ? "" : s;
    }

    /**
     * Registers a new sub-task and returns its assigned UUID. Throws if
     * {@link #maxConcurrent} active tasks are already in flight.
     */
    public String register(String username, String conversationId, String prompt) {
        if (activeCount.get() >= maxConcurrent) {
            throw new IllegalStateException(
                    "已达最大并发子任务数 " + maxConcurrent + ", 请稍后再试");
        }
        String id = UUID.randomUUID().toString();
        return registerWithId(id, username, conversationId, prompt);
    }

    /**
     * Like {@link #register(String, String, String)} but uses a caller-supplied
     * {@code subTaskId} (so the caller can keep using the {@code SubTaskRequest}
     * id it already generated instead of having to round-trip through a
     * registry-returned id). Lets {@link ISubTaskExecutor} consistently
     * contribute to the same history stream as the LLM-tool path.
     *
     * <p>Throws if maxConcurrent is exceeded OR if {@code subTaskId} is
     * already registered (active or historical) — duplicate ids are rejected
     * to keep the key space clean.</p>
     */
    public String registerWithId(String subTaskId, String username, String conversationId, String prompt) {
        if (activeCount.get() >= maxConcurrent) {
            throw new IllegalStateException(
                    "已达最大并发子任务数 " + maxConcurrent + ", 请稍后再试");
        }
        if (subTaskId == null || subTaskId.isBlank()) {
            throw new IllegalArgumentException("subTaskId must be non-blank");
        }
        if (active.containsKey(subTaskId) || history.containsKey(subTaskId)) {
            throw new IllegalStateException("子任务 id 已注册: " + subTaskId);
        }
        SubTaskRecord rec = new SubTaskRecord(subTaskId, safeUsername(username), safeConv(conversationId), prompt,
                SubTaskStatus.RUNNING, System.currentTimeMillis(), 0L, null, null, null);
        active.put(subTaskId, rec);
        activeCount.incrementAndGet();
        return subTaskId;
    }

    /**
     * Attach the {@link CompletableFuture} so the registry can cancel via
     * {@link CompletableFuture#cancel(boolean)} on kill (legacy path; the cancel-hook
     * path is preferred and used when {@link #kill(String)} is called with a registered hook).
     */
    public void attachFuture(String subTaskId, CompletableFuture<?> future) {
        SubTaskRecord rec = active.get(subTaskId);
        if (rec == null) return;
        rec = new SubTaskRecord(rec.subTaskId(), rec.username(), rec.conversationId(), rec.prompt(),
                rec.status(), rec.startedAt(), 0L, rec.errorMessage(), rec.resultText(), future);
        active.put(subTaskId, rec);
    }

    /**
     * Transitions an active sub-task to a terminal status and archives it.
     * Tolerates {@code text}/{@code errorMessage} being null (defensive against
     * custom executors returning sparse SubTaskResults).
     */
    public void markFinished(String subTaskId, SubTaskStatus status, String text, String errorMessage) {
        SubTaskRecord rec = active.remove(subTaskId);
        if (rec == null) return;
        activeCount.decrementAndGet();
        SubTaskRecord finished = new SubTaskRecord(rec.subTaskId(), rec.username(), rec.conversationId(),
                rec.prompt(), status, rec.startedAt(), System.currentTimeMillis(),
                errorMessage == null ? "" : errorMessage,
                text == null ? "" : text,
                null);
        history.put(subTaskId, finished);
        archive(finished);
        // Write-through persistence — fire-and-forget so H2 latency can't stall the
        // worker thread. The hook is responsible for its own error handling.
        if (writeHook != null) {
            try {
                writeHook.accept(finished);
            } catch (Exception e) {
                // Persistence failures must not undo the in-memory state transition;
                // log and let the user re-trigger /admin tool reconcile if needed.
                org.slf4j.LoggerFactory.getLogger(SubTaskRegistry.class)
                        .warn("Sub-task history write-through failed: id={}, err={}",
                                finished.subTaskId(), e.getMessage());
            }
        }
    }

    /**
     * Attempts to cancel a running sub-task owned by {@code username}. Returns
     * {@code true} only if the task was still active AND belongs to the caller.
     * Returns {@code false} for unknown ids AND for cross-user attempts — the
     * caller MUST be unable to distinguish the two cases from the return value
     * (avoids an id-existence oracle).
     *
     * <p>Cross-user kill attempt is logged at WARN so ops can spot scanning.
     *
     * <p>Fix for BUG-RBAC-SUBTASK-KILL: previously {@code kill(id)} only looked
     * up by id in the global active map, so any authenticated user could cancel
     * any other user's RUNNING sub-task. Now the owner check happens BEFORE
     * the cancel hook fires — the hook never even gets called for foreign ids.
     */
    public boolean kill(String username, String subTaskId) {
        if (username == null || username.isBlank() || subTaskId == null || subTaskId.isBlank()) {
            return false;
        }
        SubTaskRecord rec = active.get(subTaskId);
        if (rec == null) return false;
        // Owner check — refuse silently so the row id stays opaque to non-owners.
        if (rec.username() == null || !rec.username().equals(username)) {
            org.slf4j.LoggerFactory.getLogger(SubTaskRegistry.class)
                    .warn("拒绝跨用户 kill: caller={}, owner={}, subTaskId={}",
                            username, rec.username(), subTaskId);
            return false;
        }

        if (cancelHook != null) {
            try {
                cancelHook.accept(subTaskId);
            } catch (Exception e) {
                // Hook failure must not block kill — fall back to legacy future path.
                CompletableFuture<?> future = rec.future();
                if (future != null) future.cancel(true);
            }
        } else {
            CompletableFuture<?> future = rec.future();
            if (future != null) {
                future.cancel(true);
            }
        }

        markFinished(subTaskId, SubTaskStatus.CANCELLED, null, "用户取消");
        return true;
    }

    /**
     * Cancels every active sub-task belonging to the given conversation.
     * Returns the number of cancelled tasks. Tolerates {@code conversationId == null}.
     *
     * <p>NB: skips the {@link #kill(String, String)} owner check. This is the
     * cascade path invoked when a conversation is deleted — at that point the
     * caller's ownership of the conversation has already been validated by
     * the router / controller, and conversationId uniquely identifies the
     * scoping user within the active map (one row per sub-task).
     */
    public int killAllByConversation(String conversationId) {
        if (conversationId == null) return 0;
        List<String> ids = new ArrayList<>();
        active.forEach((id, rec) -> {
            if (conversationId.equals(rec.conversationId())) {
                ids.add(id);
            }
        });
        int n = 0;
        for (String id : ids) {
            if (killInternalNoOwnerCheck(id)) n++;
        }
        return n;
    }

    /**
     * The pre-BUG-RBAC-SUBTASK-KILL cancel path. Retained as a private
     * helper for {@link #killAllByConversation} where ownership has already
     * been validated by the caller.
     */
    private boolean killInternalNoOwnerCheck(String subTaskId) {
        SubTaskRecord rec = active.get(subTaskId);
        if (rec == null) return false;
        if (cancelHook != null) {
            try {
                cancelHook.accept(subTaskId);
            } catch (Exception e) {
                CompletableFuture<?> future = rec.future();
                if (future != null) future.cancel(true);
            }
        } else {
            CompletableFuture<?> future = rec.future();
            if (future != null) future.cancel(true);
        }
        markFinished(subTaskId, SubTaskStatus.CANCELLED, null, "用户取消");
        return true;
    }

    /**
     * Cascade-clean every per-user history entry belonging to {@code (username, conversationId)}.
     * Removes from the bounded deque and the global {@code history} map. Does NOT
     * touch the H2 persistence — that is owned by
     * {@code ILoomSubTaskHistoryRepository#deleteAllByConversation}, which the
     * caller should invoke alongside this for consistency.
     *
     * @return number of records cleared from the in-memory deque.
     */
    public int removeAllByConversation(String username, String conversationId) {
        if (username == null || username.isBlank() || conversationId == null || conversationId.isBlank()) {
            return 0;
        }
        Deque<SubTaskRecord> deque = historyByUser.get(username);
        if (deque == null) return 0;
        int removed = 0;
        synchronized (deque) {
            java.util.Iterator<SubTaskRecord> it = deque.iterator();
            while (it.hasNext()) {
                SubTaskRecord rec = it.next();
                if (conversationId.equals(rec.conversationId())) {
                    it.remove();
                    history.remove(rec.subTaskId());
                    removed++;
                }
            }
        }
        return removed;
    }

    public SubTaskRecord get(String subTaskId) {
        SubTaskRecord r = active.get(subTaskId);
        return r != null ? r : history.get(subTaskId);
    }

    public List<SubTaskRecord> listActive(String username) {
        List<SubTaskRecord> out = new ArrayList<>();
        active.forEach((id, rec) -> {
            if (rec.username() != null && rec.username().equals(username)) out.add(rec);
        });
        return out;
    }

    /**
     * Like {@link #listActive(String)} but also filters by parent {@code conversationId}.
     * A null {@code conversationId} matches everything (same as the no-filter call).
     */
    public List<SubTaskRecord> listActiveByConversation(String username, String conversationId) {
        if (username == null) return List.of();
        List<SubTaskRecord> out = new ArrayList<>();
        active.forEach((id, rec) -> {
            if (rec.username() == null || !rec.username().equals(username)) return;
            if (conversationId != null && !conversationId.equals(rec.conversationId())) return;
            out.add(rec);
        });
        return out;
    }

    public List<SubTaskRecord> listHistory(String username, int limit) {
        if (username == null) return List.of();
        Deque<SubTaskRecord> deque = historyByUser.get(username);
        if (deque == null) return List.of();
        // Newest first
        List<SubTaskRecord> out = new ArrayList<>(deque);
        java.util.Collections.reverse(out);
        if (out.size() > limit) return out.subList(0, limit);
        return out;
    }

    /**
     * Like {@link #listHistory(String, int)} but also filters by parent
     * {@code conversationId}. A null {@code conversationId} matches everything.
     */
    public List<SubTaskRecord> listHistoryByConversation(String username, String conversationId, int limit) {
        if (username == null) return List.of();
        Deque<SubTaskRecord> deque = historyByUser.get(username);
        if (deque == null) return List.of();
        List<SubTaskRecord> out = new ArrayList<>();
        synchronized (deque) {
            // Walk newest-first and collect matches up to limit.
            java.util.Iterator<SubTaskRecord> it = deque.descendingIterator();
            while (it.hasNext() && out.size() < limit) {
                SubTaskRecord rec = it.next();
                if (conversationId != null && !conversationId.equals(rec.conversationId())) continue;
                out.add(rec);
            }
        }
        return out;
    }

    private void archive(SubTaskRecord rec) {
        if (rec.username() == null) return; // skip anonymous records (would NPE historyByUser)
        Deque<SubTaskRecord> deque = historyByUser.computeIfAbsent(rec.username(), k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(rec);
            while (deque.size() > maxHistory) {
                deque.removeFirst();
            }
        }
    }

    /**
     * Re-hydrate an already-terminal record from persistent storage into the
     * in-memory deque. Used by {@code SubTaskHistoryPreloader} on application
     * startup so the first API call after a restart already sees prior history.
     * Does NOT trigger the {@code writeHook} (no point re-persisting what we just
     * loaded) and skips terminal records that are duplicates of one already in
     * the deque.
     */
    public void rehydrate(SubTaskRecord rec) {
        if (rec == null || rec.subTaskId() == null || rec.username() == null) return;
        if (history.containsKey(rec.subTaskId())) return;
        history.put(rec.subTaskId(), rec);
        archive(rec);
    }

    /**
     * Immutable view of a sub-task record. Terminal state is decided at construction
     * time via {@link SubTaskStatus}.
     */
    public record SubTaskRecord(
            String subTaskId,
            String username,
            String conversationId,
            String prompt,
            SubTaskStatus status,
            long startedAt,
            long finishedAt,
            String errorMessage,
            String resultText,
            CompletableFuture<?> future
    ) {
    }
}
