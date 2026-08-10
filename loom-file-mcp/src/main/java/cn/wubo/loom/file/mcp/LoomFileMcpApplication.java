package cn.wubo.loom.file.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Loom File MCP Server 应用程序入口。
 * <p>
 * 这是一个独立的可执行 jar，提供文件操作的 MCP 服务。
 * 可以通过以下命令启动：
 * <pre>
 * java -jar loom-file-mcp.jar --loom.file.mcp.basePath=/path/to/files
 * </pre>
 * </p>
 */
@SpringBootApplication
@EnableConfigurationProperties(LoomFileMcpProperties.class)
public class LoomFileMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoomFileMcpApplication.class, args);
    }

}
