package cn.wubo.spring.ai.loom.agent.testutil;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * M3+ T6.1 — shared test util for {@link RouterFunction} integration tests.
 *
 * <p>Provides {@link #safeRoute} / {@link #route} / {@link #json} helpers used by
 * IT classes that drive router endpoints directly without starting a servlet
 * container. Originally duplicated in
 * {@code cn.wubo.spring.ai.loom.agent.market.MarketAcceptanceIT} and
 * {@code cn.wubo.spring.ai.loom.agent.market.SkillAdminMissingIdReturns404IT};
 * extracted here so future IT classes can reuse without re-implementing the
 * MockHttpServletRequest + ServerRequest wiring.
 *
 * <p>{@link #safeRoute} wraps {@link #route} so that {@code NoSuchElementException}
 * (router didn't match the request) returns {@code null} instead of throwing —
 * caller decides whether to skip downstream assertions on a non-match.
 *
 * <p>{@link #route} parses any {@code ?query=...} string off the path before
 * constructing the mock request, so route patterns that depend on URI matching
 * work correctly even when callers pass query-bearing URLs.
 *
 * @since M3+ T6.1
 */
public final class LoomAgentTestUtil {

    private LoomAgentTestUtil() {
    }

    /**
     * Drive {@code router} with the given HTTP method, path, and optional JSON
     * body. Returns the router's response, or {@code null} if the router did
     * not match the request (treated as a non-fatal skip by the caller).
     *
     * @param router the {@link RouterFunction} to drive
     * @param method HTTP method (GET / POST / PUT / DELETE / ...)
     * @param path   request path; may include {@code ?query=...} which is
     *               stripped into {@code servletRequest.addParameter(...)} entries
     * @param body   optional JSON body; if non-null, sets
     *               {@code Content-Type: application/json} and UTF-8 bytes
     * @return the {@link ServerResponse}, or {@code null} when the router
     *         produced no match
     * @throws Exception on any non-skip exception from the router handler
     */
    public static ServerResponse safeRoute(RouterFunction<ServerResponse> router,
                                           String method,
                                           String path,
                                           String body) throws Exception {
        try {
            return route(router, method, path, body);
        } catch (RuntimeException ex) {
            if (ex.getClass().getSimpleName().equals("NoSuchElementException")) {
                return null;
            }
            throw ex;
        }
    }

    /**
     * Same as {@link #safeRoute} but throws if the router does not match.
     * Useful in tests that require a definite match (e.g. MUST-FIX-1 404
     * verification where the contract is that the route exists and the
     * handler returns 404).
     */
    public static ServerResponse route(RouterFunction<ServerResponse> router,
                                       String method,
                                       String path,
                                       String body) throws Exception {
        String uriOnly = path;
        String query = "";
        int q = path.indexOf('?');
        if (q >= 0) {
            uriOnly = path.substring(0, q);
            query = path.substring(q + 1);
        }
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(method, uriOnly);
        servletRequest.setRequestURI(uriOnly);
        servletRequest.setServletPath(uriOnly);
        servletRequest.setPathInfo(null);
        servletRequest.setContextPath("");
        if (!query.isEmpty()) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0) {
                    servletRequest.addParameter(pair, "");
                } else {
                    servletRequest.addParameter(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
        }
        if (body != null) {
            servletRequest.setContent(body.getBytes(StandardCharsets.UTF_8));
            servletRequest.setContentType(MediaType.APPLICATION_JSON_VALUE);
        }
        ServerRequest request = ServerRequest.create(servletRequest,
                List.of(new MappingJackson2HttpMessageConverter()));
        return router.route(request).orElseThrow().handle(request);
    }

    /**
     * Convenience: serialize a {@link Map} to a JSON string for use as a
     * request body. Replaces the {@code json(Map)} helper previously
     * duplicated in each IT class.
     */
    public static String json(Map<String, ?> m) throws Exception {
        return new ObjectMapper().writeValueAsString(m);
    }
}
