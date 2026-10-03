package cn.wubo.loom.http.mcp;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.HttpStorage;
import io.modelcontextprotocol.server.McpSyncServer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

/**
 * 手搭 DI —— {@link HttpEngine} 内部按 (Path, HttpConfig) 构造所有服务，
 * 对外只暴露 HttpEngine + (ProfileService, SystemService 在 reload() 路径里)。
 */
@Configuration
public class LoomHttpMcpConfiguration {

    @Bean
    public HttpStorage httpStorage(LoomHttpMcpProperties props) {
        return new HttpStorage(Paths.get(props.getBasePath()));
    }

    @Bean
    public HttpConfig httpConfig(LoomHttpMcpProperties props) {
        HttpConfig cfg = new HttpConfig();
        cfg.setFailClosed(props.isFailClosed());
        return cfg;
    }

    @Bean
    public HttpEngine httpEngine(HttpStorage storage, HttpConfig config) {
        return new HttpEngine(storage.root(), config);
    }

    // --- jar 独占服务（Task 12 实现，本步骤先建空 stub bean 占位） ---

    @Bean
    public LoomHttpMcpService loomHttpMcpService(HttpEngine engine) {
        return new LoomHttpMcpService(engine);
    }

    @Bean
    public LoomHttpService loomHttpService(HttpEngine engine) {
        return new LoomHttpService(engine);
    }

    @Bean
    public LoomHttpMcpResources loomHttpMcpResources(HttpEngine engine) {
        return new LoomHttpMcpResources(engine);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public FileWatcher httpFileWatcher(LoomHttpMcpProperties props,
                                       HttpEngine engine,
                                       ObjectProvider<McpSyncServer> mcpServerProvider) {
        return new FileWatcher(props, engine, mcpServerProvider);
    }
}