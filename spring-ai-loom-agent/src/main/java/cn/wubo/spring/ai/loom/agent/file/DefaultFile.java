package cn.wubo.spring.ai.loom.agent.file;

import cn.wubo.spring.ai.loom.agent.model.FileRecord;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class DefaultFile implements IFile {

    private final JdbcTemplate jdbcTemplate;

    public DefaultFile(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private FileRecord mapFileRecord(ResultSet rs, int rowNum) throws SQLException {
        return new FileRecord(
                rs.getString("id"),
                rs.getString("knowledge_id"),
                rs.getString("file_name"),
                rs.getLong("size"),
                rs.getTimestamp("upload_time") != null ? rs.getTimestamp("upload_time").toLocalDateTime() : null,
                rs.getString("path"),
                rs.getString("usage"),
                rs.getString("mime_type")
        );
    }

    @Override
    public List<FileRecord> list(String knowledgeId, String username) {
        if (StringUtils.hasText(knowledgeId)) {
            return jdbcTemplate.query(
                    "SELECT * FROM file_info WHERE knowledge_id = ? AND username = ?",
                    this::mapFileRecord,
                    knowledgeId,
                    username
            );
        } else {
            return jdbcTemplate.query(
                    "SELECT * FROM file_info WHERE knowledge_id IS NULL AND username = ?",
                    this::mapFileRecord,
                    username
            );
        }
    }

    @Override
    public int insert(FileRecord fileInfo, String username) {
        return jdbcTemplate.update(
                "INSERT INTO file_info (id, username, knowledge_id, file_name, size, upload_time, path, usage, mime_type) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                fileInfo.id(),
                username,
                fileInfo.knowledgeId(),
                fileInfo.fileName(),
                fileInfo.size(),
                fileInfo.uploadTime(),
                fileInfo.path(),
                fileInfo.usage(),
                fileInfo.mimeType()
        );
    }

    @Override
    public int delete(String id, String username) {
        return jdbcTemplate.update(
                "DELETE FROM file_info WHERE id = ? AND username = ?",
                id,
                 username
        );
    }

    @Override
    public FileRecord getById(String id, String username) {
        return jdbcTemplate.queryForObject(
                "SELECT * FROM file_info WHERE id = ? AND username = ?",
                this::mapFileRecord,
                id,
                 username
        );
    }

    @Override
    public Resource getResourceById(String id, String username) {
        FileRecord fileRecord = getById(id,username);
        return new FileSystemResource(fileRecord.path());
    }

    @Override
    public List<FileRecord> searchByFileName(String fileNamePattern, String username) {
        return jdbcTemplate.query(
                "SELECT * FROM file_info WHERE file_name LIKE ? AND username = ? AND knowledge_id IS NULL",
                this::mapFileRecord,
                "%" + fileNamePattern + "%",
                username
        );
    }

    @Override
    public int update(String id, String newPath, String newName, Long newSize, String username) {
        StringBuilder sql = new StringBuilder("UPDATE file_info SET");
        boolean first = true;
        if (newPath != null) {
            sql.append(" path = ?");
            first = false;
        }
        if (newName != null) {
            if (!first) sql.append(",");
            sql.append(" file_name = ?");
        }
        if (newSize != null) {
            if (!first) sql.append(",");
            sql.append(" size = ?");
        }
        sql.append(" WHERE id = ? AND username = ?");

        List<Object> params = new ArrayList<>();
        if (newPath != null) params.add(newPath);
        if (newName != null) params.add(newName);
        if (newSize != null) params.add(newSize);
        params.add(id);
        params.add(username);

        return jdbcTemplate.update(sql.toString(), params.toArray());
    }

    @Override
    public FileRecord getByPath(String path, String username) {
        List<FileRecord> records = jdbcTemplate.query(
                "SELECT * FROM file_info WHERE path = ? AND username = ? AND knowledge_id IS NULL",
                this::mapFileRecord,
                path, username
        );
        return records.isEmpty() ? null : records.get(0);
    }

    @Override
    public List<FileRecord> searchByPath(String pathPattern, String username) {
        return jdbcTemplate.query(
                "SELECT * FROM file_info WHERE path LIKE ? AND username = ? AND knowledge_id IS NULL",
                this::mapFileRecord,
                "%" + pathPattern + "%",
                username
        );
    }
}
