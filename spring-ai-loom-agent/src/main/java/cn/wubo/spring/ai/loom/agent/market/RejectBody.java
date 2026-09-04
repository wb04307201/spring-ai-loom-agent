package cn.wubo.spring.ai.loom.agent.market;

/**
 * 拒绝 (reject) 请求体。{@code comment} 必填，由
 * {@link AbstractMarketAdminService#reject(Long, String, String)} 校验。
 */
public record RejectBody(String comment) {
}
