package ai.fabric.vector;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Evaluates provider diagnostics into an operator-facing readiness verdict.
 */
public final class VectorProviderReadinessEvaluator {

    private static final List<CapabilityRequirement> REQUIRED_CAPABILITIES = List.of(
        new CapabilityRequirement("supportsVectorScan", "paged vector scan"),
        new CapabilityRequirement("supportsSearchMetadataFiltering", "metadata-filtered similarity search"),
        new CapabilityRequirement("supportsScanMetadataFiltering", "metadata-filtered vector scan"),
        new CapabilityRequirement("supportsExactFetchById", "exact vector fetch by id"),
        new CapabilityRequirement("supportsClearByEntityType", "clear by entity type"),
        new CapabilityRequirement("supportsEfficientEntityTypeCount", "efficient entity-type count")
    );

    private VectorProviderReadinessEvaluator() {
    }

    public static VectorProviderReadiness evaluate(Map<String, Object> diagnostics) {
        List<String> reasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (diagnostics == null || diagnostics.isEmpty()) {
            reasons.add("Vector provider diagnostics are missing.");
            return verdict(reasons, warnings);
        }

        if (!asBoolean(diagnostics.get("diagnosticsAvailable"), true)) {
            String error = asText(diagnostics.get("error"));
            reasons.add(error.isBlank()
                ? "Vector provider diagnostics are unavailable."
                : "Vector provider diagnostics are unavailable: " + error);
            return verdict(reasons, warnings);
        }

        for (CapabilityRequirement requirement : REQUIRED_CAPABILITIES) {
            if (!asBoolean(diagnosticValue(diagnostics, requirement.key()), false)) {
                reasons.add("Vector provider does not advertise " + requirement.description() + ".");
            }
        }

        String provider = resolveProvider(diagnostics);
        boolean durableStorage = asBoolean(diagnosticValue(diagnostics, "durableStorage"),
            asBoolean(diagnostics.get("persistent"), true));
        boolean productionProfileSafe = asBoolean(diagnosticValue(diagnostics, "productionProfileSafe"), true);
        if (provider.contains("memory") || !durableStorage || !productionProfileSafe) {
            warnings.add("Vector provider is non-durable and intended for development/tests; production use requires explicit acknowledgement.");
        }

        if (asBoolean(diagnosticValue(diagnostics, "supportsSearchMetadataFiltering"), false)
            && !hasText(diagnosticValue(diagnostics, "searchFilterMode"))) {
            warnings.add("Search metadata filtering is enabled but searchFilterMode is not reported.");
        }
        if (asBoolean(diagnosticValue(diagnostics, "supportsScanMetadataFiltering"), false)
            && !hasText(diagnosticValue(diagnostics, "scanFilterMode"))) {
            warnings.add("Scan metadata filtering is enabled but scanFilterMode is not reported.");
        }
        if (asBoolean(diagnosticValue(diagnostics, "supportsEfficientEntityTypeCount"), false)
            && !hasText(modeValue(diagnostics, "entityTypeCountMode", "countMode"))) {
            warnings.add("Efficient entity-type count is enabled but entityTypeCountMode is not reported.");
        }
        if (asBoolean(diagnosticValue(diagnostics, "supportsClearByEntityType"), false)
            && !hasText(modeValue(diagnostics, "entityTypeClearMode", "clearMode"))) {
            warnings.add("Clear by entity type is enabled but entityTypeClearMode is not reported.");
        }
        evaluateCompatibilityFallbacks(diagnostics, warnings);

        if (provider.contains("qdrant")) {
            evaluateQdrant(diagnostics, reasons, warnings);
        }
        if (provider.contains("pinecone")) {
            evaluatePinecone(diagnostics, warnings);
        }

        return verdict(reasons, warnings);
    }

    private static void evaluateQdrant(Map<String, Object> diagnostics,
                                       List<String> reasons,
                                       List<String> warnings) {
        if (hasEntries(diagnostics.get("payloadIndexesSeenMissing"))) {
            reasons.add("Qdrant payload indexes have been observed missing: " + diagnostics.get("payloadIndexesSeenMissing"));
        }
        if (hasEntries(diagnostics.get("payloadIndexCreateFailures"))) {
            reasons.add("Qdrant payload-index creation failures are present: " + diagnostics.get("payloadIndexCreateFailures"));
        }
        if (hasEntries(diagnostics.get("payloadIndexRepairAttempts"))) {
            warnings.add("Qdrant payload-index auto-repair has been used: "
                + diagnostics.get("payloadIndexRepairAttempts"));
        }
    }

    private static void evaluatePinecone(Map<String, Object> diagnostics, List<String> warnings) {
        if (Boolean.FALSE.equals(diagnostics.get("awaitClearConsistency"))) {
            warnings.add("Pinecone clear consistency waiting is disabled; admin clear verification may observe eventual-consistency lag.");
        }
        if (Boolean.TRUE.equals(diagnostics.get("sparseIndexDetected"))) {
            warnings.add("Pinecone sparse index mode is detected; verify sparse-index roundtrip in the live provider suite before release.");
        }
    }

    private static void evaluateCompatibilityFallbacks(Map<String, Object> diagnostics, List<String> warnings) {
        warnForFallbackEvidence(
            diagnostics,
            warnings,
            "metadata-filter",
            "metadataFilterFallbacks",
            "metadataFilterFallbackReasons"
        );
        warnForFallbackEvidence(
            diagnostics,
            warnings,
            "aggregate-count",
            "aggregateCountFallbacks",
            "aggregateCountFallbackReasons"
        );
        warnForFallbackEvidence(
            diagnostics,
            warnings,
            "count",
            "countFallbacks",
            "countFallbackReasons"
        );
    }

    private static void warnForFallbackEvidence(Map<String, Object> diagnostics,
                                                List<String> warnings,
                                                String operation,
                                                String counterKey,
                                                String reasonKey) {
        Object counters = diagnostics.get(counterKey);
        Object reasons = diagnostics.get(reasonKey);
        if (hasPositiveCounters(counters) || hasEntries(reasons)) {
            warnings.add("Vector provider " + operation + " compatibility fallback has been used: "
                + diagnostics.getOrDefault(counterKey, Map.of()));
        }
    }

    private static VectorProviderReadiness verdict(List<String> reasons, List<String> warnings) {
        VectorProviderReadiness.Status status = !reasons.isEmpty()
            ? VectorProviderReadiness.Status.NOT_READY
            : (!warnings.isEmpty() ? VectorProviderReadiness.Status.WARN : VectorProviderReadiness.Status.READY);
        return new VectorProviderReadiness(status, reasons, warnings);
    }

    private static String resolveProvider(Map<String, Object> diagnostics) {
        String provider = asText(diagnostics.get("provider"));
        if (provider.isBlank()) {
            provider = asText(diagnosticValue(diagnostics, "providerName"));
        }
        if (provider.isBlank()) {
            provider = asText(diagnostics.get("providerClass"));
        }
        return provider.toLowerCase(Locale.ROOT);
    }

    private static Object diagnosticValue(Map<String, Object> diagnostics, String key) {
        Object capabilities = diagnostics.get("capabilities");
        if (capabilities instanceof Map<?, ?> capabilityMap && capabilityMap.containsKey(key)) {
            return capabilityMap.get(key);
        }
        return diagnostics.get(key);
    }

    private static Object modeValue(Map<String, Object> diagnostics, String capabilityKey, String flatKey) {
        Object value = diagnosticValue(diagnostics, capabilityKey);
        return hasText(value) ? value : diagnostics.get(flatKey);
    }

    private static boolean asBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text);
        }
        return defaultValue;
    }

    private static boolean hasText(Object value) {
        return !asText(value).isBlank();
    }

    private static String asText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean hasEntries(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value.getClass().isArray()) {
            return Array.getLength(value) > 0;
        }
        if (value instanceof CharSequence text) {
            return !text.toString().isBlank();
        }
        return true;
    }

    private static boolean hasPositiveCounters(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(VectorProviderReadinessEvaluator::isPositive);
        }
        return isPositive(value);
    }

    private static boolean isPositive(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() > 0D;
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim()) > 0L;
            } catch (NumberFormatException ignored) {
                return !text.isBlank();
            }
        }
        return hasEntries(value);
    }

    private record CapabilityRequirement(String key, String description) {
    }
}
