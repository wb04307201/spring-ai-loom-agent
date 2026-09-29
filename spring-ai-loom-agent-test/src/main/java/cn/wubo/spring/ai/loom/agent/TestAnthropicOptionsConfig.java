package cn.wubo.spring.ai.loom.agent;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Test-app-only override for the Anthropic chat options bean.
 *
 * <p>Spring AI 2.0's official {@code spring.ai.anthropic.chat.options.*} yml surface does NOT
 * expose {@code thinking.type} — yml fields are limited to {@code model / max-tokens /
 * temperature / top-p / top-k / cache-options / http-headers / inference-geo /
 * web-search-tool.* / service-tier} (per Spring AI 2.0 reference docs). To enable the
 * MiniMax Anthropic-compatible endpoint's {@code thinking: {type: adaptive}} block we have
 * to build {@link AnthropicChatOptions} programmatically via {@code thinkingAdaptive()}.
 *
 * <p>This {@code @Primary} bean wins over any auto-configured default. The {@code model} is
 * passed as a String (the SDK accepts {@code model(String)} inherited from
 * {@code DefaultToolCallingChatOptions.Builder}); MiniMax-M3 is not in the
 * Anthropic SDK's {@code Model} enum so we cannot use the {@code model(Model)} overload.
 */
@Configuration
public class TestAnthropicOptionsConfig {

    @Bean
    @Primary
    public AnthropicChatOptions anthropicChatOptions() {
        return AnthropicChatOptions.builder()
                .model("MiniMax-M3")
                .thinkingAdaptive()
                .build();
    }
}