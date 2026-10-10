package com.elurea.gateway.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request an X-Request-Id (kept from the client if well-formed, generated otherwise),
 * forwards it to the service, returns it in the response and writes one access-log line per request.
 * Quote it when reporting a problem: it links the gateway log to the service logs.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements WebFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTR = RequestIdFilter.class.getName() + ".requestId";

    /** Anything else (too long, odd characters) could be used to inject content into logs, so it is replaced. */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
        long start = System.nanoTime();

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER, requestId))
                .build();
        ServerWebExchange mutated = exchange.mutate().request(request).build();
        mutated.getAttributes().put(REQUEST_ID_ATTR, requestId);
        mutated.getResponse().getHeaders().set(HEADER, requestId);

        return chain.filter(mutated).doFinally(signal -> {
            HttpStatusCode status = mutated.getResponse().getStatusCode();
            log.info("{} {} -> {} in {} ms [requestId={}, client={}]",
                    request.getMethod(), request.getPath().value(),
                    status == null ? "-" : status.value(),
                    (System.nanoTime() - start) / 1_000_000,
                    requestId, ClientAddresses.remoteAddress(request));
        });
    }
}
