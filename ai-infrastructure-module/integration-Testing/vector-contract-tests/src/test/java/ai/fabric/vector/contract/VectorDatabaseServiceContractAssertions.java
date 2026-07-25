package ai.fabric.vector.contract;

import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.dto.VectorScanRequest;
import ai.fabric.exception.AIServiceException;
import ai.fabric.rag.VectorDatabaseService;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

final class VectorDatabaseServiceContractAssertions {

    static final CapabilityExpectations STANDARD_EFFICIENT_COUNT = new CapabilityExpectations(true);
    static final CapabilityExpectations STANDARD_NON_EFFICIENT_COUNT = new CapabilityExpectations(false);

    void assertFullLifecycleContract(VectorDatabaseService service, CapabilityExpectations expectations) throws Exception {
        assertPreciseCapabilities(service, expectations);
        assertStoreSearchUpdateAndRemove(service);
        assertMetadataScanAndProjection(service);
        assertIntegralMetadataFilterDoesNotMatchDecimalMetadata(service);
        assertExactMetadataFilteringPrecedesResultLimits(service);
        assertEmptyStringMetadataFilterIsExact(service);
        assertInvalidDirectInputContract(service);
        assertBatchAndClearContract(service);
    }

    void assertProviderScopeIsolation(VectorDatabaseService left, VectorDatabaseService right) {
        String leftSharedId = left.storeVector("document", "shared-id", "Left scoped document",
            vector(1.0, 0.0, 0.0), Map.of("scope", "left", "shared", true));
        String leftOnlyId = left.storeVector("document", "left-only", "Left only document",
            vector(0.9, 0.1, 0.0), Map.of("scope", "left", "shared", false));

        String rightSharedId = right.storeVector("document", "shared-id", "Right scoped document",
            vector(0.0, 1.0, 0.0), Map.of("scope", "right", "shared", true));
        String rightOnlyId = right.storeVector("document", "right-only", "Right only document",
            vector(0.0, 0.9, 0.1), Map.of("scope", "right", "shared", false));

        assertThat(left.getVectorByEntity("document", "shared-id"))
            .map(VectorRecord::getContent)
            .contains("Left scoped document");
        assertThat(right.getVectorByEntity("document", "shared-id"))
            .map(VectorRecord::getContent)
            .contains("Right scoped document");
        assertThat(left.getVector(leftSharedId)).map(VectorRecord::getContent).contains("Left scoped document");
        assertThat(right.getVector(rightSharedId)).map(VectorRecord::getContent).contains("Right scoped document");
        assertThat(left.getVectorCountByEntityType("document")).isEqualTo(2);
        assertThat(right.getVectorCountByEntityType("document")).isEqualTo(2);

        AISearchResponse leftSearch = left.search(vector(1.0, 0.0, 0.0), AISearchRequest.builder()
            .query("scoped document")
            .entityType("document")
            .metadata(Map.of("scope", "left", "shared", true))
            .limit(10)
            .threshold(0.0d)
            .build());
        assertThat(leftSearch.getResults())
            .map(VectorDatabaseServiceContractAssertions::resultEntityId)
            .containsExactly("shared-id");

        AISearchResponse rightCannotSeeLeft = right.search(vector(1.0, 0.0, 0.0), AISearchRequest.builder()
            .query("scoped document")
            .entityType("document")
            .metadata(Map.of("scope", "left"))
            .limit(10)
            .threshold(0.0d)
            .build());
        assertThat(rightCannotSeeLeft.getResults()).isEmpty();

        VectorScanPage leftScan = left.scan(VectorScanRequest.builder()
            .entityType("document")
            .metadataEquals(Map.of("scope", "left"))
            .limit(10)
            .includeContent(true)
            .includeMetadata(true)
            .build());
        assertThat(leftScan.getVectors())
            .map(VectorRecord::getEntityId)
            .containsExactlyInAnyOrder("shared-id", "left-only");

        VectorScanPage rightScanForLeft = right.scan(VectorScanRequest.builder()
            .entityType("document")
            .metadataEquals(Map.of("scope", "left"))
            .limit(10)
            .includeMetadata(true)
            .build());
        assertThat(rightScanForLeft.getVectors()).isEmpty();

        assertThat(left.removeVectorById(rightOnlyId)).isFalse();
        assertThat(left.getVector(rightOnlyId)).isEmpty();
        assertThat(right.getVector(rightSharedId)).isPresent();

        assertThat(left.clearVectorsByEntityType("document")).isEqualTo(2);
        assertThat(left.getVectorCountByEntityType("document")).isZero();
        assertThat(left.getVector(leftSharedId)).isEmpty();
        assertThat(left.getVector(leftOnlyId)).isEmpty();

        assertThat(right.getVectorCountByEntityType("document")).isEqualTo(2);
        assertThat(right.getVector(rightSharedId)).isPresent();
        assertThat(right.getVector(rightOnlyId)).isPresent();
        assertThat(right.clearVectorsByEntityType("document")).isEqualTo(2);
    }

    void assertPreciseCapabilities(VectorDatabaseService service, CapabilityExpectations expectations) {
        assertThat(service.supportsVectorScan()).isTrue();
        assertThat(service.supportsSearchMetadataFiltering()).isTrue();
        assertThat(service.supportsScanMetadataFiltering()).isTrue();
        assertThat(service.supportsExactFetchById()).isTrue();
        assertThat(service.supportsClearByEntityType()).isTrue();
        assertThat(service.supportsEfficientEntityTypeCount())
            .isEqualTo(expectations.supportsEfficientEntityTypeCount());

        Map<String, Object> diagnostics = service.adminDiagnostics();
        assertThat(diagnostics)
            .containsKey("providerClass")
            .containsEntry("supportsVectorScan", service.supportsVectorScan())
            .containsEntry("supportsMetadataFiltering", service.supportsMetadataFiltering())
            .containsEntry("supportsSearchMetadataFiltering", service.supportsSearchMetadataFiltering())
            .containsEntry("supportsScanMetadataFiltering", service.supportsScanMetadataFiltering())
            .containsEntry("supportsExactFetchById", service.supportsExactFetchById())
            .containsEntry("supportsClearByEntityType", service.supportsClearByEntityType())
            .containsEntry("supportsEfficientEntityTypeCount", service.supportsEfficientEntityTypeCount())
            .containsEntry("metadataFilteredSearch", service.supportsSearchMetadataFiltering())
            .containsEntry("metadataFilteredScan", service.supportsScanMetadataFiltering());
        assertThat(diagnostics.get("searchFilterMode")).asString().isNotBlank();
        assertThat(diagnostics.get("scanFilterMode")).asString().isNotBlank();
        assertThat(diagnostics.get("countMode")).asString().isNotBlank();
        assertThat(diagnostics.get("clearMode")).asString().isNotBlank();

        assertThat(diagnostics.get("capabilities")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> capabilities = (Map<String, Object>) diagnostics.get("capabilities");
        assertThat(capabilities)
            .containsEntry("supportsVectorScan", service.supportsVectorScan())
            .containsEntry("supportsSearchMetadataFiltering", service.supportsSearchMetadataFiltering())
            .containsEntry("supportsScanMetadataFiltering", service.supportsScanMetadataFiltering())
            .containsEntry("supportsExactFetchById", service.supportsExactFetchById())
            .containsEntry("supportsClearByEntityType", service.supportsClearByEntityType())
            .containsEntry("supportsEfficientEntityTypeCount", service.supportsEfficientEntityTypeCount())
            .containsEntry("lifecycleAdminCompatible", expectations.supportsEfficientEntityTypeCount());
        assertThat(capabilities.get("providerName")).asString().isNotBlank();
        assertThat(capabilities.get("providerClass")).asString().isNotBlank();
        assertThat(capabilities.get("nativeClient")).asString().isNotBlank();
        assertThat(capabilities.get("searchFilterMode")).asString().isEqualTo(diagnostics.get("searchFilterMode").toString());
        assertThat(capabilities.get("scanFilterMode")).asString().isEqualTo(diagnostics.get("scanFilterMode").toString());
        assertThat(capabilities.get("metadataFilterSubset")).asString().isNotBlank();
        assertThat(capabilities.get("entityTypeCountMode")).asString().isNotBlank();
        assertThat(capabilities.get("entityTypeClearMode")).asString().isNotBlank();
        assertThat(capabilities.get("consistencyModel")).asString().isNotBlank();
        assertThat(capabilities).containsKeys("durableStorage", "productionProfileSafe");
    }

    void assertStoreSearchUpdateAndRemove(VectorDatabaseService service) throws Exception {
        String publicVectorId = service.storeVector("document", "doc-public", "Public product guide",
            vector(1.0, 0.0, 0.0), Map.of("visibility", "public", "featured", true, "rank", 1, "score", 9));
        String privateVectorId = service.storeVector("document", "doc-private", "Private product guide",
            vector(0.0, 1.0, 0.0), Map.of("visibility", "private", "featured", false, "rank", 2));
        String articleVectorId = service.storeVector("article", "doc-public", "Article with same entity id",
            vector(0.0, 0.0, 1.0), Map.of("visibility", "public"));

        assertThat(publicVectorId).isNotBlank();
        assertThat(privateVectorId).isNotBlank();
        assertThat(articleVectorId).isNotBlank();
        assertThat(service.getVector(publicVectorId)).map(VectorRecord::getEntityId).contains("doc-public");
        assertThat(service.getVectorByEntity("document", "doc-public")).map(VectorRecord::getVectorId).contains(publicVectorId);
        assertThat(service.getVectorByEntity("article", "doc-public")).map(VectorRecord::getVectorId).contains(articleVectorId);
        assertThat(service.vectorExists("document", "doc-public")).isTrue();
        assertThat(service.getVectorCountByEntityType("document")).isEqualTo(2);

        AISearchResponse response = service.search(vector(1.0, 0.0, 0.0), AISearchRequest.builder()
            .query("product guide")
            .entityType("document")
            .metadata(Map.of("visibility", "public", "featured", true, "rank", 1, "score", 9))
            .limit(10)
            .threshold(0.0d)
            .build());

        assertThat(response.getResults()).hasSize(1);
        assertThat(resultEntityId(response.getResults().getFirst())).isEqualTo("doc-public");
        assertThat((Double) response.getResults().getFirst().get("similarity")).isCloseTo(1.0d, within(0.000001d));

        Map<String, Object> unsupportedSearchFilter = new LinkedHashMap<>();
        unsupportedSearchFilter.put("visibility", "public");
        unsupportedSearchFilter.put("tags", List.of("public"));
        unsupportedSearchFilter.put("nullable", null);

        AISearchResponse unsupportedResponse = service.search(vector(1.0, 0.0, 0.0), AISearchRequest.builder()
            .query("product guide")
            .entityType("document")
            .metadata(unsupportedSearchFilter)
            .limit(10)
            .threshold(0.0d)
            .build());
        assertThat(unsupportedResponse.getResults()).isEmpty();

        Thread.sleep(5L);
        assertThat(service.updateVector(publicVectorId, "document", "doc-public", "Updated public product guide",
            vector(0.9, 0.1, 0.0), Map.of("visibility", "public", "featured", true, "rank", 1, "score", 9)))
            .isTrue();
        VectorRecord updated = service.getVector(publicVectorId).orElseThrow();
        assertThat(updated.getContent()).isEqualTo("Updated public product guide");
        assertThat(updated.getMetadata()).containsEntry("visibility", "public");
        assertThat(updated.getVersion()).isGreaterThanOrEqualTo(1);
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(updated.getCreatedAt());

        String missingVectorId = UUID.randomUUID().toString();
        assertThat(service.updateVector(missingVectorId, "document", "doc-missing", "Missing document",
            vector(1.0, 0.0, 0.0), Map.of("visibility", "public")))
            .isFalse();
        assertThat(service.getVector(missingVectorId)).isEmpty();
        assertThat(service.vectorExists("document", "doc-missing")).isFalse();

        assertThat(service.removeVector("document", "doc-public")).isTrue();
        assertThat(service.getVector(publicVectorId)).isEmpty();
        assertThat(service.getVector(articleVectorId)).isPresent();
        assertThat(service.getVector(privateVectorId)).isPresent();
    }

    void assertMetadataScanAndProjection(VectorDatabaseService service) {
        service.storeVector("document", "doc-a", "First public document",
            vector(1.0, 0.0, 0.0), Map.of("visibility", "public", "tenant", "t1"));
        service.storeVector("document", "doc-b", "Second public document",
            vector(0.9, 0.1, 0.0), Map.of("visibility", "public", "tenant", "t1"));
        service.storeVector("document", "doc-c", "Private document",
            vector(0.0, 1.0, 0.0), Map.of("visibility", "private", "tenant", "t1"));

        VectorScanPage first = service.scan(VectorScanRequest.builder()
            .entityType("document")
            .metadataEquals(Map.of("visibility", "public", "tenant", "t1"))
            .limit(1)
            .includeContent(false)
            .includeEmbedding(false)
            .includeMetadata(true)
            .build());

        assertThat(first.getVectors()).hasSize(1);
        assertThat(first.isHasMore()).isTrue();
        assertThat(first.getNextCursor()).isNotBlank();
        assertThat(first.getVectors().getFirst().getContent()).isNull();
        assertThat(first.getVectors().getFirst().getEmbedding()).isNull();
        assertThat(first.getVectors().getFirst().getMetadata()).containsEntry("visibility", "public");

        VectorScanPage projected = service.scan(VectorScanRequest.builder()
            .entityType("document")
            .metadataEquals(Map.of("visibility", "public", "tenant", "t1"))
            .limit(1)
            .includeContent(false)
            .includeEmbedding(false)
            .includeMetadata(false)
            .build());

        assertThat(projected.getVectors()).hasSize(1);
        assertThat(projected.getVectors().getFirst().getContent()).isNull();
        assertThat(projected.getVectors().getFirst().getEmbedding()).isNull();
        assertThat(projected.getVectors().getFirst().getMetadata()).isNull();

        VectorScanPage second = service.scan(VectorScanRequest.builder()
            .entityType("document")
            .metadataEquals(Map.of("visibility", "public", "tenant", "t1"))
            .cursor(first.getNextCursor())
            .limit(1)
            .includeContent(false)
            .includeEmbedding(false)
            .includeMetadata(true)
            .build());

        assertThat(second.getVectors()).hasSize(1);
        assertThat(second.isHasMore()).isFalse();
        assertThat(second.getNextCursor()).isNull();
        assertThat(Stream.concat(first.getVectors().stream(), second.getVectors().stream())
            .map(VectorRecord::getEntityId))
            .containsExactlyInAnyOrder("doc-a", "doc-b");

        Map<String, Object> unsupportedFilter = new LinkedHashMap<>();
        unsupportedFilter.put("tags", List.of("public"));
        unsupportedFilter.put("nullable", null);

        VectorScanPage unsupported = service.scan(VectorScanRequest.builder()
            .entityType("document")
            .metadataEquals(unsupportedFilter)
            .limit(10)
            .build());
        assertThat(unsupported.getVectors()).isEmpty();
    }

    void assertIntegralMetadataFilterDoesNotMatchDecimalMetadata(VectorDatabaseService service) {
        service.storeVector("document", "doc-decimal-rank", "Decimal rank document",
            vector(1.0, 0.0, 0.0), Map.of("rank", 7.9d));
        service.storeVector("document", "doc-integral-rank", "Integral rank document",
            vector(0.9, 0.1, 0.0), Map.of("rank", 7));

        AISearchResponse search = service.search(vector(1.0, 0.0, 0.0), AISearchRequest.builder()
            .query("ranked document")
            .entityType("document")
            .metadata(Map.of("rank", 7))
            .limit(10)
            .threshold(0.0d)
            .build());
        assertThat(search.getResults())
            .map(VectorDatabaseServiceContractAssertions::resultEntityId)
            .contains("doc-integral-rank")
            .doesNotContain("doc-decimal-rank");

        VectorScanPage scan = service.scan(VectorScanRequest.builder()
            .entityType("document")
            .metadataEquals(Map.of("rank", 7))
            .limit(10)
            .build());
        assertThat(scan.getVectors())
            .map(VectorRecord::getEntityId)
            .contains("doc-integral-rank")
            .doesNotContain("doc-decimal-rank");
    }

    void assertExactMetadataFilteringPrecedesResultLimits(VectorDatabaseService service) {
        try {
            for (int index = 0; index < 8; index++) {
                service.storeVector(
                    "filter-order",
                    "decimal-" + index,
                    "More similar decimal metadata candidate " + index,
                    vector(1.0, 0.0, 0.0),
                    Map.of("rank", 7.9d)
                );
            }
            service.storeVector(
                "filter-order",
                "integral-target",
                "Lower similarity exact metadata target",
                vector(0.8, 0.6, 0.0),
                Map.of("rank", 7)
            );

            AISearchResponse search = service.search(vector(1.0, 0.0, 0.0), AISearchRequest.builder()
                .query("exact metadata target")
                .entityType("filter-order")
                .metadata(Map.of("rank", 7))
                .limit(1)
                .threshold(0.0d)
                .build());

            assertThat(search.getResults())
                .map(VectorDatabaseServiceContractAssertions::resultEntityId)
                .containsExactly("integral-target");

            VectorScanPage scan = service.scan(VectorScanRequest.builder()
                .entityType("filter-order")
                .metadataEquals(Map.of("rank", 7))
                .limit(1)
                .build());

            assertThat(scan.getVectors())
                .map(VectorRecord::getEntityId)
                .containsExactly("integral-target");
        } finally {
            service.clearVectorsByEntityType("filter-order");
        }
    }

    void assertEmptyStringMetadataFilterIsExact(VectorDatabaseService service) {
        service.storeVector("document", "doc-empty-status", "Empty status document",
            vector(1.0, 0.0, 0.0), Map.of("status", ""));
        service.storeVector("document", "doc-filled-status", "Filled status document",
            vector(1.0, 0.0, 0.0), Map.of("status", "filled"));

        AISearchResponse search = service.search(vector(1.0, 0.0, 0.0), AISearchRequest.builder()
            .query("status document")
            .entityType("document")
            .metadata(Map.of("status", ""))
            .limit(10)
            .threshold(0.0d)
            .build());

        assertThat(search.getResults())
            .map(VectorDatabaseServiceContractAssertions::resultEntityId)
            .contains("doc-empty-status")
            .doesNotContain("doc-filled-status");

        VectorScanPage scan = service.scan(VectorScanRequest.builder()
            .entityType("document")
            .metadataEquals(Map.of("status", ""))
            .limit(10)
            .includeMetadata(true)
            .build());

        assertThat(scan.getVectors())
            .map(VectorRecord::getEntityId)
            .contains("doc-empty-status")
            .doesNotContain("doc-filled-status");
        assertThat(scan.getVectors())
            .filteredOn(vector -> "doc-empty-status".equals(vector.getEntityId()))
            .singleElement()
            .satisfies(vector -> assertThat(vector.getMetadata()).containsEntry("status", ""));
    }

    void assertBatchAndClearContract(VectorDatabaseService service) {
        long existingDocuments = service.getVectorCountByEntityType("document");
        long existingArticles = service.getVectorCountByEntityType("article");

        List<String> vectorIds = service.batchStoreVectors(List.of(
            VectorRecord.builder()
                .entityType("product")
                .entityId("p-1")
                .content("First product")
                .embedding(vector(1.0, 0.0, 0.0))
                .metadata(Map.of("category", "one"))
                .build(),
            VectorRecord.builder()
                .entityType("product")
                .entityId("p-2")
                .content("Second product")
                .embedding(vector(0.0, 1.0, 0.0))
                .metadata(Map.of("category", "two"))
                .build(),
            VectorRecord.builder()
                .entityType("article")
                .entityId("a-1")
                .content("Article")
                .embedding(vector(0.0, 0.0, 1.0))
                .metadata(Map.of("category", "docs"))
                .build()
        ));

        assertThat(vectorIds).hasSize(3);
        assertThat(service.getVectorCountByEntityType("product")).isEqualTo(2);
        assertThat(service.getVectorCountByEntityType("article")).isEqualTo(existingArticles + 1);

        String removedProductId = vectorIds.get(1);
        assertThat(service.removeVectorById(removedProductId)).isTrue();
        assertThat(service.getVector(removedProductId)).isEmpty();

        assertThat(service.batchUpdateVectors(List.of(
            VectorRecord.builder()
                .vectorId(vectorIds.getFirst())
                .entityType("product")
                .entityId("p-1")
                .content("Updated first product")
                .embedding(vector(0.9, 0.1, 0.0))
                .metadata(Map.of("category", "updated"))
                .build(),
            VectorRecord.builder()
                .vectorId(removedProductId)
                .entityType("product")
                .entityId("p-2")
                .content("Removed product")
                .embedding(vector(1.0, 0.0, 0.0))
                .metadata(Map.of("category", "removed"))
                .build()
        ))).isEqualTo(1);
        assertThat(service.getVector(vectorIds.getFirst()).orElseThrow().getContent())
            .isEqualTo("Updated first product");
        assertThat(service.getVector(removedProductId)).isEmpty();

        String removableProductId = service.storeVector("product", "p-3", "Third product",
            vector(0.2, 0.8, 0.0), Map.of("category", "three"));
        assertThat(service.batchRemoveVectors(List.of(removableProductId, removedProductId))).isEqualTo(1);
        assertThat(service.getVector(removableProductId)).isEmpty();

        assertThat(service.clearVectorsByEntityType("product")).isEqualTo(1);
        assertThat(service.getVector(vectorIds.getFirst())).isEmpty();
        assertThat(service.getVector(vectorIds.get(2))).isPresent();
        assertThat(service.clearVectors()).isEqualTo(existingDocuments + existingArticles + 1);
        assertThat(service.getVectorCountByEntityType("document")).isZero();
        assertThat(service.getVectorCountByEntityType("article")).isZero();
        assertThat(service.getVectorCountByEntityType("product")).isZero();
    }

    void assertInvalidDirectInputContract(VectorDatabaseService service) {
        assertThatThrownBy(() -> service.storeVector(" ", "doc-invalid", "Invalid document",
            vector(1.0, 0.0, 0.0), Map.of()))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("entityType");

        assertThatThrownBy(() -> service.storeVector("document", " ", "Invalid document",
            vector(1.0, 0.0, 0.0), Map.of()))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("entityId");

        assertThatThrownBy(() -> service.storeVector("document", "doc-invalid", "Invalid document",
            List.of(), Map.of()))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("non-empty embedding vector");

        assertThat(service.getVectorByEntity(null, "doc-invalid")).isEmpty();
        assertThat(service.getVectorByEntity("document", " ")).isEmpty();
        assertThat(service.getVectorsByEntityType(" ")).isEmpty();
        assertThat(service.getVectorCountByEntityType(" ")).isZero();
        assertThat(service.vectorExists(" ", "doc-invalid")).isFalse();
        assertThat(service.removeVector("document", " ")).isFalse();
        assertThat(service.clearVectorsByEntityType(" ")).isZero();
        assertThat(service.updateVector(" ", "document", "doc-invalid", "Invalid update",
            vector(1.0, 0.0, 0.0), Map.of())).isFalse();
        assertThat(service.updateVector("missing-vector", " ", "doc-invalid", "Invalid update",
            vector(1.0, 0.0, 0.0), Map.of())).isFalse();
    }

    private static List<Double> vector(Double... values) {
        return List.of(values);
    }

    private static Object resultEntityId(Map<String, Object> result) {
        return result.getOrDefault("entityId", result.get("id"));
    }

    record CapabilityExpectations(boolean supportsEfficientEntityTypeCount) {
    }
}
