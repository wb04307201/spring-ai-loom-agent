package cn.wubo.loom.git.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "loom.git.mcp")
public class LoomGitMcpProperties {
    private String basePath = ".local/file";
    private String gitUsername = "";
    private String gitToken = "";
    private int remoteTimeoutSeconds = 60;
}
