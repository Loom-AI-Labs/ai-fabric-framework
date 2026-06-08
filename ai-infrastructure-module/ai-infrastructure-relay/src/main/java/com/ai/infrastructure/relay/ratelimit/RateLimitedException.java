package com.ai.infrastructure.relay.ratelimit;

public class RateLimitedException extends RuntimeException {

    private final int retryAfterSeconds;

    public RateLimitedException(int retryAfterSeconds) {
        super("Rate limited");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}

