package cn.wubo.loom.compile.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LoomCompileMcpProperties.class)
public class LoomCompileMcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(LoomCompileMcpApplication.class, args);
    }
}
