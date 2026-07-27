package cn.wubo.spring.ai.loom.agent.model;

import java.util.List;

public record ChatRequestRecord(String message,
                                String conversationId,
                                List<String> mcps,
                                List<String> enabledKnowledgeIds,
                                List<String> fileIds) {
}
