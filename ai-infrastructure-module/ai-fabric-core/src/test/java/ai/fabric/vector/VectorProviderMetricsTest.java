package ai.fabric.vector;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VectorProviderMetricsTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(registry);
        registry.close();
    }

    @Test
    void recordsLowCardinalityFallbackAndRetryCounters() {
        VectorProviderMetrics.recordFallback("Milvus", "Count", "Missing Row Count");
        VectorProviderMetrics.recordRetry("Pinecone", "describeIndexStats", "UNAVAILABLE");

        assertThat(registry.counter(
            VectorProviderMetrics.FALLBACK_COUNTER,
            "provider", "milvus",
            "operation", "count",
            "reason", "missing_row_count"
        ).count()).isEqualTo(1.0d);
        assertThat(registry.counter(
            VectorProviderMetrics.RETRY_COUNTER,
            "provider", "pinecone",
            "operation", "describeindexstats",
            "reason", "unavailable"
        ).count()).isEqualTo(1.0d);
    }
}
