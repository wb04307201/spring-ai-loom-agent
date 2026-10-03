package cn.wubo.spring.ai.loom.agent.web.http;

import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.profile.Profile;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * profile 写面 REST。
 *
 * <p><b>为什么写面是 REST 而非 LLM 工具</b>:profile 内含 API 凭据。若经
 * {@code IHttpTool} 写入,凭据会进入对话历史、持久化到 chat memory 并渲染在 UI。
 * 写面必须走带会话鉴权的 HTTP 通道。
 *
 * <p><b>鉴权</b>:{@link HttpManageGuard}({@code tool_http_manage}),与 filter 独立。
 *
 * <p><b>读面脱敏</b>:GET 列表中 {@code auth.token} / {@code auth.password} /
 * {@code auth.value} 一律替换为 {@code "***"} —— 不允许凭据原文出现在响应里。
 *
 * <p>类本身不带 {@code @Configuration} —— 由 {@code HttpManagementConfiguration}
 * 用 {@code @Import} 拉起(它是仓库首个并列第二的 auto-config,见 CLAUDE.md)。
 */
public class HttpProfileRouter {

    /**
     * 4 个 profile REST 端点:
     * <ul>
     *   <li>{@code GET    /spring/ai/loom/api/http/profiles} —— 列表(脱敏)</li>
     *   <li>{@code POST   /spring/ai/loom/api/http/profiles} —— 创建</li>
     *   <li>{@code PUT    /spring/ai/loom/api/http/profiles/{name}} —— 更新</li>
     *   <li>{@code DELETE /spring/ai/loom/api/http/profiles/{name}} —— 删除</li>
     * </ul>
     */
    @Bean
    public RouterFunction<ServerResponse> httpProfileRouter(HttpManageGuard guard, LoomAgentProperties props) {
        RouterFunctions.Builder builder = RouterFunctions.route();

        builder.GET("/spring/ai/loom/api/http/profiles", request -> {
            String username = HttpRouterSupport.currentUsername();
            if (!guard.isAllowed(username)) return ServerResponse.status(403).body(HttpManageGuard.forbidden());
            HttpEngine engine = HttpRouterSupport.engineFor(username, props);
            List<Map<String, Object>> out = new ArrayList<>();
            for (String name : engine.listProfiles()) {
                out.add(HttpRouterSupport.maskProfile(readProfile(engine, name)));
            }
            return ServerResponse.ok().body(out);
        });

        builder.POST("/spring/ai/loom/api/http/profiles", request -> {
            String username = HttpRouterSupport.currentUsername();
            if (!guard.isAllowed(username)) return ServerResponse.status(403).body(HttpManageGuard.forbidden());
            Profile p = request.body(Profile.class);
            if (p == null || p.getName() == null || p.getName().isBlank()) {
                return ServerResponse.badRequest().body(Map.of("error", "name 必填"));
            }
            return ServerResponse.ok().body(HttpRouterSupport.engineFor(username, props).addProfile(p));
        });

        builder.PUT("/spring/ai/loom/api/http/profiles/{name}", request -> {
            String username = HttpRouterSupport.currentUsername();
            if (!guard.isAllowed(username)) return ServerResponse.status(403).body(HttpManageGuard.forbidden());
            String name = request.pathVariable("name");
            Profile patch = request.body(Profile.class);
            return ServerResponse.ok().body(HttpRouterSupport.engineFor(username, props).updateProfile(name, patch));
        });

        builder.DELETE("/spring/ai/loom/api/http/profiles/{name}", request -> {
            String username = HttpRouterSupport.currentUsername();
            if (!guard.isAllowed(username)) return ServerResponse.status(403).body(HttpManageGuard.forbidden());
            String name = request.pathVariable("name");
            return ServerResponse.ok().body(HttpRouterSupport.engineFor(username, props).removeProfile(name));
        });

        return builder.build();
    }

    /** 读 profile:对单个 profile 不存在的情况容忍(留空 name 时 list 会自然跳过)。 */
    private static Profile readProfile(HttpEngine engine, String name) {
        try {
            return engine.getProfile(name);
        } catch (RuntimeException e) {
            Profile empty = new Profile();
            empty.setName(name);
            return empty;
        }
    }
}
