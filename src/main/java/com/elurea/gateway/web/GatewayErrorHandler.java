package com.elurea.gateway.web;

import io.netty.channel.ConnectTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;

/**
 * Turns failures inside the gateway into problem-detail JSON:
 * unknown route 404, service unreachable 503, service too slow 504, anything else 500.
 * Error responses produced by the services themselves are passed through untouched.
 */
@Slf4j
@Component
@Order(-2) // before Spring Boot's default handler
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private final ProblemResponses problems;

    public GatewayErrorHandler(ProblemResponses problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        String path = exchange.getRequest().getPath().value();

        if (isConnectionFailure(ex)) {
            log.warn("Service unreachable for {}: {}", path, rootMessage(ex));
            return problems.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "The service is temporarily unavailable, please try again in a moment");
        }
        if (ex instanceof ResponseStatusException statusException) {
            HttpStatusCode code = statusException.getStatusCode();
            HttpStatus status = HttpStatus.resolve(code.value());
            if (status == HttpStatus.NOT_FOUND) {
                return problems.write(exchange, status, "No API route matches this path");
            }
            if (status == HttpStatus.GATEWAY_TIMEOUT) {
                log.warn("Service too slow for {}: {}", path, statusException.getReason());
                return problems.write(exchange, status, "The service took too long to respond");
            }
            if (status != null && status.is4xxClientError()) {
                return problems.write(exchange, status, statusException.getReason() != null
                        ? statusException.getReason() : status.getReasonPhrase());
            }
            if (status == HttpStatus.SERVICE_UNAVAILABLE) {
                return problems.write(exchange, status, "The service is temporarily unavailable, please try again in a moment");
            }
        }
        log.error("Unexpected gateway error for {}", path, ex);
        return problems.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    private static boolean isConnectionFailure(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof ConnectTimeoutException
                    || cause instanceof UnknownHostException) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
