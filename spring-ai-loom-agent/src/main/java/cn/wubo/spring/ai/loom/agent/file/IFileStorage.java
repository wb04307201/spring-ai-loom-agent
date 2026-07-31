package cn.wubo.spring.ai.loom.agent.file;

import java.io.InputStream;

/**
 * 抽象文件存储接口，支持数据库和磁盘两种实现。
 * <p>
 * 用于知识库文件的二进制内容存储。通过 {@code @ConditionalOnMissingBean}
 * 允许用户替换为自定义实现（如 S3、MinIO 等）。
 */
public interface IFileStorage {

    /**
     * 保存文件到存储
     *
     * @param knowledgeId 知识库 ID
     * @param fileName    文件名
     * @param inputStream 文件内容
     * @param mimeType    MIME 类型
     * @return 存储位置标识（数据库为 fileId，磁盘为路径）
     */
    String save(String knowledgeId, String fileName, InputStream inputStream, String mimeType);

    /**
     * 读取文件内容
     *
     * @param location 存储位置标识
     * @return 文件内容字节数组
     */
    byte[] read(String location);

    /**
     * 删除文件
     *
     * @param location 存储位置标识
     */
    void delete(String location);

    /**
     * 删除知识库所有文件
     *
     * @param knowledgeId 知识库 ID
     */
    void deleteByKnowledgeId(String knowledgeId);
}
