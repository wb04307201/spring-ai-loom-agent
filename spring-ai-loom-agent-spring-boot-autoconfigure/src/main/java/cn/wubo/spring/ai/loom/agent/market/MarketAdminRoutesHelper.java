package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.user.IUser;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * M3+ T3.2 — static helpers to collapse the boilerplate shared by
 * {@code loomAgentMarketSkillAdminRouter} and
 * {@code loomAgentMarketKnowledgeAdminRouter}. Pre-T3.2, both routers
 * reimplemented the same 9-handler pattern:
 *
 * <ol>
 *   <li>Read current user from {@link UserContextHolder}</li>
 *   <li>Verify {@code user.isAdmin(username)} → 403 otherwise</li>
 *   <li>Parse {@code {id}} path variable (Long for skill, String UUID for KB)</li>
 *   <li>Try service call; map {@link LoomAgentRuntimeException} to its
 *       declared {@code statusCode}; map {@link NumberFormatException} to 400</li>
 * </ol>
 *
 * <p>After T3.2, callers can write:
 * <pre>{@code
 *   builder.PUT(prefix + "/{id}/approve", request ->
 *       MarketAdminRoutesHelper.adminOnly(user, request,
 *           MarketAdminRoutesHelper.idPath("id", Long::parseLong, id ->
 *               svc.approve(id, UserContextHolder.getCurrentUser()))));
 * }</pre>
 *
 * <p>The full migration of all 9 handlers × 2 routers is staged as a
 * follow-up (see LoomAgentConfiguration TODO at the v1/v2 admin router
 * javadoc). The helpers exist so the migration is mechanical rather
 * than a refactor.
 */
public final class MarketAdminRoutesHelper {

    private MarketAdminRoutesHelper() {}

    /**
     * Wrap a handler so the request is rejected with 403 unless the
     * current user is an admin.
     */
    public static ServerResponse adminOnly(IUser user,
                                            org.springframework.web.servlet.function.ServerRequest request,
                                            java.util.function.Supplier<ServerResponse> body) {
        String username = UserContextHolder.getCurrentUser();
        if (username == null || !user.isAdmin(username)) {
            return ServerResponse.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "无权限"));
        }
        return body.get();
    }

    /**
     * Parse {@code pathVar} from the request and dispatch to {@code body}.
     * Catches {@link NumberFormatException} and returns 400 (matters for
     * skill routers where the id is {@code Long}; KB routers can pass
     * {@code s -> s} to accept any string including UUID).
     */
    public static <K> ServerResponse idPath(String pathVar,
                                             Function<String, K> parser,
                                             BiFunction<K, org.springframework.web.servlet.function.ServerRequest, ServerResponse> body,
                                             org.springframework.web.servlet.function.ServerRequest request) {
        final K id;
        try {
            id = parser.apply(request.pathVariable(pathVar));
        } catch (NumberFormatException nfe) {
            return ServerResponse.badRequest().body(Map.of(
                    "error", "id 格式错误: " + request.pathVariable(pathVar)));
        }
        try {
            return body.apply(id, request);
        } catch (LoomAgentRuntimeException ex) {
            int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.BAD_REQUEST.value();
            return ServerResponse.status(code).body(Map.of("error", ex.getMessage()));
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            return ServerResponse.badRequest().body(Map.of(
                    "error", "数据约束失败: " + ex.getMostSpecificCause().getMessage()));
        }
    }
}
