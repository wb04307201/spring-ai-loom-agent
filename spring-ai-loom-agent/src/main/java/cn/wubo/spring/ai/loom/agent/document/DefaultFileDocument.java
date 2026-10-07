package cn.wubo.spring.ai.loom.agent.document;

import cn.wubo.spring.ai.loom.agent.model.FileDocumentRecord;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public class DefaultFileDocument implements IFileDocument {

    private final JdbcTemplate jdbcTemplate;

    public DefaultFileDocument(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private FileDocumentRecord mapFileDocumentRecord(ResultSet rs, int rowNum) throws SQLException {
        return new FileDocumentRecord(
                rs.getString("file_id"),
                rs.getString("document_id")
        );
    }

    @Override
    public List<FileDocumentRecord> getListByFileId(String id) {
        return jdbcTemplate.query(
                "SELECT * FROM file_document WHERE file_id = ?",
                this::mapFileDocumentRecord,
                id
        );
    }

    @Override
    public int[][] insert(List<FileDocumentRecord> fileDocuments) {
        return jdbcTemplate.batchUpdate(
                "INSERT INTO file_document (file_id, document_id) VALUES (?, ?)",
                fileDocuments,
                fileDocuments.size(),
                (ps, argument) -> {
                    ps.setString(1, argument.fileId());
                    ps.setString(2, argument.documentId());
                }
        );
    }

    @Override
    public int deleteByFileId(String id) {
        return jdbcTemplate.update(
                "DELETE FROM file_document WHERE file_id = ?",
                id
        );
    }
}
