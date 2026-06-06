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
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class DefaultUpload implements IUpload {

    private final IFile file;
    private final IFileDocument fileDocument;
    private final IDocumentRead documentRead;
    private final VectorStore vectorStore;
    private final IKnowledge knowledge;
    private static final String BASE_PATH = ".local/file";

    public DefaultUpload(IFile file, IFileDocument fileDocument, IDocumentRead documentRead, VectorStore vectorStore, IKnowledge knowledge) {
        this.file = file;
        this.fileDocument = fileDocument;
        this.documentRead = documentRead;
        this.vectorStore = vectorStore;
        this.knowledge = knowledge;
    }

    private Path saveFile(InputStream is, Path filePath) throws IOException {
        Files.createDirectories(filePath.getParent());
        if (Files.exists(filePath)) Files.delete(filePath);
        Files.copy(is, filePath);
        return filePath;
    }

    private void removeFile(Path path) throws IOException {
        Files.deleteIfExists(path);
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
            String fileId = UUID.randomUUID().toString();
            Path filePath = saveFile(is, Paths.get(BASE_PATH, username, "upload", fileId, fileName));
            FileRecord fileRecord = new FileRecord(
                    fileId,
                    null,
                    fileName,
                    filePath.toFile().length(),
                    LocalDateTime.now(),
                    filePath.toString(),
                    "conversation",
                    resolveMimeType(fileName, mimeType)
            );
            file.insert(fileRecord,username);
            return fileId;
        } catch (IOException e) {
            throw new LoomAgentRuntimeException(e);
        }
    }

    @Override
    public String uploadWithKnowledge(InputStream is, String fileName, String mimeType, String knowledgeId) {
        String username = UserContextHolder.getCurrentUser();
        try {
            String fileId = UUID.randomUUID().toString();
            Path filePath = saveFile(is, Paths.get(BASE_PATH, username, "knowledge", knowledgeId, fileId, fileName));
            FileRecord fileRecord = new FileRecord(
                    fileId,
                    knowledgeId,
                    fileName,
                    filePath.toFile().length(),
                    LocalDateTime.now(),
                    filePath.toString(),
                    "knowledge",
                    resolveMimeType(fileName, mimeType)
            );
            file.insert(fileRecord,username);

            Resource resource = file.getResourceById(fileId,username);
            List<Document> documents = documentRead.read(resource, knowledgeId);
            vectorStore.add(documents);

            List<FileDocumentRecord> fileDocumentRecords = documents
                    .stream()
                    .map(document -> new FileDocumentRecord(fileId, document.getId()))
                    .toList();

            fileDocument.insert(fileDocumentRecords);
            return fileId;
        } catch (IOException e) {
            throw new LoomAgentRuntimeException(e);
        }
    }

    @Override
    public int delete(String fileId) {
        String username = UserContextHolder.getCurrentUser();
        FileRecord fileRecord = file.getById(fileId,username);
        if (StringUtils.hasText(fileRecord.knowledgeId())){
            List<FileDocumentRecord> fileDocumentRecords = fileDocument.getListByFileId(fileId);
            vectorStore.delete(fileDocumentRecords.stream().map(FileDocumentRecord::documentId).toList());
            fileDocument.deleteByFileId(fileId);
        }
        try {
            removeFile(Paths.get(fileRecord.path()));
        } catch (IOException e) {
            throw new LoomAgentRuntimeException(e);
        }
        return file.delete(fileId, username);
    }

    @Override
    public int deleteAllKnowledge(String knowledgeId) {
        List<FileRecord> fileRecords = file.list(knowledgeId, UserContextHolder.getCurrentUser());
        for (FileRecord fileRecord : fileRecords) {
            delete(fileRecord.id());
        }
        return knowledge.delete(knowledgeId);
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

}
