package cn.wubo.spring.ai.loom.agent.file.storage;

import cn.wubo.spring.ai.loom.agent.file.IFileStorage;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.util.UUID;

/**
 * 基于 H2 数据库的文件存储实现。
 * <p>
 * 文件二进制内容存储在 {@code loom_file_content} 表中，
 * 元数据（路径、大小等）仍由 {@code file_info} 管理。
 * <p>
 * 这是默认实现（通过 {@code LoomAgentConfiguration} 中的 {@code @Bean} 方法注册），
 * 用户可通过自定义 {@code IFileStorage} bean 替换为 S3/MinIO 等。
 */
public class DatabaseFileStorage implements IFileStorage {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseFileStorage(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String save(String knowledgeId, String fileName, InputStream inputStream, String mimeType) {
        String fileId = UUID.randomUUID().toString();
        byte[] content;
        try {
            content = inputStream.readAllBytes();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read input stream", e);
        }

        jdbcTemplate.update(
                "INSERT INTO loom_file_content (file_id, content, mime_type, knowledge_id) VALUES (?, ?, ?, ?)",
                fileId, content, mimeType, knowledgeId);
        return fileId;
    }

    @Override
    public byte[] read(String location) {
        return jdbcTemplate.queryForObject(
                "SELECT content FROM loom_file_content WHERE file_id = ?",
                new Object[]{location},
                byte[].class);
    }

    @Override
    public void delete(String location) {
        jdbcTemplate.update("DELETE FROM loom_file_content WHERE file_id = ?", location);
    }

    @Override
    public void deleteByKnowledgeId(String knowledgeId) {
        jdbcTemplate.update("DELETE FROM loom_file_content WHERE knowledge_id = ?", knowledgeId);
    }
}
