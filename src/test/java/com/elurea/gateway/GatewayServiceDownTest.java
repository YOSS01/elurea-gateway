package com.elurea.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Services that are not running must give a clear 503, never a hanging request or a raw 500.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.services.order-url=http://localhost:${closed.port}",
                "app.services.payment-url=http://localhost:${closed.port}"
        })
class GatewayServiceDownTest {

    static {
        System.setProperty("closed.port", Integer.toString(freePort()));
    }

    @LocalServerPort
    private int port;

    @Test
    void unreachableServiceGivesProblemJson503() {
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();

        client.get().uri("/api/orders").exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Service Unavailable")
                .jsonPath("$.requestId").exists();

        client.post().uri("/api/payments").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isEqualTo(503);
    }

    /** A port nothing listens on: bound then released immediately. */
    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
