package cn.wubo.spring.ai.loom.agent.tool.http;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.ToolGroup;

/**
 * HTTP 配置<b>写面</b>的授权标记 —— <b>刻意零 {@code @Tool} 方法</b>。
 *
 * <p><b>为什么需要一个没有工具方法的接口</b><br>
 * 写面(profile / system / endpoint 的注册与修改)只走 REST
 * ({@code HttpProfileRouter} / {@code HttpSystemRouter}),由
 * {@code HttpManageGuard} 把关。但 {@code role_tool} 的授权入口只有一条:
 * {@code CapabilityService.listAll()} 遍历 {@code List<IEmbedTool>},从实现类的
 * {@code @ToolGroup} 接口推导 group id —— {@code CapabilityService.listAll()}
 * 之外没有任何途径能声明一个可授权的本地组。
 * <p>
 * 在此之前 {@code tool_http_manage} 只是 {@code HttpManageGuard} 里的一个字符串常量,
 * 带来两个真实缺陷:
 * <ol>
 *   <li>admin 控制台"授权本地工具"永远列不出它 → 授权只能靠手写 SQL;</li>
 *   <li>{@code DefaultRoleService.setRoleTools} 是 {@code DELETE + INSERT} 全量替换,
 *       而 {@code roles.js} 只提交 UI 渲染出的组 —— 控制台保存一次就静默撤销该授权。</li>
 * </ol>
 * 本接口补齐这条链路:它让 {@code tool_http_manage} 出现在 {@code /admin/capabilities}
 * 里,控制台可勾选、保存不丢。零工具方法 ⇒ 不向 LLM 暴露任何能力
 * ({@code DefaultChat} 按 {@code @Tool} 方法注册 callback,这里一个都没有)。
 *
 * <p><b>RBAC</b>:{@code defaultGranted = false} —— 写面能写入含 API 凭据的文件,
 * 必须由 admin 显式授权。
 *
 * <p><b>单一真源</b>:group id 常量 {@link #GROUP} 与 {@code @ToolGroup.value} 必须一致,
 * 由 {@code HttpManageToolGroupMetadataTest} 锁死;{@code HttpManageGuard.GROUP} 直接引用
 * {@link #GROUP},不再各写一份字面量。
 *
 * @see IHttpTool 调用面(有 5 个 {@code @Tool},group = {@code tool_http})
 * @see cn.wubo.spring.ai.loom.agent.web.http.HttpManageGuard 写面守卫
 */
@ToolGroup(value = "http_manage", defaultGranted = false,
            description = "HTTP 配置写面:profile / system / endpoint 的注册与修改"
                        + "(经 REST,含凭据文件写入;不暴露给 LLM,需管理员授权)")
public interface IHttpManageTool extends IEmbedTool {

    /**
     * {@code role_tool.group_name} 中本组的取值,与 {@code @ToolGroup.value} 拼接一致
     * ({@code "tool_" + value})。
     */
    String GROUP = "tool_http_manage";
}
