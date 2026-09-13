package cn.wubo.spring.ai.loom.agent.vectorstore;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 确定性 fake embedder:hash 播种的伪随机向量,同文本恒等向量。
 *
 * <p>关键点:覆盖 dimensions() —— 接口 default 实现会调 embed("Test String")
 * 再走真实网络/模型链路;fake 必须短路。embedCount() 供 no-re-embed 断言
 * (hydrate 后计数不得增长)。
 */
public class FakeEmbeddingModel implements EmbeddingModel {

    private final int dim;
    private final AtomicInteger embedCalls = new AtomicInteger();

    public FakeEmbeddingModel(int dim) {
        this.dim = dim;
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        embedCalls.incrementAndGet();
        float[] v = new float[dim];
        long seed = text == null ? 0L : text.hashCode();
        java.util.Random rnd = new java.util.Random(seed);
        for (int i = 0; i < dim; i++) {
            v[i] = rnd.nextFloat() * 2f - 1f;
        }
        return v;
    }

    @Override
    public int dimensions() {
        return dim;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        throw new UnsupportedOperationException("fake 不支持批量 call");
    }

    public int embedCount() {
        return embedCalls.get();
    }
}
