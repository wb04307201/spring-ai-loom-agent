package cn.wubo.spring.ai.loom.agent.file;

import cn.wubo.spring.ai.loom.agent.model.FileRecord;
import org.springframework.core.io.Resource;

import java.util.List;

public interface IFile {

    List<FileRecord> list(String knowledgeId, String username);

    int insert(FileRecord fileInfo, String username);

    int delete(String id, String username);

    FileRecord getById(String id, String username);

    Resource getResourceById(String id, String username);

    /** 按文件路径精确查询已注册的文件记录 */
    FileRecord getByExactPath(String path, String username);

    /** 按关键词搜索文件（匹配文件名或路径） */
    List<FileRecord> search(String keyword, String username);

    /** 返回 usage != 'knowledge' 的所有文件，用于文件管理器 */
    List<FileRecord> listForManager(String username);

    int update(String id, String newPath, String newName, Long newSize, String username);
}
