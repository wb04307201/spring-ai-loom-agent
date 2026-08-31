package cn.wubo.spring.ai.loom.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明 {@link IEmbedTool} 实现类 / 接口所属的 tool group。
 * <p>
 * 用于 {@code CapabilityService} 把本地工具归类到 RBAC 可授权的 capability group。
 * 注解值是"纯名"(如 {@code "file"}),DB 存储 / capability id 拼接时由
 * {@code CapabilityService} 统一加 {@code "tool_"} 前缀以与 MCP 的 capability id 命名空间区分。
 *
 * <p><b>放置位置：</b>接口（{@code IFileTool} 等）,不放在实现类。理由：
 * <ul>
 *   <li>capability 身份属于抽象层,实现替换不影响 capability 体系</li>
 *   <li>Spring 的 {@code @ConditionalOnMissingBean} 接口 → 任意 {@code IFileTool} 实现都会被扫到,
 *       不会被替换实现丢掉 group 归属</li>
 * </ul>
 *
 * <p><b>{@code defaultGranted}</b>：若为 {@code true},该 group 被视为"平台默认能力",<b>不受
 * role_tool RBAC 控制</b> —— {@code CapabilityService.visibleToolGroupsFor(username)} 会
 * 把它们 union 进结果,保证任何登录用户都能调用。语义和适用对象：
 * <ul>
 *   <li>per-user 隔离的安全工具（如 IScheduleTool 命名空间在用户/conversation 内）</li>
 *   <li>只读或低副作用工具（如 ITimeTool 仅返回时间）</li>
 *   <li>下游数据已独立受控的工具（如 IKnowledgeTool — KB 列表本身受 role_knowledge 控制）</li>
 * </ul>
 * <b>不适用：</b>涉及文件系统写、git push、构建执行、Docker 容器启动等任意可执行副作用的
 * 工具必须保持 {@code defaultGranted = false},走 RBAC 流程。
 *
 * <p>单一真源：本注解。DB 端的 {@code role_tool} 历史行会被 V2.4 迁移清理,
 * admin UI 不展示 universal 工具的可增删入口,只在元数据层硬约束。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolGroup {
    String value();
    /**
     * 一行人类可读描述,在 admin 角色授权 UI 渲染。缺省时 CapabilityService
     * 会 fallback 到 "N 个工具"。
     */
    String description() default "";
    /**
     * 是否为"默认授予"工具 — {@code true} 时该 group 对所有登录用户可见,不参与 RBAC 控制。
     * 详见类 javadoc。默认 {@code false}（保持 RBAC 语义）。
     */
    boolean defaultGranted() default false;
}