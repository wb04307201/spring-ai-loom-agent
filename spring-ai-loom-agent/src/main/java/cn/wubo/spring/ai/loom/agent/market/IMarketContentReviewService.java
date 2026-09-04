package cn.wubo.spring.ai.loom.agent.market;

public interface IMarketContentReviewService {
    Page<ReviewRow> listReviews(Long marketId, int page, int size);
    ReviewRow submit(Long marketId, String username, ReviewSubmitRequest req);
    ReviewRow update(Long marketId, String username, ReviewUpdateRequest req);
    void deleteAsAdmin(Long marketId, String username);
    RatingAggregate aggregate(Long marketId);
}
