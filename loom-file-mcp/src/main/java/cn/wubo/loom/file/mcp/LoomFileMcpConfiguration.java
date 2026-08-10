package cn.wubo.loom.file.mcp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Loom File MCP 自动配置。
 */
@Configuration
public class LoomFileMcpConfiguration {

    @Bean
    public LoomFileMcpService loomFileMcpService(LoomFileMcpProperties properties) {
        return new LoomFileMcpService(properties);
    }

}
