package cn.wubo.loom.maven.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "loom.maven.mcp")
public class LoomMavenMcpProperties {
    private String basePath = ".local/file";
    private String mavenHome;
    private String localRepository;
    private int maxOutputLines = 200;
    private long defaultTimeoutMs = 300000;
}
