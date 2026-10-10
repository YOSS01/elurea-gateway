package com.elurea.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.net.URI;
import java.util.List;

/**
 * Runs the real gateway in front of four stub services that answer with their own name and
 * the headers they received.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rate-limit.auth-per-minute=3")
class GatewayRoutingTest {

    private static final DisposableServer USER = stub("user");
    private static final DisposableServer PRODUCT = stub("product");
    private static final DisposableServer ORDER = stub("order");
    private static final DisposableServer PAYMENT = stub("payment");

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        registry.add("app.services.user-url", () -> "http://localhost:" + USER.port());
        registry.add("app.services.product-url", () -> "http://localhost:" + PRODUCT.port());
        registry.add("app.services.order-url", () -> "http://localhost:" + ORDER.port());
        registry.add("app.services.payment-url", () -> "http://localhost:" + PAYMENT.port());
    }

    @AfterAll
    static void stopStubs() {
        List.of(USER, PRODUCT, ORDER, PAYMENT).forEach(DisposableServer::disposeNow);
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void eachApiPrefixReachesItsService() {
        expectService("/api/auth/me", "user");
        expectService("/api/users", "user");
        expectService("/api/addresses/123", "user");
        expectService("/api/testimonials", "user");
        expectService("/api/subscribers", "user");
        expectService("/api/categories", "product");
        expectService("/api/products/slug/linen-dress", "product");
        expectService("/api/product-variants?productId=1", "product");
        expectService("/api/product-galleries?productId=1", "product");
        expectService("/api/orders", "order");
        expectService("/api/payments/abc", "payment");
    }

    @Test
    void uploadedImagesGoToTheServiceThatStoresThem() {
        expectService("/uploads/avatars/a.png", "user");
        expectService("/uploads/products/p.png", "product");
        client.delete().uri("/uploads/products/p.png").exchange().expectStatus().isNotFound();
    }

    @Test
    void paypalWebhookIsReachable() {
        client.post().uri("/api/payments/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("payment");
    }

    @Test
    void internalEndpointsAndPathTricksAreNeverRouted() {
        for (String path : List.of("/internal/payments/orders/1/refund",
                "/api/orders/internal/x",
                "/api/orders/%2e%2e/%2e%2e/internal/orders/1/payment-status",
                "/api/orders/..%2f..%2finternal/x")) {
            client.post().uri(URI.create("http://localhost:" + port + path)).exchange()
                    .expectStatus().isNotFound()
                    .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody().jsonPath("$.status").isEqualTo(404);
        }
    }

    @Test
    void clientCannotForwardTheInternalApiKey() {
        client.get().uri("/api/orders").header("X-Internal-Api-Key", "guessed-key")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.internalKey").isEqualTo("none");
    }

    @Test
    void requestIdIsGeneratedForwardedAndReturned() {
        client.get().uri("/api/products").exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches("X-Request-Id", "[0-9a-f-]{36}")
                .expectBody().jsonPath("$.requestId").value(id -> org.assertj.core.api.Assertions.assertThat((String) id).hasSize(36));

        client.get().uri("/api/products").header("X-Request-Id", "frontend-trace-42").exchange()
                .expectHeader().valueEquals("X-Request-Id", "frontend-trace-42")
                .expectBody().jsonPath("$.requestId").isEqualTo("frontend-trace-42");

        client.get().uri("/api/products").header("X-Request-Id", "<script>alert(1)</script> fake").exchange()
                .expectHeader().valueMatches("X-Request-Id", "[0-9a-f-]{36}");
    }

    @Test
    void loginIsRateLimitedPerClient() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange().expectStatus().isOk();
        }
        client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").value(detail ->
                        org.assertj.core.api.Assertions.assertThat((String) detail).contains("Too many login attempts"));

        // Other endpoints are still available to the same client.
        client.get().uri("/api/products").exchange().expectStatus().isOk();
    }

    @Test
    void unknownPathIsAProblemJson404() {
        client.get().uri("/api/unknown").exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("No API route matches this path")
                .jsonPath("$.requestId").exists();
    }

    @Test
    void healthEndpointIsUp() {
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    private void expectService(String path, String service) {
        client.get().uri(path).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo(service);
    }

    private static DisposableServer stub(String name) {
        return HttpServer.create()
                .port(0)
                .handle((request, response) -> {
                    String key = request.requestHeaders().get("X-Internal-Api-Key");
                    String requestId = request.requestHeaders().get("X-Request-Id");
                    String json = "{\"service\":\"%s\",\"path\":\"%s\",\"internalKey\":\"%s\",\"requestId\":\"%s\"}"
                            .formatted(name, request.uri(), key == null ? "none" : key, requestId);
                    return response.header("Content-Type", "application/json").sendString(Mono.just(json));
                })
                .bindNow();
    }
}
