package cn.wubo.loom.http.mcp;

import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.HttpStorage;
import io.modelcontextprotocol.server.McpSyncServer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 监听 profiles/ + systems/ 目录变更,debounce 后调 {@link HttpEngine#reload()} 并推
 * {@code notifications/resources/list_changed} 到已连 MCP client。
 *
 * <p>比源项目简化点(参见 Task 12 头部):源项目有 {@code ResourcesReloadedEvent} +
 * {@code McpConfig} 两件套是因为兼容 ASYNC/SYNC 两种 server type + 允许通用 Spring 监听;
 * loom-http-mcp 的 application.yml 锁死 SYNC type,直接注入 {@link McpSyncServer} 即可,
 * 事件类直接砍。
 *
 * <p>构造时立即启动({@link LoomHttpMcpProperties#isAutoReload()} 为 false 时跳过);
 * Spring 容器关闭时 {@link PreDestroy} 自动 {@link #stop()}。
 */
public class FileWatcher {

    private static final Logger log = LoggerFactory.getLogger(FileWatcher.class);
    static final long DEBOUNCE_MS = 100L;

    private final LoomHttpMcpProperties props;
    private final HttpEngine engine;
    private final HttpStorage storage;
    private final ObjectProvider<McpSyncServer> serverProvider;

    private WatchService watchService;
    private Thread watchThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService debounceExecutor;
    private volatile ScheduledFuture<?> debounceTask;
    private final Object debounceLock = new Object();

    public FileWatcher(LoomHttpMcpProperties props,
                       HttpEngine engine,
                       HttpStorage storage,
                       ObjectProvider<McpSyncServer> serverProvider) {
        this.props = props;
        this.engine = engine;
        this.storage = storage;
        this.serverProvider = serverProvider;
        if (props.isAutoReload()) start();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        if (props != null && !props.isAutoReload()) {
            log.info("FileWatcher autoReload=false, 跳过启动");
            running.set(false);
            return;
        }
        Path profilesDir = storage.profilesDir();
        Path systemsDir = storage.systemsDir();
        try {
            Files.createDirectories(profilesDir);
            Files.createDirectories(systemsDir);
            watchService = profilesDir.getFileSystem().newWatchService();
            profilesDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            systemsDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException e) {
            running.set(false);
            throw new IllegalStateException("Failed to start FileWatcher", e);
        }
        debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "loom-http-mcp-fw-debounce");
            t.setDaemon(true);
            return t;
        });
        watchThread = new Thread(this::watchLoop, "loom-http-mcp-fw-watch");
        watchThread.setDaemon(true);
        watchThread.start();
        log.info("FileWatcher started, watching {} and {}", profilesDir, systemsDir);
    }

    @PreDestroy
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) { }
        }
        if (watchThread != null) {
            try {
                watchThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (debounceExecutor != null) debounceExecutor.shutdownNow();
        log.info("FileWatcher stopped");
    }

    private void watchLoop() {
        while (running.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            }
            boolean relevant = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                Object ctx = event.context();
                if (ctx instanceof Path p && p.toString().endsWith(".json")) relevant = true;
                else if (ctx == null) relevant = true;
            }
            if (!key.reset()) {
                log.warn("WatchKey no longer valid for {}", key.watchable());
                return;
            }
            if (relevant) scheduleReload();
        }
    }

    private void scheduleReload() {
        synchronized (debounceLock) {
            if (debounceTask != null) debounceTask.cancel(false);
            debounceTask = debounceExecutor.schedule(this::doReload, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void doReload() {
        try {
            engine.reload();
            log.debug("FileWatcher reloaded profile + system caches");
            McpSyncServer server = serverProvider.getIfAvailable();
            if (server != null) {
                try {
                    server.notifyResourcesListChanged();
                } catch (RuntimeException e) {
                    log.warn("notifyResourcesListChanged failed", e);
                }
            } else {
                log.debug("No McpSyncServer available, skipping list_changed push");
            }
        } catch (RuntimeException e) {
            log.warn("FileWatcher reload failed", e);
        }
    }
}
