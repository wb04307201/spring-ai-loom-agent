package cn.wubo.spring.ai.loom.agent.model;

import java.util.List;

public record CleanBatchRequest(
        List<CleanItem> items
) {
    public record CleanItem(String username, String conversationId) {}
}
