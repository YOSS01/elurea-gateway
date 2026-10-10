package com.elurea.gateway.web;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetSocketAddress;

/**
 * Who is calling, for rate limiting and logs.
 */
public final class ClientAddresses {

    private ClientAddresses() {
    }

    /** IP of the TCP peer: cannot be spoofed, but is the proxy's IP when running behind a load balancer. */
    public static String remoteAddress(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null) {
            return "unknown";
        }
        return remote.getAddress() != null ? remote.getAddress().getHostAddress() : remote.getHostString();
    }

    /**
     * The original client IP. X-Forwarded-For is only honoured when explicitly trusted, i.e. when a proxy
     * we control sets it; otherwise anyone could send a fake value to dodge the rate limits.
     */
    public static String clientIp(ServerHttpRequest request, boolean trustForwardedFor) {
        if (trustForwardedFor) {
            String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].strip();
            }
        }
        return remoteAddress(request);
    }
}
