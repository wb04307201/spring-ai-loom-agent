package cn.wubo.spring.ai.loom.agent.file;

import java.io.InputStream;

public interface IUpload {

    String upload(InputStream is, String fileName, String mimeType);

    String uploadWithKnowledge(InputStream is, String fileName, String mimeType, String knowledgeId);

    int delete(String fileId);

    int deleteAllKnowledge(String knowledgeId);

    byte[] getContentByLocation(String location);
}
