package ai.fabric.vector;

import io.micrometer.core.instrument.Metrics;

import java.util.Locale;

/**
 * Low-cardinality operational metrics for vector provider fallback and retry paths.
 */
public final class VectorProviderMetrics {

    public static final String FALLBACK_COUNTER = "ai.fabric.vector.provider.fallbacks";
    public static final String RETRY_COUNTER = "ai.fabric.vector.provider.retries";

    private VectorProviderMetrics() {
    }

    public static void recordFallback(String provider, String operation, String reason) {
        Metrics.counter(
            FALLBACK_COUNTER,
            "provider", tag(provider),
            "operation", tag(operation),
            "reason", tag(reason)
        ).increment();
    }

    public static void recordRetry(String provider, String operation, String reason) {
        Metrics.counter(
            RETRY_COUNTER,
            "provider", tag(provider),
            "operation", tag(operation),
            "reason", tag(reason)
        ).increment();
    }

    private static String tag(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "unknown";
        }
        normalized = normalized.replaceAll("[^a-z0-9_.-]+", "_");
        normalized = normalized.replaceAll("_+", "_");
        if (normalized.length() > 64) {
            normalized = normalized.substring(0, 64);
        }
        return normalized;
    }
}
