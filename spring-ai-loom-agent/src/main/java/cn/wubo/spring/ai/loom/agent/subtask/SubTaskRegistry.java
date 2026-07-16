package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
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

    /** Backward-compatible constructor with no cancel hook. */
    public SubTaskRegistry(int maxConcurrent, int maxHistory) {
        this(maxConcurrent, maxHistory, null);
    }

    public SubTaskRegistry(int maxConcurrent, int maxHistory, Consumer<String> cancelHook) {
        this.maxConcurrent = maxConcurrent;
        this.maxHistory = maxHistory;
        this.cancelHook = cancelHook;
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
    }

    /**
     * Attempts to cancel a running sub-task. Returns {@code true} if the task was
     * still in {@code active} when called. Invokes the cancel hook (if registered)
     * to interrupt the worker thread, then transitions the record to CANCELLED.
     * Falls back to the legacy {@code attachFuture} path if no hook is registered.
     */
    public boolean kill(String subTaskId) {
        SubTaskRecord rec = active.get(subTaskId);
        if (rec == null) return false;

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
            if (kill(id)) n++;
        }
        return n;
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

    private void archive(SubTaskRecord rec) {
        if (rec.username() == null) return;       // skip anonymous records (would NPE historyByUser)
        Deque<SubTaskRecord> deque = historyByUser.computeIfAbsent(rec.username(), k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(rec);
            while (deque.size() > maxHistory) {
                deque.removeFirst();
            }
        }
    }

    private static String safeUsername(String s) {
        return s == null ? "" : s;
    }

    private static String safeConv(String s) {
        return s == null ? "" : s;
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
    ) {}
}
