package cn.wubo.spring.ai.loom.agent.vectorstore;

/**
 * loom_vector_store 一行(不含 created_at —— 运行时无消费方)。
 * score 可空,镜像旧 docs.json 的 score 字段。
 */
public record H2VectorRow(
        String documentId,
        String content,
        String metadataJson,
        byte[] embedding,
        int dim,
        Double score) {
}
