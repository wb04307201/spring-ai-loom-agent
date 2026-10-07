package cn.wubo.loom.http.core;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * HTTP 能力的存储根派生（无 Spring，原 http-mcp 的 {@code StorageConfig}）。
 *
 * <p><b>与 http-mcp 的关键差异</b>：原实现从 {@code ${mcp.storage.root}} + 环境变量
 * {@code MCP_STORAGE_DIR} 派生，是<b>单一全局根</b>。本类改为<b>构造传入</b>，
 * 因为内部工具模式必须按用户隔离（{@code ~/.loom/users/{username}/http/}），
 * 而 jar 模式保持扁平（{@code ~/.loom/mcp/http/}）—— 存储根是<b>数据</b>，
 * 不是全局配置。这是双模差别的全部所在。
 */
public final class HttpStorage {

    private final Path root;

    public HttpStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public static HttpStorage of(String path) {
        return new HttpStorage(Paths.get(path));
    }

    public Path root() { return root; }
    public Path profilesDir()  { return root.resolve("profiles"); }
    public Path systemsDir()   { return root.resolve("systems"); }
    public Path historyDir()   { return root.resolve("history"); }
    public Path responsesDir() { return root.resolve("responses"); }
    public Path configFile()   { return root.resolve("config.json"); }
    public Path logDir()       { return root.resolve("log"); }
    public Path auditLogFile() { return logDir().resolve("audit.log"); }
}
