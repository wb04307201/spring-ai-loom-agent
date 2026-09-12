package cn.wubo.loom.git.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "loom.git.mcp")
public class LoomGitMcpProperties {
    /** 用户文件根目录（绝对路径默认，杜绝 cwd 相对漂移）。 */
    private String basePath = System.getProperty("user.home") + "/.loom/file";
    private String gitUsername = "";
    private String gitToken = "";
    private int remoteTimeoutSeconds = 60;
}
