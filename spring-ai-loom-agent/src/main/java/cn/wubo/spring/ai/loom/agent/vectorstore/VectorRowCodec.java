package cn.wubo.spring.ai.loom.agent.vectorstore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/**
 * loom_vector_store 行字段的编解码器(单一职责,便于单测)。
 *
 * <p>embedding 序列化:little-endian,4 bytes/维(float32);无 JSON 开销。
 * 1024 维约 4KB/行。metadata 用 Jackson 序列化(与旧 docs.json 同族工具链)。
 */
public final class VectorRowCodec {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private VectorRowCodec() {
    }

    public static byte[] encodeEmbedding(float[] vector) {
        ByteBuffer bb = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : vector) {
            bb.putFloat(f);
        }
        return bb.array();
    }

    public static float[] decodeEmbedding(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < out.length; i++) {
            out[i] = bb.getFloat();
        }
        return out;
    }

    public static String metadataToJson(Map<String, Object> metadata) {
        try {
            return MAPPER.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception e) {
            throw new IllegalStateException("metadata 序列化失败", e);
        }
    }

    public static Map<String, Object> metadataFromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(json, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            // 单行毒化不拖垮全量加载:调用方(hydrate)捕获后跳过该行
            throw new IllegalStateException("metadata 解析失败: " + e.getMessage(), e);
        }
    }
}
