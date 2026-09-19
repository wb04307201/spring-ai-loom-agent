package cn.wubo.spring.ai.loom.agent.file;

import cn.wubo.spring.ai.loom.agent.document.IDocumentRead;
import cn.wubo.spring.ai.loom.agent.document.IFileDocument;
import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.knowledge.IKnowledge;
import cn.wubo.spring.ai.loom.agent.model.FileDocumentRecord;
import cn.wubo.spring.ai.loom.agent.model.FileRecord;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
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
 * 上传管线默认实现 —— 两条腿、两种依赖边界(2026-09-19 解耦改造):
 * <ul>
 *   <li><b>聊天附件腿</b>({@link #upload}):落盘 {@code {usersBasePath}/{username}/file/}
 *       + file_info 元数据。<b>不依赖任何 RAG 组件</b> —— 无 embedding 的纯聊天部署
 *       同样完整可用(IUpload bean 恒在,见 autoconfigure StorageConfiguration)。</li>
 *   <li><b>知识上传腿</b>({@link #uploadWithKnowledge} / {@link #delete} 的知识分支):
 *       需要 {@link IDocumentRead}(解析切块)与 {@link VectorStore}(向量化)。
 *       二者经 {@link org.springframework.beans.factory.ObjectProvider} 注入:
 *       降级部署(无 embedding/VectorStore)provider 为空 → 上传抛 503 业务错误、
 *       删除跳过向量清理(元数据/存储照常清),不再把整条上传管线拖下水。</li>
 * </ul>
 */
public class DefaultUpload implements IUpload {

    private final IFile file;
    private final IFileDocument fileDocument;
    private final org.springframework.beans.factory.ObjectProvider<IDocumentRead> documentReadProvider;
    private final org.springframework.beans.factory.ObjectProvider<VectorStore> vectorStoreProvider;
    private final IKnowledge knowledge;
    private final IFileStorage fileStorage;
    private final String usersBasePath;

    public DefaultUpload(IFile file, IFileDocument fileDocument,
                         org.springframework.beans.factory.ObjectProvider<IDocumentRead> documentReadProvider,
                         org.springframework.beans.factory.ObjectProvider<VectorStore> vectorStoreProvider,
                         IKnowledge knowledge, IFileStorage fileStorage, String usersBasePath) {
        this.file = file;
        this.fileDocument = fileDocument;
        this.documentReadProvider = documentReadProvider;
        this.vectorStoreProvider = vectorStoreProvider;
        this.knowledge = knowledge;
        this.fileStorage = fileStorage;
        this.usersBasePath = usersBasePath;
    }

    /** 知识腿依赖门:降级部署(无 embedding/VectorStore)抛 503 业务错误。 */
    private record KnowledgeLeg(IDocumentRead documentRead, VectorStore vectorStore) {
    }

    private KnowledgeLeg knowledgeLegOrThrow() {
        IDocumentRead documentRead = documentReadProvider.getIfAvailable();
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (documentRead == null || vectorStore == null) {
            throw new LoomAgentRuntimeException(503,
                    "当前部署未启用知识空间(未配置 embedding / 无 VectorStore),知识库上传不可用");
        }
        return new KnowledgeLeg(documentRead, vectorStore);
    }

    /**
     * 获取不重复的文件路径。如果目标文件已存在，则在文件名后追加 (1)、(2) 等序号。
     * 例如：test.txt → test(1).txt → test(2).txt
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
        if (StringUtils.hasText(mimeType) && !"application/octet-stream".equals(mimeType)) return mimeType;
        String guessed = URLConnection.guessContentTypeFromName(fileName);
        if (guessed != null) return guessed;
        // Fallback: detect MIME type from file extension
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

    @Override
    public String upload(InputStream is, String fileName, String mimeType) {
        String username = UserContextHolder.getCurrentUser();
        try {
            Path userDir = cn.wubo.loom.file.core.LoomPaths.userFileDir(usersBasePath, username);
            Files.createDirectories(userDir);
            Path filePath = getUniquePath(userDir, fileName);
            Files.copy(is, filePath, StandardCopyOption.REPLACE_EXISTING);
            String fileId = UUID.randomUUID().toString();
            FileRecord fileRecord = new FileRecord(
                    fileId,
                    null,
                    filePath.getFileName().toString(),
                    filePath.toFile().length(),
                    LocalDateTime.now(),
                    filePath.toString(),
                    "conversation",
                    resolveMimeType(filePath.getFileName().toString(), mimeType)
            );
            file.insert(fileRecord, username);
            return fileId;
        } catch (IOException e) {
            throw new LoomAgentRuntimeException(e);
        }
    }

    @Override
    public String uploadWithKnowledge(InputStream is, String fileName, String mimeType, String knowledgeId) {
        // 先过依赖门再落存储,避免降级部署留下孤儿内容
        KnowledgeLeg leg = knowledgeLegOrThrow();
        String username = UserContextHolder.getCurrentUser();
        try {
            // 通过 IFileStorage 保存文件内容（数据库或磁盘）
            String location = fileStorage.save(knowledgeId, fileName, is, mimeType);

            // 读取文件内容用于向量化
            byte[] content = fileStorage.read(location);
            String resolvedMimeType = resolveMimeType(fileName, mimeType);
            Resource resource = new InputStreamResource(new ByteArrayInputStream(content), fileName);
            List<Document> documents = leg.documentRead().read(resource, knowledgeId);
            leg.vectorStore().add(documents);

            // 生成唯一 fileId 用于元数据关联
            String fileId = UUID.randomUUID().toString();

            List<FileDocumentRecord> fileDocumentRecords = documents
                    .stream()
                    .map(document -> new FileDocumentRecord(fileId, document.getId()))
                    .toList();

            // 写入 file_info 元数据行（无论数据库还是磁盘实现都需要）
            FileRecord fileRecord = new FileRecord(
                    fileId,
                    knowledgeId,
                    fileName,
                    content.length,
                    LocalDateTime.now(),
                    location, // 数据库实现 = fileId；磁盘实现 = 磁盘路径
                    "knowledge",
                    resolvedMimeType);
            file.insert(fileRecord, username);
            fileDocument.insert(fileDocumentRecords);
            return fileId;
        } catch (RuntimeException e) {
            throw new LoomAgentRuntimeException(e);
        }
    }

    @Override
    public int delete(String fileId) {
        String username = UserContextHolder.getCurrentUser();
        FileRecord fileRecord = file.getById(fileId, username);
        if (StringUtils.hasText(fileRecord.knowledgeId())) {
            List<FileDocumentRecord> fileDocumentRecords = fileDocument.getListByFileId(fileId);
            // 降级部署(后来关掉 embedding)无 store 可清:跳过向量删除,元数据/存储照常清
            VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
            if (vectorStore != null) {
                vectorStore.delete(fileDocumentRecords.stream().map(FileDocumentRecord::documentId).toList());
            }
            fileDocument.deleteByFileId(fileId);
            // 通过 IFileStorage 删除实际文件内容
            fileStorage.delete(fileId);
        } else {
            // 非知识库文件，从磁盘删除
            try {
                Files.deleteIfExists(Paths.get(fileRecord.path()));
            } catch (IOException e) {
                throw new LoomAgentRuntimeException(e);
            }
        }
        return file.delete(fileId, username);
    }

    @Override
    public int deleteAllKnowledge(String knowledgeId) {
        String username = UserContextHolder.getCurrentUser();
        List<FileRecord> fileRecords = file.list(knowledgeId, username);
        for (FileRecord fileRecord : fileRecords) {
            delete(fileRecord.id());
        }
        // 通过 IFileStorage 删除知识库所有文件（清理残留）
        fileStorage.deleteByKnowledgeId(knowledgeId);
        return knowledge.delete(knowledgeId);
    }

    @Override
    public byte[] getContentByLocation(String location) {
        return fileStorage.read(location);
    }

}
