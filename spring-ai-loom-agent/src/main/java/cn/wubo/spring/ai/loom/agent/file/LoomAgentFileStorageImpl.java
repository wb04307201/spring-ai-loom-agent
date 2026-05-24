package cn.wubo.spring.ai.loom.agent.file;

import cn.wubo.file.view.storage.IFileStorage;
import cn.wubo.file.view.storage.dto.FileStorageInfo;
import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class LoomAgentFileStorageImpl implements IFileStorage {

    private final JdbcTemplate jdbcTemplate;

    public LoomAgentFileStorageImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public FileStorageInfo upload(String fileName, byte[] content, String mimeType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FileStorageInfo findById(String id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, file_name, size, mime_type, path FROM file_info WHERE id = ?",
                    (rs, rowNum) -> new FileStorageInfo(
                            rs.getString("id"),
                            rs.getString("file_name"),
                            rs.getLong("size"),
                            rs.getString("mime_type"),
                            rs.getString("path"),
                            "1"
                    ),
                    id
            );
        } catch (Exception e) {
            throw new LoomAgentRuntimeException("文件不存在");
        }
    }

    @Override
    public List<FileStorageInfo> list() {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] getContentByLocation(String location) {
        Path path = Path.of(location);
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new LoomAgentRuntimeException(e);
        }
    }

    @Override
    public Boolean deleteById(String id) {
        throw new UnsupportedOperationException();
    }
}
