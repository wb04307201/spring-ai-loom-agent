package cn.wubo.loom.http.mcp;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.HttpStorage;
import cn.wubo.loom.http.core.profile.Profile;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * FileWatcher 端到端:文件变更 → engine.reload() → server.notifyResourcesListChanged() 推送。
 *
 * <p><b>不依赖 Spring 容器</b>:直构造 {@link HttpEngine} + {@link HttpStorage} +
 * FileWatcher + 自定义 {@link ObjectProvider},用 {@link TempDir} 给 profiles/systems。
 * 这样测的是 FileWatcher 自身逻辑,不被 Spring bean 装配干扰。
 *
 * <p><b>为什么用 Mockito 而非手写 stub</b>:Spring AI 2.0.1 的 {@link McpSyncServer} 是
 * class(非接口),私有构造器,手写 fake 必须实现全部方法且 {@code final} 字段难破;
 * Mockito 直接 {@code mock()} 创建对象,只 stub 用到的方法。
 */
@DisplayName("FileWatcher 端到端:文件变更 → 内存态 + list_changed")
class FileWatcherIT {

    @Test
    @DisplayName("新建 profile.json → engine 内存态可见 + server 收到推送")
    void newProfileTriggersReloadAndPush(@TempDir Path tmp) throws Exception {
        Path profilesDir = tmp.resolve("profiles");
        Files.createDirectories(profilesDir);
        Path systemsDir = tmp.resolve("systems");
        Files.createDirectories(systemsDir);

        // 构造 engine + storage(不走 Spring,直构造)
        HttpStorage storage = new HttpStorage(tmp);
        HttpEngine engine = new HttpEngine(storage.root(), new HttpConfig());

        // 模拟 server:用 Mockito mock + doAnswer 计数 notifyResourcesListChanged 调用
        McpSyncServer mockServer = mock(McpSyncServer.class);
        java.util.concurrent.atomic.AtomicInteger pushed = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv -> {
            pushed.incrementAndGet();
            return null;
        }).when(mockServer).notifyResourcesListChanged();

        ObjectProvider<McpSyncServer> provider = new ObjectProvider<>() {
            @Override
            public McpSyncServer getIfAvailable() {
                return mockServer;
            }

            @Override
            public McpSyncServer getIfUnique() {
                return mockServer;
            }

            @Override
            public McpSyncServer getObject() {
                return mockServer;
            }
        };

        LoomHttpMcpProperties props = new LoomHttpMcpProperties();
        props.setBasePath(tmp.toString());
        props.setAutoReload(true);

        FileWatcher watcher = new FileWatcher(props, engine, storage, provider);
        try {
            // 落盘一个新 profile(直接写文件,模拟外部 admin 进程)
            // —— engine.addProfile 同时会更新内存缓存,绕过 watcher reload 路径;
            // 直接写文件更真实地模拟"另一进程创建 profile"。
            String json = "{\"name\":\"from-fs\",\"description\":\"watch test\"}";
            java.nio.file.Files.writeString(profilesDir.resolve("from-fs.json"), json);

            // 等 watcher 检测 → reload → push(100ms debounce + 余量)
            await().atMost(ofSeconds(10)).untilAsserted(() ->
                    assertThat(pushed.get()).isGreaterThanOrEqualTo(1));
            // 同时用 Mockito verify 二次确认调用发生过
            verify(mockServer, atLeast(1)).notifyResourcesListChanged();
            // reload 后 engine 内存态应能读出
            assertThat(engine.getProfile("from-fs")).isNotNull();
        } finally {
            watcher.stop();
        }
    }
}
