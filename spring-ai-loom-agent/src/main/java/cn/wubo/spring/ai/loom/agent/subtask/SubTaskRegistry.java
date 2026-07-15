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

/**
 * In-memory registry of sub-task lifecycles.
 * <p>
 * Tracks active runs (with their {@link CompletableFuture} so we can cancel),
 * archives completed records into a bounded ring (per-user FIFO), and exposes
 * query methods for the BFF + the conversation-deletion lifecycle hook.
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

    public SubTaskRegistry(int maxConcurrent, int maxHistory) {
        this.maxConcurrent = maxConcurrent;
        this.maxHistory = maxHistory;
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
        SubTaskRecord rec = new SubTaskRecord(id, username, conversationId, prompt,
                SubTaskStatus.RUNNING, System.currentTimeMillis(), 0L, null, null, null);
        active.put(id, rec);
        activeCount.incrementAndGet();
        return id;
    }

    /**
     * Attach the {@link CompletableFuture} so the registry can cancel via
     * {@link CompletableFuture#cancel(boolean)} on kill.
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
     */
    public void markFinished(String subTaskId, SubTaskStatus status, String text, String errorMessage) {
        SubTaskRecord rec = active.remove(subTaskId);
        if (rec == null) return;
        activeCount.decrementAndGet();
        SubTaskRecord finished = new SubTaskRecord(rec.subTaskId(), rec.username(), rec.conversationId(),
                rec.prompt(), status, rec.startedAt(), System.currentTimeMillis(),
                errorMessage, text, null);
        history.put(subTaskId, finished);
        archive(finished);
    }

    /**
     * Attempts to cancel a running sub-task. Returns {@code true} if the task was
     * still running and a {@link CompletableFuture} cancel was issued.
     */
    public boolean kill(String subTaskId) {
        SubTaskRecord rec = active.get(subTaskId);
        if (rec == null) return false;
        CompletableFuture<?> future = rec.future();
        if (future != null) {
            future.cancel(true);
        }
        markFinished(subTaskId, SubTaskStatus.CANCELLED, null, "用户取消");
        return true;
    }

    /**
     * Cancels every active sub-task belonging to the given conversation.
     * Returns the number of cancelled tasks.
     */
    public int killAllByConversation(String conversationId) {
        List<String> ids = new ArrayList<>();
        active.forEach((id, rec) -> {
            if (rec.conversationId().equals(conversationId)) ids.add(id);
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
            if (rec.username().equals(username)) out.add(rec);
        });
        return out;
    }

    public List<SubTaskRecord> listHistory(String username, int limit) {
        Deque<SubTaskRecord> deque = historyByUser.get(username);
        if (deque == null) return List.of();
        // Newest first
        List<SubTaskRecord> out = new ArrayList<>(deque);
        java.util.Collections.reverse(out);
        if (out.size() > limit) return out.subList(0, limit);
        return out;
    }

    private void archive(SubTaskRecord rec) {
        Deque<SubTaskRecord> deque = historyByUser.computeIfAbsent(rec.username(), k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(rec);
            while (deque.size() > maxHistory) {
                deque.removeFirst();
                // Note: the drop is by insertion order (FIFO oldest-out).
                // The history map still has all entries so get() always works.
            }
        }
    }

    /**
     * Immutable view of a sub-task record. Terminal state is decided at construction time
     * via {@link SubTaskStatus}.
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