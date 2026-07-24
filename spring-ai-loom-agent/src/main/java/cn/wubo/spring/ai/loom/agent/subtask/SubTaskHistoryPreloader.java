package cn.wubo.spring.ai.loom.agent.subtask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.Set;

/**
 * Cold-start rehydration of {@link SubTaskRegistry}'s in-memory history from the
 * persistent {@link ILoomSubTaskHistoryRepository} (H2 table {@code loom_subtask_history}).
 * <p>
 * Without this, the in-memory ring buffer starts empty after every restart and the
 * frontend's history list shows nothing until a new sub-task fires. The preloader
 * restores up to {@code maxHistory} records per user so the very first API call
 * after restart already has the full history visible.
 * </p>
 * <p>
 * Records are inserted in oldest-first order into the per-user deque (the registry's
 * deque semantics rely on FIFO ordering so that "newest first" iteration is the
 * reverse of insertion order).
 * </p>
 */
public class SubTaskHistoryPreloader {

    private static final Logger log = LoggerFactory.getLogger(SubTaskHistoryPreloader.class);

    private final SubTaskRegistry registry;
    private final ILoomSubTaskHistoryRepository repo;
    private final int maxHistory;

    public SubTaskHistoryPreloader(SubTaskRegistry registry,
                                   ILoomSubTaskHistoryRepository repo,
                                   int maxHistory) {
        this.registry = registry;
        this.repo = repo;
        this.maxHistory = maxHistory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void preload() {
        // At startup no user is logged in yet — we must enumerate via the repo.
        Set<String> users = repo.findAllUsernames();
        if (users.isEmpty()) {
            log.info("SubTaskHistoryPreloader: no persisted history to rehydrate");
            return;
        }
        int totalLoaded = 0;
        for (String user : users) {
            List<SubTaskRegistry.SubTaskRecord> rows = repo.findByUsername(user, maxHistory);
            if (rows.isEmpty()) continue;
            // Oldest first — repository returns newest first; flip order so deque
            // insertion order is chronological, preserving newest-first iteration.
            for (int i = rows.size() - 1; i >= 0; i--) {
                registry.rehydrate(rows.get(i));
            }
            totalLoaded += rows.size();
        }
        log.info("SubTaskHistoryPreloader: rehydrated {} record(s) across {} user(s) into SubTaskRegistry",
                totalLoaded, users.size());
    }
}