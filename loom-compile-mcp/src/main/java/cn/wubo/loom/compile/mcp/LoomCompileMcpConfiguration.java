package cn.wubo.loom.compile.mcp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LoomCompileMcpConfiguration {
    @Bean
    public LoomCompileMcpService loomCompileMcpService(LoomCompileMcpProperties properties) {
        return new LoomCompileMcpService(properties);
    }
}
