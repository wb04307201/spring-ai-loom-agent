package cn.wubo.spring.ai.loom.agent.market;

/**
 * 市场公告 (announcement) 请求体。在 T18 (announcement 路由) 引入时由
 * {@code MarketAnnouncementRepository#upsert} 消费。本类仅声明，T7 不消费。
 */
public record AnnouncementBody(String title, String body) {
}
