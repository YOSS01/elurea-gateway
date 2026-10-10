package com.elurea.gateway.ratelimit;

/**
 * Classic token bucket: holds up to {@code capacity} tokens, refilled continuously at {@code capacity} per
 * minute. Each request takes one token; an empty bucket means "too many requests". Thread-safe.
 */
final class TokenBucket {

    private static final double NANOS_PER_MINUTE = 60_000_000_000d;

    private final int capacity;
    private final double tokensPerNano;
    private double tokens;
    private long lastRefillNanos;
    private volatile long lastUsedNanos;

    TokenBucket(int capacityPerMinute, long nowNanos) {
        this.capacity = capacityPerMinute;
        this.tokensPerNano = capacityPerMinute / NANOS_PER_MINUTE;
        this.tokens = capacityPerMinute;
        this.lastRefillNanos = nowNanos;
        this.lastUsedNanos = nowNanos;
    }

    /**
     * @return 0 if the request may go through, otherwise the number of seconds after which one token is back
     */
    synchronized long tryConsume(long nowNanos) {
        tokens = Math.min(capacity, tokens + (nowNanos - lastRefillNanos) * tokensPerNano);
        lastRefillNanos = nowNanos;
        lastUsedNanos = nowNanos;
        if (tokens >= 1) {
            tokens -= 1;
            return 0;
        }
        double missingNanos = (1 - tokens) / tokensPerNano;
        return Math.max(1, (long) Math.ceil(missingNanos / 1_000_000_000d));
    }

    long lastUsedNanos() {
        return lastUsedNanos;
    }
}
