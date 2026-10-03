package cn.wubo.loom.http.mcp;

import cn.wubo.loom.http.core.HttpEngine;
import io.modelcontextprotocol.server.McpSyncServer;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Task 11 stub: Task 12 fills start()/stop() with profiles/ + systems/ WatchService
 * and pushes {@code notifications/resources/list_changed} to MCP clients on change.
 */
public class FileWatcher {

    public FileWatcher(LoomHttpMcpProperties props, HttpEngine engine,
                       ObjectProvider<McpSyncServer> serverProvider) {
        /* Task 12 填 */
    }

    public void start() {
        /* Task 12 */
    }

    public void stop() {
        /* Task 12 */
    }
}