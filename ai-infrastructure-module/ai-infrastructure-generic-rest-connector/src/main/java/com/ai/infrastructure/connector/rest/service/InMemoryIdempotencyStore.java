package com.ai.infrastructure.connector.rest.service;

import com.ai.infrastructure.connector.rest.api.ActionResultDto;
import com.ai.infrastructure.connector.rest.config.RestRoutingConfig;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final RestRoutingConfig config;
    private final Clock clock;

    public InMemoryIdempotencyStore(RestRoutingConfig config, Clock clock) {
        this.config = config;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Override
    public ActionResultDto executeIdempotent(String actionId, String idempotencyKey, Supplier<ActionResultDto> execution) {
        Objects.requireNonNull(execution, "execution");
        if (!StringUtils.hasText(actionId)) {
            throw new IllegalArgumentException("actionId is required");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return execution.get();
        }

        int ttlSeconds = config != null && config.getConnector() != null && config.getConnector().getIdempotency() != null
            ? config.getConnector().getIdempotency().getTtlSeconds()
            : 300;

        int maxWaitMs = config != null && config.getConnector() != null && config.getConnector().getIdempotency() != null
            ? config.getConnector().getIdempotency().getInProgressMaxWaitMs()
            : 2000;

        evictExpired(ttlSeconds);

        String key = actionId.trim() + "::" + idempotencyKey.trim();
        Entry existing = entries.get(key);
        if (existing != null) {
            return await(existing.future, maxWaitMs);
        }

        CompletableFuture<ActionResultDto> future = new CompletableFuture<>();
        Entry created = new Entry(Instant.now(clock), future);
        Entry winner = entries.putIfAbsent(key, created);
        if (winner != null) {
            return await(winner.future, maxWaitMs);
        }

        try {
            ActionResultDto result = execution.get();
            future.complete(result);
            return result;
        } catch (Exception ex) {
            future.completeExceptionally(ex);
            entries.remove(key);
            throw ex;
        }
    }

    private ActionResultDto await(CompletableFuture<ActionResultDto> future, int maxWaitMs) {
        if (future == null) {
            return ActionResultDto.failure("SERVICE_UNAVAILABLE", "Idempotency store error.");
        }
        try {
            if (maxWaitMs <= 0) {
                return ActionResultDto.failure("IDEMPOTENCY_IN_PROGRESS", "Action is already in progress.");
            }
            return future.get(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            return ActionResultDto.failure("IDEMPOTENCY_IN_PROGRESS", "Action is already in progress.");
        }
    }

    private void evictExpired(int ttlSeconds) {
        if (ttlSeconds <= 0) {
            return;
        }
        Instant now = Instant.now(clock);
        entries.entrySet().removeIf(entry -> entry.getValue() == null
            || entry.getValue().createdAt == null
            || entry.getValue().createdAt.plusSeconds(ttlSeconds).isBefore(now));
    }

    private record Entry(Instant createdAt, CompletableFuture<ActionResultDto> future) {
    }
}

