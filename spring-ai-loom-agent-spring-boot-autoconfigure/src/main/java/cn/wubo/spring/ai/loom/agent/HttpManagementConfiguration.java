package cn.wubo.spring.ai.loom.agent;

import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;
import cn.wubo.spring.ai.loom.agent.web.http.HttpManageGuard;
import cn.wubo.spring.ai.loom.agent.web.http.HttpProfileRouter;
import cn.wubo.spring.ai.loom.agent.web.http.HttpSystemRouter;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * HTTP 写面装配 —— {@link HttpManageGuard} + REST 路由({@link HttpProfileRouter}
 * + {@link HttpSystemRouter})。
 *
 * <p><b>为什么是独立 auto-config,而不是内联在 {@link LoomAgentConfiguration}</b>:
 * 后者已 4857 行,再塞 13 个 HTTP REST 端点不可维护;独立 auto-config 可单独
 * 测试、单独审计,失败也能精准定位。
 *
 * <p><b>顺序保证</b>:{@link AutoConfigureAfter}({@link LoomAgentConfiguration}.class)
 * 确保 {@link IRoleService} bean 先于 {@link HttpManageGuard} 创建 —— 后者依赖前者。
 *
 * <p><b>关闭开关</b>:{@code spring.ai.loom.agent.http.enabled=false} 完全跳过
 * 整段装配(默认 true,与 {@code LoomAgentProperties.HttpProperty.enabled} 同步语义)。
 *
 * <p><b>两个 router 的拉起</b>:用 {@link Import} 显式登记,避免它们因不在
 * autoconfigure 模块的 component-scan 包下而被遗漏 —— 仓库 router 类此前都
 * 是 {@code LoomAgentConfiguration} 的内联 {@code @Bean},无独立类先例。
 */
@AutoConfiguration(after = LoomAgentConfiguration.class)
@ConditionalOnProperty(prefix = "spring.ai.loom.agent.http", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Import({HttpProfileRouter.class, HttpSystemRouter.class})
public class HttpManagementConfiguration {

    @ConditionalOnMissingBean
    @Bean
    public HttpManageGuard httpManageGuard(IRoleService roleService) {
        return new HttpManageGuard(roleService);
    }
}
