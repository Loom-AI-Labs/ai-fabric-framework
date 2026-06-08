package com.ai.infrastructure.relationship.metrics;

import com.ai.infrastructure.relationship.cache.QueryCache;
import com.ai.infrastructure.relationship.config.RelationshipQueryProperties;
import com.ai.infrastructure.relationship.model.QueryMode;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight in-memory metrics collector for relationship-aware queries.
 */
@Slf4j
public class QueryMetrics {

    private final boolean enabled;
    private final long latencyAlertMs;
    private final long fallbackAlertThreshold;

    private final AtomicLong planCount = new AtomicLong();
    private final AtomicLong planFailures = new AtomicLong();
    private final LongAdder planLatencyTotal = new LongAdder();
    private final AtomicLong planCacheHits = new AtomicLong();
    private final AtomicLong planCacheMisses = new AtomicLong();

    private final AtomicLong executionCount = new AtomicLong();
    private final AtomicLong executionFailures = new AtomicLong();
    private final LongAdder executionLatencyTotal = new LongAdder();

    private final AtomicLong fallbackMetadata = new AtomicLong();
    private final AtomicLong fallbackVector = new AtomicLong();
    private final AtomicLong fallbackSimple = new AtomicLong();

    private volatile CacheSnapshot cacheSnapshot = CacheSnapshot.empty();

    public QueryMetrics(RelationshipQueryProperties properties) {
        RelationshipQueryProperties.MetricsProperties metrics = properties.getMetrics();
        this.enabled = metrics.isEnabled();
        this.latencyAlertMs = metrics.getLatencyAlertMs();
        this.fallbackAlertThreshold = metrics.getFallbackAlertThreshold();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void recordPlan(long latencyMs, boolean fromCache, boolean success) {
        if (!enabled) {
            return;
        }
        planCount.incrementAndGet();
        planLatencyTotal.add(latencyMs);
        if (fromCache) {
            planCacheHits.incrementAndGet();
        } else {
            planCacheMisses.incrementAndGet();
        }
        if (!success) {
            planFailures.incrementAndGet();
        }
        if (latencyMs > latencyAlertMs && !fromCache) {
            log.warn("Plan latency {}ms exceeded alert threshold {}", latencyMs, latencyAlertMs);
        }
    }

    public void recordExecution(QueryMode mode, long latencyMs, int resultCount, boolean fallbackUsed) {
        if (!enabled) {
            return;
        }
        executionCount.incrementAndGet();
        executionLatencyTotal.add(latencyMs);
        if (fallbackUsed && executionCount.get() % fallbackAlertThreshold == 0) {
            log.warn("Fallback mode triggered {} times (latest mode: {})", executionCount.get(), mode);
        }
        if (latencyMs > latencyAlertMs) {
            log.warn("Execution latency {}ms exceeded alert threshold {}", latencyMs, latencyAlertMs);
        }
    }

    public void recordExecutionFailure(long latencyMs) {
        if (!enabled) {
            return;
        }
        executionFailures.incrementAndGet();
        executionLatencyTotal.add(latencyMs);
    }

    public void recordFallbackStage(String stage, boolean success, int producedResults) {
        if (!enabled) {
            return;
        }
        switch (stage) {
            case "FALLBACK_METADATA" -> fallbackMetadata.incrementAndGet();
            case "FALLBACK_VECTOR" -> fallbackVector.incrementAndGet();
            case "FALLBACK_SIMPLE" -> fallbackSimple.incrementAndGet();
            default -> { }
        }
        if (!success && fallbackMetadata.get() + fallbackVector.get() + fallbackSimple.get() >= fallbackAlertThreshold) {
            log.warn("Fallback stage {} has failed {} times", stage, fallbackAlertThreshold);
        }
    }

    public void updateCacheStats(QueryCache.CacheStats plan,
                                 QueryCache.CacheStats embedding,
                                 QueryCache.CacheStats result) {
        if (!enabled) {
            return;
        }
        this.cacheSnapshot = new CacheSnapshot(plan, embedding, result, Instant.now());
    }

    public QueryMetricsSnapshot snapshot() {
        return new QueryMetricsSnapshot(
            planCount.get(),
            planFailures.get(),
            planLatencyTotal.sum(),
            planCacheHits.get(),
            planCacheMisses.get(),
            executionCount.get(),
            executionFailures.get(),
            executionLatencyTotal.sum(),
            fallbackMetadata.get(),
            fallbackVector.get(),
            fallbackSimple.get(),
            cacheSnapshot
        );
    }

    @Value
    public static class QueryMetricsSnapshot {
        long planCount;
        long planFailures;
        long planLatencyTotalMs;
        long planCacheHits;
        long planCacheMisses;
        long executionCount;
        long executionFailures;
        long executionLatencyTotalMs;
        long fallbackMetadataCount;
        long fallbackVectorCount;
        long fallbackSimpleCount;
        CacheSnapshot cacheSnapshot;
    }

    @Value
    public static class CacheSnapshot {
        QueryCache.CacheStats plan;
        QueryCache.CacheStats embedding;
        QueryCache.CacheStats result;
        Instant capturedAt;

        static CacheSnapshot empty() {
            return new CacheSnapshot(null, null, null, Instant.now());
        }
    }
}
