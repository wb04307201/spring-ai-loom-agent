package cn.wubo.spring.ai.loom.agent.web.http;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * system / endpoint / OpenAPI 刷新的写面 REST。鉴权同 {@link HttpProfileRouter}。
 *
 * <p><b>8 个端点</b>:
 * <ul>
 *   <li>{@code GET    /spring/ai/loom/api/http/systems}</li>
 *   <li>{@code POST   /spring/ai/loom/api/http/systems}</li>
 *   <li>{@code PUT    /spring/ai/loom/api/http/systems/{name}}</li>
 *   <li>{@code DELETE /spring/ai/loom/api/http/systems/{name}}</li>
 *   <li>{@code GET    /spring/ai/loom/api/http/systems/{name}/endpoints}</li>
 *   <li>{@code POST   /spring/ai/loom/api/http/systems/{name}/endpoints}</li>
 *   <li>{@code PUT    /spring/ai/loom/api/http/systems/{name}/endpoints/{method}/{path}}</li>
 *   <li>{@code DELETE /spring/ai/loom/api/http/systems/{name}/endpoints/{method}/{path}}</li>
 *   <li>{@code POST   /spring/ai/loom/api/http/systems/{name}/refresh}</li>
 * </ul>
 *
 * <p>类不带 {@code @Configuration} —— 由 {@code HttpManagementConfiguration} 用
 * {@code @Import} 拉起。
 */
public class HttpSystemRouter {

    @Bean
    public RouterFunction<ServerResponse> httpSystemRouter(HttpManageGuard guard, LoomAgentProperties props) {
        RouterFunctions.Builder builder = RouterFunctions.route();

        builder.GET("/spring/ai/loom/api/http/systems", request -> {
            String username = HttpRouterSupport.currentUsername();
            if (!guard.isAllowed(username)) return ServerResponse.status(403).body(HttpManageGuard.forbidden());
            return ServerResponse.ok().body(HttpRouterSupport.engineFor(username, props).listSystems());
        });

        builder.POST("/spring/ai/loom/api/http/systems", request -> {
            String username = HttpRouterSupport.currentUsername();
            if (!guard.isAllowed(username)) return ServerResponse.status(403).body(HttpManageGuard.forbidden());
            cn.wubo.loom.http.core.system.System s = request.body(cn.wubo.loom.http.core.system.System.class);
            if (s == null || s.getName() == null || s.getName().isBlank()) {
                return ServerResponse.badRequest().body(Map.of("error", "name 必填"));
            }
            return ServerResponse.ok().body(HttpRouterSupport.engineFor(username, props).registerSystem(s));
        });

        builder.PUT("/spring/ai/loom/api/http/systems/{name}", request -> {
            String username = HttpRouterSupport.currentUsername();
            if (!guard.isAllowed(username)) return ServerResponse.status(403).body(HttpManageGuard.forbidden());
            String name = request.pathVariable("name");
            cn.wubo.loom.http.core.system.System patch = request.body(cn.wubo.loom.http.core.system.System.class);
            return ServerResponse.ok().body(HttpRouterSupport.engineFor(username, props).updateSystem(name, patch));
        });

        builder.DELETE("/spring/ai/loom/api/http/systems/{name}", request -> {
            String username = HttpRouterSupport.currentUsername();
            if (!guard.isAllowed(username)) return ServerResponse.status(403).body(HttpManageGuard.forbidden());
            String name = request.pathVariable("name");
            // REST 写面删除 system 时不主动清 OpenAPI 缓存 ——
            // 与 http-mcp ProfileTools.removeSystem 默认行为一致(false)
            return ServerResponse.ok().body(HttpRouterSupport.engineFor(username, props).removeSystem(name, false));
        });

        builder.POST("/spring/ai/loom/api/http/systems/{name}/refresh", request -> {
            String username = HttpRouterSupport.currentUsername();
            if (!guard.isAllowed(username)) return ServerResponse.status(403).body(HttpManageGuard.forbidden());
            String name = request.pathVariable("name");
            return ServerResponse.ok().body(HttpRouterSupport.engineFor(username, props).refreshSystem(name));
        });

        // ---- endpoints ----

        builder.GET("/spring/ai/loom/api/http/systems/{name}/endpoints", request -> {
            String username = HttpRouterSupport.currentUsername();
            if (!guard.isAllowed(username)) return ServerResponse.status(403).body(HttpManageGuard.forbidden());
            String name = request.pathVariable("name");
            return ServerResponse.ok().body(HttpRouterSupport.engineFor(username, props).listEndpoints(name, null, null));
        });

        builder.POST("/spring/ai/loom/api/http/systems/{name}/endpoints", request -> {
            String username = HttpRouterSupport.currentUsername();
            if (!guard.isAllowed(username)) return ServerResponse.status(403).body(HttpManageGuard.forbidden());
            String name = request.pathVariable("name");
            Map<String, Object> body = request.body(Map.class);
            if (body == null) return ServerResponse.badRequest().body(Map.of("error", "body 必填"));
            Map<String, Object> args = extractEndpointArgs(name, body);
            return ServerResponse.ok().body(
                    HttpRouterSupport.engineFor(username, props).addEndpoint(
                            name,
                            (String) args.get("method"),
                            (String) args.get("path"),
                            asMapList(args.get("parameters")),
                            asMap(args.get("requestBody")),
                            asMap(args.get("responses")),
                            (String) args.get("summary"),
                            (String) args.get("description"),
                            asStringList(args.get("tags"))));
        });

        builder.PUT("/spring/ai/loom/api/http/systems/{name}/endpoints/{method}/{path:.+}", request -> {
            String username = HttpRouterSupport.currentUsername();
            if (!guard.isAllowed(username)) return ServerResponse.status(403).body(HttpManageGuard.forbidden());
            String name = request.pathVariable("name");
            String method = request.pathVariable("method");
            String path = request.pathVariable("path");
            Map<String, Object> patch = request.body(Map.class);
            if (patch == null) patch = new LinkedHashMap<>();
            return ServerResponse.ok().body(
                    HttpRouterSupport.engineFor(username, props).updateEndpoint(name, method, path, patch));
        });

        builder.DELETE("/spring/ai/loom/api/http/systems/{name}/endpoints/{method}/{path:.+}", request -> {
            String username = HttpRouterSupport.currentUsername();
            if (!guard.isAllowed(username)) return ServerResponse.status(403).body(HttpManageGuard.forbidden());
            String name = request.pathVariable("name");
            String method = request.pathVariable("method");
            String path = request.pathVariable("path");
            return ServerResponse.ok().body(
                    HttpRouterSupport.engineFor(username, props).removeEndpoint(name, method, path));
        });

        return builder.build();
    }

    /** 从 POST body 提取 endpoint 字段。{@code method} / {@code path} 必填。 */
    private static Map<String, Object> extractEndpointArgs(String name, Map<String, Object> body) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("method", body.get("method"));
        args.put("path", body.get("path"));
        args.put("parameters", body.get("parameters"));
        args.put("requestBody", body.get("requestBody"));
        args.put("responses", body.get("responses"));
        args.put("summary", body.get("summary"));
        args.put("description", body.get("description"));
        args.put("tags", body.get("tags"));
        return args;
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Map<String, Object>> asMapList(Object o) {
        return o instanceof java.util.List<?> l ? (java.util.List<Map<String, Object>>) l : null;
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> asStringList(Object o) {
        return o instanceof java.util.List<?> l ? (java.util.List<String>) l : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
