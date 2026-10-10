package com.elurea.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    @Test
    void allowsTheBurstThenRefillsGradually() {
        TokenBucket bucket = new TokenBucket(3, 0);

        assertThat(bucket.tryConsume(0)).isZero();
        assertThat(bucket.tryConsume(0)).isZero();
        assertThat(bucket.tryConsume(0)).isZero();
        assertThat(bucket.tryConsume(0)).isEqualTo(20); // 3 per minute: one token every 20 seconds

        assertThat(bucket.tryConsume(19 * SECOND)).isPositive();
        assertThat(bucket.tryConsume(20 * SECOND)).isZero();
    }

    @Test
    void neverStoresMoreThanItsCapacity() {
        TokenBucket bucket = new TokenBucket(2, 0);
        long anHourLater = 3600 * SECOND;

        assertThat(bucket.tryConsume(anHourLater)).isZero();
        assertThat(bucket.tryConsume(anHourLater)).isZero();
        assertThat(bucket.tryConsume(anHourLater)).isPositive();
    }
}
