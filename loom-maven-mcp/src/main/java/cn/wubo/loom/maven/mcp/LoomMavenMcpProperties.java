package cn.wubo.loom.maven.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "loom.maven.mcp")
public class LoomMavenMcpProperties {
    /** 用户文件根目录（绝对路径默认，杜绝 cwd 相对漂移）。 */
    private String basePath = System.getProperty("user.home") + "/.loom/file";
    private String mavenHome;
    private String localRepository;
    private int maxOutputLines = 200;
    private long defaultTimeoutMs = 300000;
}
