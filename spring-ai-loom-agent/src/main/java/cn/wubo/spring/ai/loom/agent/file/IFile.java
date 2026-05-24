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

    List<FileRecord> searchByFileName(String fileNamePattern, String username);

    int update(String id, String newPath, String newName, Long newSize,String username);

    FileRecord getByPath(String path, String username);

    List<FileRecord> searchByPath(String pathPattern, String username);
}
