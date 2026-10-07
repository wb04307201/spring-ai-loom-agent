package cn.wubo.spring.ai.loom.agent.document;

import cn.wubo.spring.ai.loom.agent.model.FileDocumentRecord;

import java.util.List;

public interface IFileDocument {

    List<FileDocumentRecord> getListByFileId(String id);

    int[][] insert(List<FileDocumentRecord> fileDocuments);

    int deleteByFileId(String id);

}
