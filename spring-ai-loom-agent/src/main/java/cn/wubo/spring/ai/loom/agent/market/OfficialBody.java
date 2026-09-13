package cn.wubo.spring.ai.loom.agent.market;

/**
 * 设置官方 (setOfficial) 请求体。{@code isOfficial=true} 表示标记为官方推荐。
 */
public record OfficialBody(boolean isOfficial) {
}
