package com.elurea.gateway.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Set;

/**
 * Edge hygiene, before anything is routed:
 * <ul>
 *     <li>service-to-service endpoints (/internal/**) and path tricks that could reach them once a service
 *     normalises the path ("..", encoded dots or slashes, backslashes) are answered with 404;</li>
 *     <li>headers that only trusted back-end services may send are removed from client requests.</li>
 * </ul>
 * The services check the internal API key themselves too; this is the second line of defence.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class EdgeGuardFilter implements WebFilter {

    /** Sent between services only. A client never has a reason to send it. */
    static final Set<String> INTERNAL_HEADERS = Set.of("X-Internal-Api-Key");

    private final ProblemResponses problems;

    public EdgeGuardFilter(ProblemResponses problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (isForbiddenPath(exchange.getRequest())) {
            return problems.write(exchange, HttpStatus.NOT_FOUND, "No API route matches this path");
        }
        ServerHttpRequest sanitized = exchange.getRequest().mutate()
                .headers(headers -> INTERNAL_HEADERS.forEach(headers::remove))
                .build();
        return chain.filter(exchange.mutate().request(sanitized).build());
    }

    static boolean isForbiddenPath(ServerHttpRequest request) {
        String raw = request.getURI().getRawPath();
        if (raw == null) {
            return false;
        }
        String lowerRaw = raw.toLowerCase(Locale.ROOT);
        if (lowerRaw.contains("%2e") || lowerRaw.contains("%2f") || lowerRaw.contains("%5c") || raw.contains("\\")) {
            return true;
        }
        for (String segment : raw.split("/")) {
            String lower = segment.toLowerCase(Locale.ROOT);
            if (lower.equals("..") || lower.equals(".") || lower.equals("internal")) {
                return true;
            }
        }
        return false;
    }
}
