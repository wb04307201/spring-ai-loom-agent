package cn.wubo.spring.ai.loom.agent.file;

import cn.wubo.spring.ai.loom.agent.model.FileRecord;

/**
 * 文件下载与预览接口，提供知识库文件的下载/预览 URL 生成和内容读取。
 * <p>
 * 通过 {@code @ConditionalOnMissingBean} 允许替换为自定义实现。
 */
public interface IFileDownload {

    /**
     * 获取文件下载 URL
     *
     * @param fileId 文件 ID
     * @return 下载 URL
     */
    String getDownloadUrl(String fileId);

    /**
     * 获取文件预览 URL
     *
     * @param fileId 文件 ID
     * @return 预览 URL
     */
    String getPreviewUrl(String fileId);

    /**
     * 读取文件内容字节数组，用于下载/预览
     *
     * @param fileId   文件 ID
     * @param username 当前用户名（用于权限校验）
     * @return 文件内容字节数组
     */
    byte[] readFileContent(String fileId, String username);

    /**
     * 获取文件元数据记录
     *
     * @param fileId   文件 ID
     * @param username 当前用户名（用于权限校验）
     * @return 文件记录
     */
    FileRecord getFileRecord(String fileId, String username);
}
