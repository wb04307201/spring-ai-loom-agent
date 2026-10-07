package cn.wubo.loom.http.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * loom-http-mcp 入口。
 * <p>stdio MCP server（{@code spring.main.web-application-type: none} 在 application.yml 设置），
 * 通过 jbang 拉起（参见 README）。
 */
@SpringBootApplication
@EnableConfigurationProperties(LoomHttpMcpProperties.class)
public class LoomHttpMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoomHttpMcpApplication.class, args);
    }
}