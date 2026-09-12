package cn.wubo.loom.maven.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "loom.maven.mcp")
public class LoomMavenMcpProperties {
    /** MCP server 沙箱根（扁平配置模式，不使用主库用户树；4 个 MCP server 默认共享）。 */
    private String basePath = System.getProperty("user.home") + "/.loom/mcp";
    private String mavenHome;
    private String localRepository;
    private int maxOutputLines = 200;
    private long defaultTimeoutMs = 300000;
}
