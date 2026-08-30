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
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolGroup {
    String value();
}