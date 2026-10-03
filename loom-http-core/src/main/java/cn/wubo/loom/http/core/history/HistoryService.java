package cn.wubo.loom.http.core.history;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpStorage;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Per-system JSONL history writer + query reader.
 *
 * <p>Writes one compact JSON object per line to {@code history/<system>.jsonl}.
 * When the file's line count exceeds {@code global.historyMaxEntriesPerSystem},
 * the oldest lines are dropped from the start (rolling trim).
 *
 * <p>Thread-safe: a single internal lock serialises all read/write operations
 * on the same {@code HistoryService} instance. Sufficient for a single-process
 * MCP server.
 */
public class HistoryService {

    private static final String AD_HOC_SYSTEM = "_ad_hoc";
    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_FAILED = "failed";

    private final HttpStorage storage;
    private final HttpConfig global;

    /** Compact mapper — no indentation, ISO instants. Used to write JSONL lines. */
    private final ObjectMapper jsonlMapper;

    /** Tolerant reader — ignores unknown properties and skips malformed lines. */
    private final ObjectMapper jsonlReader;

    private final Object lock = new Object();

    public HistoryService(HttpStorage storage, HttpConfig global) {
        this.storage = storage;
        this.global = global;
        this.jsonlMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.INDENT_OUTPUT);
        this.jsonlReader = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Append one entry to {@code history/<system>.jsonl}, creating the directory
     * if missing and trimming from the start when the file exceeds the
     * configured max entries per system.
     */
    public void append(String system, HistoryEntry entry) {
        String sys = (system == null || system.isEmpty()) ? AD_HOC_SYSTEM : system;
        Path file = storage.historyDir().resolve(sys + ".jsonl");

        synchronized (lock) {
            try {
                Files.createDirectories(storage.historyDir());

                List<String> lines = new ArrayList<>();
                if (Files.exists(file)) {
                    try (Stream<String> s = Files.lines(file, StandardCharsets.UTF_8)) {
                        s.forEach(lines::add);
                    }
                }

                String json = jsonlMapper.writeValueAsString(entry);
                lines.add(json);

                int max = global.getHistoryMaxEntriesPerSystem();
                if (max > 0 && lines.size() > max) {
                    // Keep only the last `max` entries (drop the oldest from the start).
                    List<String> trimmed = new ArrayList<>(lines.subList(lines.size() - max, lines.size()));
                    lines = trimmed;
                }

                writeLines(file, lines);
            } catch (IOException e) {
                throw new RuntimeException("Failed to append history entry for system=" + sys, e);
            }
        }
    }

    /**
     * Read entries for {@code system}, applying optional status and time filters
     * and returning the most-recent {@code limit} entries (descending by
     * timestamp).
     *
     * @param system       {@code null}/empty is treated as {@code _ad_hoc}
     * @param limit        maximum entries to return; {@code <= 0} means no cap
     * @param statusFilter {@code "succeeded"} / {@code "failed"} / {@code null} (all)
     * @param since        if non-null, only entries with {@code timestamp.isAfter(since)}
     *                     are returned
     */
    public List<HistoryEntry> query(String system, int limit, String statusFilter, Instant since) {
        String sys = (system == null || system.isEmpty()) ? AD_HOC_SYSTEM : system;
        Path file = storage.historyDir().resolve(sys + ".jsonl");

        synchronized (lock) {
            if (!Files.exists(file)) return List.of();

            List<HistoryEntry> entries;
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                entries = new ArrayList<>(lines.size());
                for (String line : lines) {
                    if (line.isBlank()) continue;
                    try {
                        entries.add(jsonlReader.readValue(line, HistoryEntry.class));
                    } catch (Exception ignored) {
                        // Skip malformed line — never let one bad row break a query.
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to query history for system=" + sys, e);
            }

            // Sort descending by timestamp (most recent first).
            entries.sort(Comparator.comparing(HistoryEntry::getTimestamp,
                    Comparator.nullsLast(Comparator.reverseOrder())));

            // Apply filters.
            List<HistoryEntry> filtered = new ArrayList<>(entries.size());
            for (HistoryEntry e : entries) {
                if (since != null && e.getTimestamp() != null && !e.getTimestamp().isAfter(since)) {
                    continue;
                }
                if (statusFilter != null && !statusFilter.isEmpty()) {
                    if (STATUS_SUCCEEDED.equalsIgnoreCase(statusFilter) && !isSuccess(e)) continue;
                    if (STATUS_FAILED.equalsIgnoreCase(statusFilter) && !isFailure(e)) continue;
                    // Unknown filter → treated as "all" (no-op)
                }
                filtered.add(e);
            }

            // Apply limit.
            if (limit > 0 && filtered.size() > limit) {
                return new ArrayList<>(filtered.subList(0, limit));
            }
            return filtered;
        }
    }

    // -- helpers --------------------------------------------------------------

    private static boolean isSuccess(HistoryEntry e) {
        if (e.getError() != null) return false;
        return e.getAssertion() == null || e.getAssertion().isPassed();
    }

    private static boolean isFailure(HistoryEntry e) {
        return !isSuccess(e);
    }

    private void writeLines(Path file, List<String> lines) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows / non-POSIX — skip silently.
        }
    }
}
