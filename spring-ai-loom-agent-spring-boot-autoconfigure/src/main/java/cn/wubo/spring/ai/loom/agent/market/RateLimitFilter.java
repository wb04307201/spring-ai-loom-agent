package cn.wubo.spring.ai.loom.agent.market;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M3+ T4.2 — token-bucket rate limiter for the market hot-path endpoints.
 *
 * <p>Per cleanup spec §9 ADR-T08: <b>in-memory</b> bucket (Bucket4j), NOT Redis
 * distributed; explicitly does not support horizontal scaling. Per-user
 * buckets keyed by remote IP (proxy-aware via {@code X-Forwarded-For}).
 *
 * <p>Lives in the autoconfigure module because it depends on Bucket4j,
 * which is an optional dep on {@code spring-ai-loom-agent-spring-boot-autoconfigure}.
 * Core ({@code spring-ai-loom-agent}) does not pull Bucket4j.
 *
 * <p>Protected URL patterns (set by the servlet registration):
 * <ul>
 *   <li>{@code /spring/ai/loom/market-skills/{id}/pull}</li>
 *   <li>{@code /spring/ai/loom/market-knowledge/{id}/pull}</li>
 *   <li>{@code /spring/ai/loom/market-knowledge/{id}/access}</li>
 *   <li>{@code /spring/ai/loom/market-skills/{id}/reviews} (POST)</li>
 *   <li>{@code /spring/ai/loom/market-knowledge/{id}/reviews} (POST)</li>
 * </ul>
 *
 * <p>Limit defaults to 60 requests / minute / IP. The exact path patterns
 * are configured at registration time so this filter stays generic.
 *
 * <p>Response on rate limit: HTTP 429 + {@code Retry-After} header (seconds
 * until bucket refills) + a minimal JSON body {@code {"error":"rate_limited"}}.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Default budget: 60 requests per minute per remote IP. */
    private static final long DEFAULT_CAPACITY = 60;
    private static final Duration DEFAULT_REFILL_PERIOD = Duration.ofMinutes(1);

    /** URL path prefixes this filter gates. */
    private final List<String> protectedPrefixes;
    private final long capacity;
    private final Duration refillPeriod;

    /** Per-IP token buckets. */
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(List<String> protectedPrefixes) {
        this(protectedPrefixes, DEFAULT_CAPACITY, DEFAULT_REFILL_PERIOD);
    }

    public RateLimitFilter(List<String> protectedPrefixes, long capacity, Duration refillPeriod) {
        this.protectedPrefixes = List.copyOf(protectedPrefixes);
        this.capacity = capacity;
        this.refillPeriod = refillPeriod;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) return true;
        for (String prefix : protectedPrefixes) {
            if (path.startsWith(prefix)) return false;
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String key = clientKey(request);
        Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
        } else {
            long retryAfterSec = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
            log.debug("rate limit triggered for {} on {}", key, request.getRequestURI());
            response.setStatus(429); // HTTP 429 Too Many Requests —
// HttpServletResponse.SC_TOO_MANY_REQUESTS is not in the Servlet 5 API
// surface; the literal is the documented status code per RFC 6585.
            response.setHeader("Retry-After", String.valueOf(retryAfterSec));
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"rate_limited\",\"retry_after_seconds\":" + retryAfterSec + "}");
        }
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillIntervally(capacity, refillPeriod)
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * Proxy-aware client key: prefer {@code X-Forwarded-For} first hop,
     * fall back to {@code remoteAddr}. Returns {@code "unknown"} when
     * the request carries no resolvable IP (shouldn't happen for HTTP).
     */
    private static String clientKey(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String addr = request.getRemoteAddr();
        return addr == null || addr.isBlank() ? "unknown" : addr;
    }
}
