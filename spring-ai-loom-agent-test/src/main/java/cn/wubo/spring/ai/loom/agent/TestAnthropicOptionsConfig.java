package cn.wubo.spring.ai.loom.agent;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Test-app-only override: 提供 EmbeddingModel fake bean (chat provider 完全 yml 驱动).
 *
 * <p>历史 (2026-10-01 之前): 同时持有 {@code AnthropicChatOptions @Primary} bean (Spring AI
 * 2.0 yml 无法表达 {@code thinking.type=adaptive}, 用 builder 程序化) + {@code EmbeddingModel}
 * fake。A 方案 (OpenAI SDK + DashScope 兼容端点) 切换后:
 * <ul>
 *   <li>{@code AnthropicChatOptions} 类已不在 classpath → 该 @Bean 方法引用即启动失败。
 *       OpenAI SDK yml {@code spring.ai.openai.chat.options.*} 能覆盖 model/temperature,
 *       {@code enable_thinking} 走 extra-body, 无需程序化 builder。</li>
 *   <li>fake EmbeddingModel 必须保留: RAG 链的 {@code EmbeddingModelAvailableCondition}
 *       需要 bean 定义才能实例化 VectorStore。test app 没有真 embedding provider
 *       (旧 {@code spring-ai-alibaba-starter-dashscope} 已删), fake 让 h2VectorStore 能起。</li>
 * </ul>
 *
 * <p>类名保留 {@code TestAnthropicOptionsConfig} 不改名 — 改成通用名字会触发别的引用,
 * 等 A 方案定下来再统一清理。
 */
@Configuration
public class TestAnthropicOptionsConfig {

    /**
     * 4-dim deterministic fake — enough for {@code h2VectorStore} to instantiate and the
     * RAG condition chain to pass. The test app does not perform real embedding lookups in
     * the unit/IT gate (those tests don't exercise RAG retrieval).
     *
     * <p>2026-11-07 加属性闸 {@code loom.test.fake-embedding.enabled}（默认 true，IT 不受影响）：
     * 该 fake bean 在场时 {@code EmbeddingModelAvailableCondition} 第 1 步「bean 定义已在场」
     * 恒 match，yml 的 {@code spring.ai.model.embedding(.text)=none} 无法关掉 RAG 链；
     * 需要真实验「无 embedding 部署」形态时设 false，让条件走第 2 步 noMatch → 不建 VectorStore。
     */
    @Bean
    @Primary
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "loom.test.fake-embedding.enabled", havingValue = "true", matchIfMissing = true)
    public EmbeddingModel testFakeEmbeddingModel() {
        return new EmbeddingModel() {
            @Override
            public float[] embed(Document document) { return embed(document.getText()); }

            @Override
            public float[] embed(String text) {
                java.util.Random r = new java.util.Random(text == null ? 0L : text.hashCode());
                float[] v = new float[4];
                for (int i = 0; i < v.length; i++) v[i] = r.nextFloat() * 2f - 1f;
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
