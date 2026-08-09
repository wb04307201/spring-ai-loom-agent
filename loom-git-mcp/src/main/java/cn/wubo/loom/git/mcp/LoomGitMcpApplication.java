package cn.wubo.loom.git.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LoomGitMcpProperties.class)
public class LoomGitMcpApplication {
 public static void main(String[] args) {
 SpringApplication.run(LoomGitMcpApplication.class, args);
 }
}
