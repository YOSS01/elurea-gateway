package com.elurea.gateway.web;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeGuardFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/internal/orders/1/payment-status",
            "/api/payments/INTERNAL/x",
            "/api/orders/../internal/x",
            "/api/orders/./x",
            "/api/orders/%2e%2e/x",
            "/api/orders/%2E%2E/x",
            "/api/orders/a%2fb",
            "/api/orders/a%5cb"
    })
    void blocksInternalEndpointsAndPathTricks(String path) {
        assertThat(EdgeGuardFilter.isForbiddenPath(MockServerHttpRequest.method(HttpMethod.GET, URI.create("http://gw" + path)).build()))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/orders",
            "/api/products/slug/international-dress",
            "/uploads/products/1f0c.png",
            "/api/payments/webhooks/paypal"
    })
    void letsNormalPathsThrough(String path) {
        assertThat(EdgeGuardFilter.isForbiddenPath(MockServerHttpRequest.method(HttpMethod.GET, URI.create("http://gw" + path)).build()))
                .isFalse();
    }
}
