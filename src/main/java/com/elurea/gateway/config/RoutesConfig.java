package com.elurea.gateway.config;

import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory.RetryConfig;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;

import static org.springframework.cloud.gateway.support.RouteMetadataUtils.CONNECT_TIMEOUT_ATTR;
import static org.springframework.cloud.gateway.support.RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR;

/**
 * Public routes. Only the paths listed here are reachable through the gateway: anything else,
 * in particular the services' /internal/** endpoints, gets a 404.
 * <pre>
 * /api/auth, users, addresses, testimonials, subscribers  -> user service
 * /api/categories, products, product-variants, product-galleries -> product service
 * /api/orders   -> order service
 * /api/payments -> payment service (including the PayPal webhook)
 * /uploads/avatars/**  -> user service    (images, GET/HEAD only)
 * /uploads/products/** -> product service (images, GET/HEAD only)
 * </pre>
 */
@Configuration
public class RoutesConfig {

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder, AppProperties properties) {
        AppProperties.Services services = properties.services();
        AppProperties.Timeouts timeouts = properties.timeouts();
        long connect = timeouts.connect().toMillis();
        long response = timeouts.response().toMillis();

        return builder.routes()
                .route("user-service", route -> route
                        .path("/api/auth/**", "/api/users/**", "/api/addresses/**",
                                "/api/testimonials/**", "/api/subscribers/**")
                        .filters(RoutesConfig::retryReadsOnConnectionFailure)
                        .metadata(CONNECT_TIMEOUT_ATTR, (int) connect)
                        .metadata(RESPONSE_TIMEOUT_ATTR, response)
                        .uri(services.userUrl()))
                .route("user-uploads", route -> route
                        .path("/uploads/avatars/**").and().method(HttpMethod.GET, HttpMethod.HEAD)
                        .filters(RoutesConfig::retryReadsOnConnectionFailure)
                        .metadata(CONNECT_TIMEOUT_ATTR, (int) connect)
                        .metadata(RESPONSE_TIMEOUT_ATTR, response)
                        .uri(services.userUrl()))
                .route("product-service", route -> route
                        .path("/api/categories/**", "/api/products/**",
                                "/api/product-variants/**", "/api/product-galleries/**")
                        .filters(RoutesConfig::retryReadsOnConnectionFailure)
                        .metadata(CONNECT_TIMEOUT_ATTR, (int) connect)
                        .metadata(RESPONSE_TIMEOUT_ATTR, response)
                        .uri(services.productUrl()))
                .route("product-uploads", route -> route
                        .path("/uploads/products/**").and().method(HttpMethod.GET, HttpMethod.HEAD)
                        .filters(RoutesConfig::retryReadsOnConnectionFailure)
                        .metadata(CONNECT_TIMEOUT_ATTR, (int) connect)
                        .metadata(RESPONSE_TIMEOUT_ATTR, response)
                        .uri(services.productUrl()))
                .route("order-service", route -> route
                        .path("/api/orders/**")
                        .filters(RoutesConfig::retryReadsOnConnectionFailure)
                        .metadata(CONNECT_TIMEOUT_ATTR, (int) connect)
                        .metadata(RESPONSE_TIMEOUT_ATTR, response)
                        .uri(services.orderUrl()))
                .route("payment-service", route -> route
                        .path("/api/payments/**")
                        .filters(RoutesConfig::retryReadsOnConnectionFailure)
                        .metadata(CONNECT_TIMEOUT_ATTR, (int) connect)
                        .metadata(RESPONSE_TIMEOUT_ATTR, timeouts.paymentResponse().toMillis())
                        .uri(services.paymentUrl()))
                .build();
    }

    /**
     * A service restarting should not surface as an error for simple reads: GET requests are retried twice
     * when the connection itself fails. Writes are never retried by the gateway (they may not be idempotent),
     * and neither are error responses from the services.
     */
    private static GatewayFilterSpec retryReadsOnConnectionFailure(GatewayFilterSpec filters) {
        return filters.retry(retry -> configureRetry(retry));
    }

    @SuppressWarnings("unchecked")
    private static void configureRetry(RetryConfig retry) {
        retry.setRetries(2)
                .setMethods(HttpMethod.GET)
                .setSeries()
                .setStatuses()
                .setExceptions(ConnectException.class, IOException.class)
                .setBackoff(Duration.ofMillis(100), Duration.ofMillis(500), 2, true);
    }
}
