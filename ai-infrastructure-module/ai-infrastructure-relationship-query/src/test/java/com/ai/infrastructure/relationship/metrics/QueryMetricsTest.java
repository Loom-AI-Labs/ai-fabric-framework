package com.ai.infrastructure.relationship.metrics;

import com.ai.infrastructure.relationship.cache.QueryCache;
import com.ai.infrastructure.relationship.config.RelationshipQueryProperties;
import com.ai.infrastructure.relationship.model.QueryMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryMetricsTest {

    private QueryMetrics metrics;

    @BeforeEach
    void setUp() {
        RelationshipQueryProperties properties = new RelationshipQueryProperties();
        metrics = new QueryMetrics(properties);
    }

    @Test
    void shouldRecordPlanMetrics() {
        metrics.recordPlan(12, true, true);
        metrics.recordPlan(25, false, false);

        QueryMetrics.QueryMetricsSnapshot snapshot = metrics.snapshot();
        assertThat(snapshot.getPlanCount()).isEqualTo(2);
        assertThat(snapshot.getPlanFailures()).isEqualTo(1);
        assertThat(snapshot.getPlanCacheHits()).isEqualTo(1);
        assertThat(snapshot.getPlanCacheMisses()).isEqualTo(1);
    }

    @Test
    void shouldRecordExecutionMetrics() {
        metrics.recordExecution(QueryMode.ENHANCED, 120, 5, true);
        metrics.recordExecutionFailure(200);
        QueryMetrics.QueryMetricsSnapshot snapshot = metrics.snapshot();
        assertThat(snapshot.getExecutionCount()).isEqualTo(1);
        assertThat(snapshot.getExecutionFailures()).isEqualTo(1);
        assertThat(snapshot.getExecutionLatencyTotalMs()).isEqualTo(320);
    }

    @Test
    void shouldRecordFallbackStages() {
        metrics.recordFallbackStage("FALLBACK_METADATA", true, 1);
        metrics.recordFallbackStage("FALLBACK_VECTOR", false, 0);
        metrics.recordFallbackStage("FALLBACK_SIMPLE", true, 2);

        QueryMetrics.QueryMetricsSnapshot snapshot = metrics.snapshot();
        assertThat(snapshot.getFallbackMetadataCount()).isEqualTo(1);
        assertThat(snapshot.getFallbackVectorCount()).isEqualTo(1);
        assertThat(snapshot.getFallbackSimpleCount()).isEqualTo(1);
    }

    @Test
    void shouldUpdateCacheSnapshot() {
        QueryCache.CacheStats stats = new QueryCache.CacheStats(1, 2, 0, 3);
        metrics.updateCacheStats(stats, stats, stats);
        QueryMetrics.QueryMetricsSnapshot snapshot = metrics.snapshot();
        assertThat(snapshot.getCacheSnapshot().getPlan().hits()).isEqualTo(1);
    }
}
