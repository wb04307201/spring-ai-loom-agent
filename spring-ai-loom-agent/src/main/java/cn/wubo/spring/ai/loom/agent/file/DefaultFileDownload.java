package cn.wubo.spring.ai.loom.agent.file;

import cn.wubo.spring.ai.loom.agent.model.FileRecord;

/**
 * 默认文件下载/预览实现。
 * <p>
 * 根据 {@code FileRecord.path} 的来源（IFileStorage.save 返回的 UUID 或磁盘路径），
 * 透明地使用 {@code IFileStorage.read()} 读取文件内容。
 * 通过 {@code @ConditionalOnMissingBean} 允许替换为自定义实现。
 */
public class DefaultFileDownload implements IFileDownload {

    private final IFile fileService;
    private final IFileStorage fileStorage;

    public DefaultFileDownload(IFile fileService, IFileStorage fileStorage) {
        this.fileService = fileService;
        this.fileStorage = fileStorage;
    }

    @Override
    public String getDownloadUrl(String fileId) {
        return "/spring/ai/loom/api/file/" + fileId + "/download";
    }

    @Override
    public String getPreviewUrl(String fileId) {
        return "/spring/ai/loom/api/file/" + fileId + "/preview";
    }

    @Override
    public byte[] readFileContent(String fileId, String username) {
        FileRecord record = getFileRecord(fileId, username);
        return fileStorage.read(record.path());
    }

    @Override
    public FileRecord getFileRecord(String fileId, String username) {
        FileRecord record = fileService.getById(fileId, username);
        if (record == null) {
            throw new IllegalArgumentException("File not found: " + fileId);
        }
        return record;
    }
}
