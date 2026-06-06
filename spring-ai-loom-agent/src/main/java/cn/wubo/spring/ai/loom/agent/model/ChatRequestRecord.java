package cn.wubo.spring.ai.loom.agent.model;

import java.util.List;

public record ChatRequestRecord(String message,
                                String conversationId,
                                List<String> mcps,
                                String knowledgeId,
                                List<String> fileIds) {
}
