package cn.wubo.loom.http.mcp;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.HttpStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * loom-http-mcp 模块骨架装配测试。
 * <p>jar 启动能进入 Spring 上下文、4 个 bean 可解析、属性从 yml 正确绑定。
 * 工具与资源的契约测试在 Task 12 加（{@code tools/list} / {@code resources/list}）。
 */
@SpringBootTest(classes = LoomHttpMcpApplication.class)
@TestPropertySource(properties = {
        "spring.main.web-application-type=none",
        "spring.ai.mcp.server.stdio=false",       // 测试不拉起 stdio，避免占 stdin
        "loom.http.mcp.basePath=${java.io.tmpdir}/loom-http-mcp-wiring-test",
        "loom.http.mcp.failClosed=false"
})
@DisplayName("loom-http-mcp 骨架装配")
class LoomHttpMcpWiringTest {

    @Autowired private HttpStorage storage;
    @Autowired private HttpConfig  config;
    @Autowired private HttpEngine  engine;
    @Autowired private LoomHttpMcpProperties props;

    @Test
    @DisplayName("属性绑定到 LoomHttpMcpProperties")
    void propertiesBind() {
        assertThat(props.getBasePath()).endsWith("loom-http-mcp-wiring-test");
        assertThat(props.isFailClosed()).isFalse();
        assertThat(props.isAutoReload()).isTrue();
    }

    @Test
    @DisplayName("HttpConfig.failClosed 由属性注入")
    void configExposesFailClosed() {
        assertThat(config.isFailClosed()).isFalse();
    }

    @Test
    @DisplayName("HttpStorage 派生 7 个子目录")
    void storageDerivesSubdirs() {
        Path base = Path.of(props.getBasePath());
        assertThat(storage.root()).isEqualTo(base);
        assertThat(storage.profilesDir()).isEqualTo(base.resolve("profiles"));
        assertThat(storage.systemsDir()).isEqualTo(base.resolve("systems"));
        assertThat(storage.historyDir()).isEqualTo(base.resolve("history"));
        assertThat(storage.responsesDir()).isEqualTo(base.resolve("responses"));
        assertThat(storage.configFile()).isEqualTo(base.resolve("config.json"));
        assertThat(storage.logDir()).isEqualTo(base.resolve("log"));
        assertThat(storage.auditLogFile()).isEqualTo(base.resolve("log/audit.log"));
    }

    @Test
    @DisplayName("HttpEngine bean 可创建，且 reload() 不抛")
    void engineReloadIsCallable() {
        assertThat(engine).isNotNull();
        engine.reload();   // 重复调用安全：HttpEngine 内部 service 的 reload 已是幂等
    }
}