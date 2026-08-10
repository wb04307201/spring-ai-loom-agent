package cn.wubo.loom.maven.mcp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LoomMavenMcpConfiguration {
    @Bean
    public LoomMavenMcpService loomMavenMcpService(LoomMavenMcpProperties properties) {
        return new LoomMavenMcpService(properties);
    }
}
