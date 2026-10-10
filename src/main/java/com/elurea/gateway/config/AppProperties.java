package com.elurea.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Typed view of the "app.*" settings in application.properties.
 */
@ConfigurationProperties("app")
public record AppProperties(
        @DefaultValue Services services,
        @DefaultValue Timeouts timeouts,
        @DefaultValue RateLimit rateLimit
) {

    public record Services(
            @DefaultValue("http://localhost:8081") String userUrl,
            @DefaultValue("http://localhost:8082") String productUrl,
            @DefaultValue("http://localhost:8083") String orderUrl,
            @DefaultValue("http://localhost:8084") String paymentUrl
    ) {
    }

    public record Timeouts(
            @DefaultValue("2s") Duration connect,
            @DefaultValue("15s") Duration response,
            @DefaultValue("45s") Duration paymentResponse
    ) {
    }

    /**
     * @param authPerMinute     login/register attempts allowed per client IP and minute
     * @param apiPerMinute      other API calls allowed per client IP and minute
     * @param trustForwardedFor use the X-Forwarded-For header as client IP (only behind a trusted proxy)
     */
    public record RateLimit(
            @DefaultValue("10") int authPerMinute,
            @DefaultValue("300") int apiPerMinute,
            @DefaultValue("false") boolean trustForwardedFor
    ) {
    }
}
