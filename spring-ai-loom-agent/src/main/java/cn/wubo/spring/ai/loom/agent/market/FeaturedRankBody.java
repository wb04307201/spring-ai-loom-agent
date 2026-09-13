package cn.wubo.spring.ai.loom.agent.market;

/**
 * 设置精选排序 (setFeaturedRank) 请求体。{@code rank} 用于在 admin 列表
 * 内对官方内容做手动排序，值越大越靠前。
 */
public record FeaturedRankBody(int rank) {
}
