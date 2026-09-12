package cn.wubo.loom.git.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "loom.git.mcp")
public class LoomGitMcpProperties {
    /** MCP server 沙箱根（扁平配置模式，不使用主库用户树；4 个 MCP server 默认共享）。 */
    private String basePath = System.getProperty("user.home") + "/.loom/mcp";
    private String gitUsername = "";
    private String gitToken = "";
    private int remoteTimeoutSeconds = 60;
}
