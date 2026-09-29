package cn.wubo.spring.ai.loom.agent;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Test-app-only overrides for the Anthropic chat options + EmbeddingModel beans.
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
 *
 * <p>The fake {@link EmbeddingModel} is required because the test app no longer ships an
 * auto-configured EmbeddingModel (the old {@code spring-ai-alibaba-starter-dashscope} was
 * removed). The library's {@code EmbeddingModelAvailableCondition} reads bean definitions
 * with {@code allowEagerInit=false} and won't materialize a {@code VectorStore} bean when
 * no provider is present. Embedding beans declared in a top-level {@code @Configuration}
 * class on the test app's classpath are visible at condition-evaluation time.
 */
@Configuration
public class TestAnthropicOptionsConfig {

    @Bean
    @Primary
    public AnthropicChatOptions anthropicChatOptions() {
        // DashScope's /apps/anthropic endpoint requires max_tokens > thinking_budget.
        // yml's chat.options.model/multi_model/enable_thinking flow through the auto-config builder,
        // but yml can't express the max-tokens-vs-budget math, so we override here.
        return AnthropicChatOptions.builder()
                .model("qwen3.8-max")
                .maxTokens(16384)
                .temperature(1.0)
                .thinkingEnabled(8192L)
                .build();
    }

    /**
     * 4-dim deterministic fake — enough for {@code h2VectorStore} to instantiate and the
     * RAG condition chain to pass. The test app does not perform real embedding lookups in
     * the unit/IT gate (those tests don't exercise RAG retrieval).
     */
    @Bean
    public EmbeddingModel testFakeEmbeddingModel() {
        return new EmbeddingModel() {
            @Override
            public float[] embed(Document document) { return embed(document.getText()); }

            @Override
            public float[] embed(String text) {
                java.util.Random r = new java.util.Random(text == null ? 0L : text.hashCode());
                float[] v = new float[4];
                for (int i = 0; i < 4; i++) v[i] = r.nextFloat() * 2f - 1f;
                return v;
            }

            @Override
            public int dimensions() { return 4; }

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }
}