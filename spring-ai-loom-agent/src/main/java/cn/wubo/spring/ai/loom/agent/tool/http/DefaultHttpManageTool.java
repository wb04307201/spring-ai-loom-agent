package cn.wubo.spring.ai.loom.agent.tool.http;

/**
 * {@link IHttpManageTool} 的默认实现 —— <b>刻意为空</b>。
 *
 * <p>存在的唯一理由是让 {@code CapabilityService.listAll()} 能枚举到
 * {@code tool_http_manage} 这一组(见 {@link IHttpManageTool} javadoc 的完整说明)。
 * 授权的判定逻辑不在这里,而在 {@code HttpManageGuard}:
 * 它读 {@code IRoleService.getVisibleToolsForUser} 的授权结论。
 *
 * <p>本类不持有任何状态,可被消费方用 {@code @ConditionalOnMissingBean} 整体替换。
 */
public class DefaultHttpManageTool implements IHttpManageTool {
}
