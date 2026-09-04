package cn.wubo.spring.ai.loom.agent.market;

import java.util.List;

public record MarketFilter(
    int page, int size,
    MarketContentStatus status,
    String category,
    String query,           // SQL LIKE on name/description
    String sortBy           // e.g. "official_rank" | "submitted_at"
) {
    public MarketFilter {
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 20;
        if (sortBy == null || sortBy.isBlank()) sortBy = "official_rank";
    }
}