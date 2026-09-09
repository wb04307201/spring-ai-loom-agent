package cn.wubo.spring.ai.loom.agent.tool.common;

import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.model.FileRecord;
import cn.wubo.spring.ai.loom.agent.util.TikaUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 路径 → fileId 桥接(getOrCreateFileId),从 DefaultFileTool 私有方法原样抽出
 * (行为零变化),供 DefaultFileTool 的预览/下载链接与 DefaultHtmlRenderTool 的
 * 截图链接共用(spec D7:完全复用既有 usage='temp' 桥接机制,零新基建)。
 * <p>
 * 语义:已存在记录 → 尺寸变化时 update,返回原 id;不存在 → Tika 探测 mimeType,
 * UUID 生成 fileId,insert usage='temp' 行;任何异常 → 返回 null(调用方转失败文本)。
 */
public class FileIdBridge {

    private final IFile file;

    public FileIdBridge(IFile file) {
        this.file = file;
    }

    public String getOrCreateFileId(Path filePath, String username) {
        try {
            String pathStr = filePath.toString();
            FileRecord existing = file.getByExactPath(pathStr, username);
            if (existing != null) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
                    if (attrs.size() != existing.size()) {
                        file.update(existing.id(), pathStr, filePath.getFileName().toString(), attrs.size(), username);
                    }
                } catch (Exception ignored) {
                    // 读取 / 更新失败时仍然返回已有 id
                }
                return existing.id();
            }

            String mimeType = TikaUtils.TIKA.detect(filePath.toFile());
            if (mimeType == null) mimeType = "application/octet-stream";
            String fileId = UUID.randomUUID().toString();
            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
            file.insert(new FileRecord(
                    fileId,
                    null,
                    filePath.getFileName().toString(),
                    attrs.size(),
                    LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault()),
                    pathStr,
                    "temp",
                    mimeType
            ), username);
            return fileId;
        } catch (Exception e) {
            return null;
        }
    }
}
