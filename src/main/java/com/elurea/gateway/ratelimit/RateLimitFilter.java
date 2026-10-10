package com.elurea.gateway.ratelimit;

import com.elurea.gateway.config.AppProperties;
import com.elurea.gateway.web.ClientAddresses;
import com.elurea.gateway.web.ProblemResponses;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Per-client-IP rate limits, answered with 429 and a Retry-After header:
 * <ul>
 *     <li>login and register: a few attempts per minute, which makes password guessing impractical;</li>
 *     <li>every other /api call: a generous flood guard.</li>
 * </ul>
 * Payment provider webhooks and images are not limited. Counters live in memory: with several gateway
 * instances each one counts separately (use a shared store such as Redis at that point).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RateLimitFilter implements WebFilter {

    private static final Set<String> AUTH_PATHS = Set.of("/api/auth/login", "/api/auth/register");
    private static final long IDLE_EVICTION_NANOS = TimeUnit.MINUTES.toNanos(10);

    private final AppProperties.RateLimit config;
    private final ProblemResponses problems;
    private final LongSupplier clock;
    private final Map<String, TokenBucket> authBuckets = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> apiBuckets = new ConcurrentHashMap<>();

    @Autowired
    public RateLimitFilter(AppProperties properties, ProblemResponses problems) {
        this(properties, problems, System::nanoTime);
    }

    /** For tests: a controllable clock. */
    RateLimitFilter(AppProperties properties, ProblemResponses problems, LongSupplier clock) {
        this.config = properties.rateLimit();
        this.problems = problems;
        this.clock = clock;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        if (!path.startsWith("/api/") || path.startsWith("/api/payments/webhooks/")) {
            return chain.filter(exchange);
        }
        String client = ClientAddresses.clientIp(request, config.trustForwardedFor());
        long now = clock.getAsLong();

        if (request.getMethod() == HttpMethod.POST && AUTH_PATHS.contains(path)) {
            long retryAfter = take(authBuckets, client, config.authPerMinute(), now);
            if (retryAfter > 0) {
                return reject(exchange, retryAfter, "Too many login attempts, please wait %d seconds".formatted(retryAfter));
            }
        }
        long retryAfter = take(apiBuckets, client, config.apiPerMinute(), now);
        if (retryAfter > 0) {
            return reject(exchange, retryAfter, "Too many requests, please slow down");
        }
        return chain.filter(exchange);
    }

    /** Forgets clients that have been quiet for a while, so memory does not grow with every IP ever seen. */
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    void evictIdleClients() {
        long now = clock.getAsLong();
        authBuckets.values().removeIf(bucket -> now - bucket.lastUsedNanos() > IDLE_EVICTION_NANOS);
        apiBuckets.values().removeIf(bucket -> now - bucket.lastUsedNanos() > IDLE_EVICTION_NANOS);
    }

    int trackedClients() {
        return authBuckets.size() + apiBuckets.size();
    }

    private static long take(Map<String, TokenBucket> buckets, String client, int perMinute, long now) {
        return buckets.computeIfAbsent(client, key -> new TokenBucket(perMinute, now)).tryConsume(now);
    }

    private Mono<Void> reject(ServerWebExchange exchange, long retryAfterSeconds, String detail) {
        exchange.getResponse().getHeaders().set("Retry-After", Long.toString(retryAfterSeconds));
        return problems.write(exchange, HttpStatus.TOO_MANY_REQUESTS, detail);
    }
}
