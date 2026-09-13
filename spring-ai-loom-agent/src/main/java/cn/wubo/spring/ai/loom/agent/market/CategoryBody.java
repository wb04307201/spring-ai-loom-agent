package cn.wubo.spring.ai.loom.agent.market;

/**
 * 设置分类 (setCategory) 请求体。{@code category} 为分类名，{@code null} 或空字符串
 * 表示清除分类。
 */
public record CategoryBody(String category) {
}
