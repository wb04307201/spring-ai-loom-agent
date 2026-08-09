package cn.wubo.spring.ai.loom.agent.model;

import java.time.LocalDateTime;

public record FileRecord(
 String id,
 String knowledgeId,
 String fileName,
 long size,
 LocalDateTime uploadTime,
 String path,
 String usage,
 String mimeType
) {
}
