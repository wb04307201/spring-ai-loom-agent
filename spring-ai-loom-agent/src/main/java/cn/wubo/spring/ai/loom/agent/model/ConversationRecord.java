package cn.wubo.spring.ai.loom.agent.model;

public record ConversationRecord(
        String conversationId,
        String title,
        java.time.Instant createdAt,
        java.time.Instant updatedAt) {
}
