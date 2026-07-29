package cn.wubo.spring.ai.loom.agent.file.storage;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.file.IFileStorage;
import cn.wubo.spring.ai.loom.agent.model.FileRecord;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 基于磁盘文件系统的文件存储实现。
 * <p>
 * 将现有 {@code DefaultUpload} 的磁盘操作逻辑迁移至此，
 * 作为可选实现保留。通过 {@code @ConditionalOnMissingBean} 保证
 * 在 {@code DatabaseFileStorage} 未被注册时才生效。
 */
// @Component // commented out: DatabaseFileStorage is the default; enable this if you want disk as fallback
@ConditionalOnMissingBean(IFileStorage.class)
public class DiskFileStorage implements IFileStorage {

    private final IFile file;
    private final String fileBasePath;
    private final String knowledgeBasePath;

    public DiskFileStorage(IFile file, String fileBasePath, String knowledgeBasePath) {
        this.file = file;
        this.fileBasePath = fileBasePath;
        this.knowledgeBasePath = knowledgeBasePath;
    }

    @Override
    public String save(String knowledgeId, String fileName, InputStream inputStream, String mimeType) {
        String username = UserContextHolder.getCurrentUser();
        try {
            Path knowledgeDir = Paths.get(knowledgeBasePath, username, knowledgeId);
            Files.createDirectories(knowledgeDir);
            Path filePath = getUniquePath(knowledgeDir, fileName);
            Files.copy(inputStream, filePath, StandardCopyOption.REPLACE_EXISTING);

            String fileId = UUID.randomUUID().toString();
            FileRecord fileRecord = new FileRecord(
                    fileId,
                    knowledgeId,
                    filePath.getFileName().toString(),
                    filePath.toFile().length(),
                    LocalDateTime.now(),
                    filePath.toString(),
                    "knowledge",
                    resolveMimeType(filePath.getFileName().toString(), mimeType));
            file.insert(fileRecord, username);
            return fileId;
        } catch (IOException e) {
            throw new LoomAgentRuntimeException(e);
        }
    }

    @Override
    public byte[] read(String location) {
        Path path = Path.of(location);
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new LoomAgentRuntimeException(e);
        }
    }

    @Override
    public void delete(String location) {
        try {
            Files.deleteIfExists(Path.of(location));
        } catch (IOException e) {
            throw new LoomAgentRuntimeException(e);
        }
    }

    @Override
    public void deleteByKnowledgeId(String knowledgeId) {
        String username = UserContextHolder.getCurrentUser();
        Path knowledgeDir = Paths.get(knowledgeBasePath, username, knowledgeId);
        if (Files.exists(knowledgeDir)) {
            try {
                Files.walk(knowledgeDir)
                        .filter(Files::isRegularFile)
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
                Files.deleteIfExists(knowledgeDir);
            } catch (IOException e) {
                throw new LoomAgentRuntimeException(e);
            }
        }
    }

    /**
     * 获取不重复的文件路径。如果目标文件已存在，则在文件名后追加 (1)、(2) 等序号。
     */
    private Path getUniquePath(Path targetDir, String fileName) {
        Path resolved = targetDir.resolve(fileName);
        if (!Files.exists(resolved)) {
            return resolved;
        }
        String name = fileName;
        String ext = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            name = fileName.substring(0, dotIndex);
            ext = fileName.substring(dotIndex);
        }
        int counter = 1;
        while (true) {
            String newName = name + "(" + counter + ")" + ext;
            resolved = targetDir.resolve(newName);
            if (!Files.exists(resolved)) {
                return resolved;
            }
            counter++;
        }
    }

    private String resolveMimeType(String fileName, String mimeType) {
        if (StringUtils.hasText(mimeType) && !"application/octet-stream".equals(mimeType))
            return mimeType;
        String guessed = URLConnection.guessContentTypeFromName(fileName);
        if (guessed != null) return guessed;
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : "";
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "txt" -> "text/plain";
            case "csv" -> "text/csv";
            case "md" -> "text/markdown";
            case "html", "htm" -> "text/html";
            case "xml" -> "text/xml";
            case "rtf" -> "application/rtf";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }
}
