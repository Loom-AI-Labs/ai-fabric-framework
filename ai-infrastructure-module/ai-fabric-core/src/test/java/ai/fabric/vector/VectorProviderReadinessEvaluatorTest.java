package ai.fabric.vector;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VectorProviderReadinessEvaluatorTest {

    @Test
    void marksFullyCapableProviderReady() {
        VectorProviderReadiness readiness = VectorProviderReadinessEvaluator.evaluate(baseDiagnostics(Map.of(
            "provider", "pinecone",
            "searchFilterMode", "provider-side-portable-scalar",
            "scanFilterMode", "client-side-list-fetch-portable-scalar",
            "awaitClearConsistency", true,
            "sparseIndexDetected", false
        )));

        assertThat(readiness.status()).isEqualTo(VectorProviderReadiness.Status.READY);
        assertThat(readiness.productionReady()).isTrue();
        assertThat(readiness.operational()).isTrue();
        assertThat(readiness.reasons()).isEmpty();
        assertThat(readiness.warnings()).isEmpty();
    }

    @Test
    void marksMissingLifecycleCapabilityNotReady() {
        VectorProviderReadiness readiness = VectorProviderReadinessEvaluator.evaluate(baseDiagnostics(Map.of(
            "provider", "custom",
            "supportsScanMetadataFiltering", false,
            "searchFilterMode", "native",
            "scanFilterMode", "native"
        )));

        assertThat(readiness.status()).isEqualTo(VectorProviderReadiness.Status.NOT_READY);
        assertThat(readiness.productionReady()).isFalse();
        assertThat(readiness.operational()).isFalse();
        assertThat(readiness.reasons())
            .contains("Vector provider does not advertise metadata-filtered vector scan.");
    }

    @Test
    void prefersStructuredCapabilitiesWhenFlatCapabilityKeysAreAbsent() {
        java.util.LinkedHashMap<String, Object> diagnostics = new java.util.LinkedHashMap<>();
        diagnostics.put("diagnosticsAvailable", true);
        diagnostics.put("capabilities", capabilities(Map.of(
            "providerName", "pinecone",
            "searchFilterMode", "provider-side-portable-scalar",
            "scanFilterMode", "client-side-list-fetch-portable-scalar",
            "durableStorage", true,
            "productionProfileSafe", true
        )));
        diagnostics.put("awaitClearConsistency", true);
        diagnostics.put("sparseIndexDetected", false);

        VectorProviderReadiness readiness = VectorProviderReadinessEvaluator.evaluate(diagnostics);

        assertThat(readiness.status()).isEqualTo(VectorProviderReadiness.Status.READY);
        assertThat(readiness.reasons()).isEmpty();
        assertThat(readiness.warnings()).isEmpty();
    }

    @Test
    void warnsForNonDurableMemoryProvider() {
        VectorProviderReadiness readiness = VectorProviderReadinessEvaluator.evaluate(baseDiagnostics(Map.of(
            "provider", "memory",
            "persistent", false,
            "searchFilterMode", "in-memory-portable-scalar",
            "scanFilterMode", "in-memory-portable-scalar"
        )));

        assertThat(readiness.status()).isEqualTo(VectorProviderReadiness.Status.WARN);
        assertThat(readiness.productionReady()).isFalse();
        assertThat(readiness.operational()).isTrue();
        assertThat(readiness.warnings())
            .anySatisfy(warning -> assertThat(warning).contains("non-durable"));
    }

    @Test
    void marksQdrantPayloadIndexDriftNotReadyAndRepairAttemptsWarn() {
        VectorProviderReadiness readiness = VectorProviderReadinessEvaluator.evaluate(baseDiagnostics(Map.of(
            "provider", "qdrant",
            "searchFilterMode", "qdrant-payload-filter-with-index-repair",
            "scanFilterMode", "qdrant-payload-filter-with-index-repair",
            "failOnMissingPayloadIndex", false,
            "payloadIndexesSeenMissing", List.of("document:entity_id"),
            "payloadIndexCreateFailures", Map.of("document", "permission denied"),
            "payloadIndexRepairAttempts", Map.of("document", 2)
        )));

        assertThat(readiness.status()).isEqualTo(VectorProviderReadiness.Status.NOT_READY);
        assertThat(readiness.reasons())
            .anySatisfy(reason -> assertThat(reason).contains("payload indexes have been observed missing"))
            .anySatisfy(reason -> assertThat(reason).contains("payload-index creation failures"));
        assertThat(readiness.warnings())
            .anySatisfy(warning -> assertThat(warning).contains("payload-index auto-repair has been used"));
    }

    @Test
    void warnsForWeaviateAggregateCountFallback() {
        VectorProviderReadiness readiness = VectorProviderReadinessEvaluator.evaluate(baseDiagnostics(Map.of(
            "provider", "weaviate",
            "searchFilterMode", "weaviate-field-tokenized-where-with-exact-paging",
            "scanFilterMode", "weaviate-field-tokenized-where-with-exact-paging",
            "aggregateCountFallbacks", Map.of("Document", 1),
            "aggregateCountFallbackReasons", Map.of("Document", "aggregate unsupported")
        )));

        assertThat(readiness.status()).isEqualTo(VectorProviderReadiness.Status.WARN);
        assertThat(readiness.warnings())
            .anySatisfy(warning -> assertThat(warning).contains("aggregate-count compatibility fallback"));
    }

    @Test
    void warnsForFallbackEvidenceEvenWhenProviderNameIsGeneric() {
        VectorProviderReadiness readiness = VectorProviderReadinessEvaluator.evaluate(baseDiagnostics(Map.of(
            "provider", "custom-native-wrapper",
            "searchFilterMode", "provider-side-portable-scalar",
            "scanFilterMode", "provider-side-portable-scalar",
            "metadataFilterFallbacks", Map.of("document", 1),
            "aggregateCountFallbacks", Map.of("document", 1)
        )));

        assertThat(readiness.status()).isEqualTo(VectorProviderReadiness.Status.WARN);
        assertThat(readiness.warnings())
            .anySatisfy(warning -> assertThat(warning).contains("metadata-filter compatibility fallback"))
            .anySatisfy(warning -> assertThat(warning).contains("aggregate-count compatibility fallback"));
    }

    @Test
    void warnsForFallbackReasonMapsEvenWhenCountersAreAbsent() {
        VectorProviderReadiness readiness = VectorProviderReadinessEvaluator.evaluate(baseDiagnostics(Map.of(
            "provider", "custom-native-wrapper",
            "searchFilterMode", "provider-side-portable-scalar",
            "scanFilterMode", "provider-side-portable-scalar",
            "metadataFilterFallbackReasons", Map.of("document", "provider rejected portable metadata filter")
        )));

        assertThat(readiness.status()).isEqualTo(VectorProviderReadiness.Status.WARN);
        assertThat(readiness.warnings())
            .anySatisfy(warning -> assertThat(warning).contains("metadata-filter compatibility fallback"));
    }

    @Test
    void warnsForStandardizedCountFallbacks() {
        VectorProviderReadiness readiness = VectorProviderReadinessEvaluator.evaluate(baseDiagnostics(Map.of(
            "provider", "milvus",
            "searchFilterMode", "milvus-json-expression",
            "scanFilterMode", "milvus-json-expression",
            "countFallbacks", Map.of("products", 1),
            "countFallbackReasons", Map.of("products", "collection statistics did not include row_count")
        )));

        assertThat(readiness.status()).isEqualTo(VectorProviderReadiness.Status.WARN);
        assertThat(readiness.warnings())
            .anySatisfy(warning -> assertThat(warning).contains("count compatibility fallback"));
    }

    @Test
    void ignoresEmptyOrZeroFallbackEvidence() {
        VectorProviderReadiness readiness = VectorProviderReadinessEvaluator.evaluate(baseDiagnostics(Map.of(
            "provider", "custom-native-wrapper",
            "searchFilterMode", "provider-side-portable-scalar",
            "scanFilterMode", "provider-side-portable-scalar",
            "metadataFilterFallbacks", Map.of("document", 0),
            "aggregateCountFallbacks", Map.of("document", 0),
            "countFallbacks", Map.of("document", 0),
            "countFallbackReasons", Map.of()
        )));

        assertThat(readiness.status()).isEqualTo(VectorProviderReadiness.Status.READY);
        assertThat(readiness.warnings()).isEmpty();
    }

    @Test
    void marksUnavailableDiagnosticsNotReady() {
        VectorProviderReadiness readiness = VectorProviderReadinessEvaluator.evaluate(Map.of(
            "diagnosticsAvailable", false,
            "error", "provider down"
        ));

        assertThat(readiness.status()).isEqualTo(VectorProviderReadiness.Status.NOT_READY);
        assertThat(readiness.reasons())
            .contains("Vector provider diagnostics are unavailable: provider down");
    }

    private Map<String, Object> baseDiagnostics(Map<String, Object> overrides) {
        java.util.LinkedHashMap<String, Object> diagnostics = new java.util.LinkedHashMap<>();
        diagnostics.put("diagnosticsAvailable", true);
        diagnostics.put("providerClass", "test.Provider");
        diagnostics.put("supportsVectorScan", true);
        diagnostics.put("supportsSearchMetadataFiltering", true);
        diagnostics.put("supportsScanMetadataFiltering", true);
        diagnostics.put("supportsExactFetchById", true);
        diagnostics.put("supportsClearByEntityType", true);
        diagnostics.put("supportsEfficientEntityTypeCount", true);
        diagnostics.put("countMode", "native-count");
        diagnostics.put("clearMode", "native-clear");
        diagnostics.putAll(overrides);
        return diagnostics;
    }

    private Map<String, Object> capabilities(Map<String, Object> overrides) {
        java.util.LinkedHashMap<String, Object> capabilities = new java.util.LinkedHashMap<>();
        capabilities.put("providerName", "custom");
        capabilities.put("supportsVectorScan", true);
        capabilities.put("supportsSearchMetadataFiltering", true);
        capabilities.put("supportsScanMetadataFiltering", true);
        capabilities.put("supportsExactFetchById", true);
        capabilities.put("supportsClearByEntityType", true);
        capabilities.put("supportsEfficientEntityTypeCount", true);
        capabilities.put("searchFilterMode", "native");
        capabilities.put("scanFilterMode", "native");
        capabilities.put("entityTypeCountMode", "native-count");
        capabilities.put("entityTypeClearMode", "native-clear");
        capabilities.put("durableStorage", true);
        capabilities.put("productionProfileSafe", true);
        capabilities.putAll(overrides);
        return capabilities;
    }
}
