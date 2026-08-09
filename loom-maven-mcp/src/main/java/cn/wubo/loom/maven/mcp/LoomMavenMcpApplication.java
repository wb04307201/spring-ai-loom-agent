package cn.wubo.loom.maven.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LoomMavenMcpProperties.class)
public class LoomMavenMcpApplication {
 public static void main(String[] args) {
 SpringApplication.run(LoomMavenMcpApplication.class, args);
 }
}
