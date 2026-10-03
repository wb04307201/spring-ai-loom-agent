package cn.wubo.loom.http.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * loom-http-mcp 独立 jar 的配置属性。
 * <p>扁平 basePath 配置（不依赖主库 {@code usersBasePath}），与 {@code loom-file-mcp} 等
 * 4 个 MCP server 的 basePath 并列。详见 Task 11 头部"两条必须先裁定的隔离要点"。
 */
@Data
@ConfigurationProperties(prefix = "loom.http.mcp")
public class LoomHttpMcpProperties {

    /**
     * 存储根（profile / system / history JSON 落点）。
     * <p>默认 {@code ~/.loom/http-mcp} —— <strong>刻意</strong>不与
     * {@code ~/.loom/mcp} 共享：profile 含凭据明文，独立根避免与
     * loom-file-mcp 的可读沙箱混在一起构成权限放大。
     */
    private String basePath = System.getProperty("user.home") + "/.loom/http-mcp";

    /**
     * 白名单 fail-closed 开关。spec §4.4：内部工具模式恒 true，jar 默认 false
     * （保持 http-mcp 既有 fail-open 行为，不破坏已部署用户）。
     */
    private boolean failClosed = false;

    /**
     * 是否启用 FileWatcher 监听 profiles/ + systems/ 目录变更并推 list_changed。
     * 测试场景可关掉，避免与文件监听线程竞争。
     */
    private boolean autoReload = true;
}