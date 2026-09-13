package cn.wubo.spring.ai.loom.agent.vectorstore;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VectorRowCodecTest {

    @Test
    void embeddingRoundTrip_isBitwiseIdentical() {
        float[] v = {0.0f, 1.5f, -2.25f, Float.MAX_VALUE, 1e-30f};
        // 确定性往返,逐位相等,无需容差(containsExactly 不接受 Offset 参数)
        assertThat(VectorRowCodec.decodeEmbedding(VectorRowCodec.encodeEmbedding(v)))
                .containsExactly(v);
    }

    @Test
    void encoding_isLittleEndianFourBytesPerDim() {
        byte[] bytes = VectorRowCodec.encodeEmbedding(new float[]{1.0f, -2.5f});
        assertThat(bytes).hasSize(8);
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(bb.getFloat(0)).isEqualTo(1.0f);
        assertThat(bb.getFloat(4)).isEqualTo(-2.5f);
    }

    @Test
    void metadataRoundTrip_preservesTypicalChunkKeys() {
        Map<String, Object> md = Map.of(
                "type", "knowledge",
                "knowledgeId", "kb-123",
                "doc_index", 3,
                "weight", 0.75);
        Map<String, Object> back = VectorRowCodec.metadataFromJson(VectorRowCodec.metadataToJson(md));
        assertThat(back)
                .containsEntry("type", "knowledge")
                .containsEntry("knowledgeId", "kb-123");
        assertThat(((Number) back.get("doc_index")).intValue()).isEqualTo(3);
        assertThat(((Number) back.get("weight")).doubleValue()).isEqualTo(0.75);
    }

    @Test
    void metadataFromJson_nullOrBlank_returnsEmptyMap() {
        assertThat(VectorRowCodec.metadataFromJson(null)).isEmpty();
        assertThat(VectorRowCodec.metadataFromJson("")).isEmpty();
    }

    @Test
    void metadataToJson_null_returnsEmptyJsonObject() {
        assertThat(VectorRowCodec.metadataToJson(null)).isEqualTo("{}");
    }
}
