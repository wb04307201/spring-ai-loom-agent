package cn.wubo.loom.git.mcp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LoomGitMcpConfiguration {
    @Bean
    public LoomGitMcpService loomGitMcpService(LoomGitMcpProperties properties) {
        return new LoomGitMcpService(properties);
    }
}
