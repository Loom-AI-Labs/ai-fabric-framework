package ai.fabric.vector.memory;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.dto.VectorScanRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class InMemoryVectorDatabaseServiceTest {

    private InMemoryVectorDatabaseService service;

    @BeforeEach
    void setUp() {
        service = new InMemoryVectorDatabaseService(new AIProviderConfig());
    }

    @Test
    void storeSearchAndFilterByMetadata() {
        String watchVectorId = service.storeVector(
            "product",
            "watch-1",
            "Luxury watch",
            List.of(1.0, 0.0),
            Map.of("category", "watches", "tenantId", "tenant-a")
        );
        service.storeVector(
            "product",
            "shoe-1",
            "Running shoe",
            List.of(0.0, 1.0),
            Map.of("category", "shoes", "tenantId", "tenant-a")
        );
        service.storeVector(
            "article",
            "watch-guide",
            "Watch buying guide",
            List.of(1.0, 0.0),
            Map.of("category", "watches", "tenantId", "tenant-a")
        );

        AISearchResponse response = service.search(
            List.of(1.0, 0.0),
            AISearchRequest.builder()
                .query("watch")
                .entityType("product")
                .limit(5)
                .threshold(0.5)
                .metadata(Map.of("category", "watches"))
                .build()
        );

        assertThat(response.getTotalResults()).isEqualTo(1);
        assertThat(response.getMaxScore()).isCloseTo(1.0, within(0.000001));
        assertThat(response.getRequestId()).isNotBlank();

        Map<String, Object> result = response.getResults().get(0);
        assertThat(result)
            .containsEntry("vectorId", watchVectorId)
            .containsEntry("id", "watch-1")
            .containsEntry("entityId", "watch-1")
            .containsEntry("entityType", "product")
            .containsEntry("vectorSpace", "product")
            .containsEntry("content", "Luxury watch");
        assertThat((Double) result.get("similarity")).isCloseTo(1.0, within(0.000001));
        assertThat((Map<String, Object>) result.get("metadata"))
            .containsEntry("category", "watches")
            .containsEntry("tenantId", "tenant-a")
            .containsKey("raw");
    }

    @Test
    void dimensionMismatchDoesNotReturnFalsePositiveAtZeroThreshold() {
        service.storeVector(
            "product",
            "three-dimensional",
            "Different embedding dimension",
            List.of(1.0, 0.0, 0.0),
            Map.of("category", "test")
        );

        AISearchResponse response = service.search(
            List.of(1.0, 0.0),
            AISearchRequest.builder()
                .query("dimension")
                .entityType("product")
                .limit(10)
                .threshold(0.0)
                .build()
        );

        assertThat(response.getResults()).isEmpty();
        assertThat(response.getTotalResults()).isZero();
        assertThat(response.getMaxScore()).isZero();
    }

    @Test
    void normalizesInvalidThresholdsAcrossSearchApis() {
        service.storeVector(
            "product",
            "watch-1",
            "Luxury watch",
            List.of(1.0, 0.0),
            Map.of("category", "watches")
        );

        AISearchResponse requestSearch = service.search(
            List.of(1.0, 0.0),
            AISearchRequest.builder()
                .query("watch")
                .entityType("product")
                .limit(5)
                .threshold(Double.POSITIVE_INFINITY)
                .build()
        );
        AISearchResponse entityTypeSearch = service.searchByEntityType(
            List.of(1.0, 0.0),
            "product",
            5,
            Double.NaN
        );

        assertThat(requestSearch.getResults()).hasSize(1);
        assertThat(entityTypeSearch.getResults()).hasSize(1);
    }

    @Test
    void ignoresNonFiniteVectorsDuringSearch() {
        service.storeVector(
            "product",
            "valid",
            "Valid embedding",
            List.of(1.0, 0.0),
            Map.of()
        );
        service.storeVector(
            "product",
            "nan",
            "Invalid embedding",
            Arrays.asList(Double.NaN, 0.0),
            Map.of()
        );

        AISearchResponse response = service.search(
            List.of(1.0, 0.0),
            AISearchRequest.builder()
                .query("embedding")
                .entityType("product")
                .limit(10)
                .threshold(0.0)
                .build()
        );
        AISearchResponse invalidQuery = service.search(
            Arrays.asList(null, 0.0),
            AISearchRequest.builder()
                .query("embedding")
                .entityType("product")
                .limit(10)
                .threshold(0.0)
                .build()
        );

        assertThat(response.getResults())
            .extracting(result -> result.get("entityId"))
            .containsExactly("valid");
        assertThat(invalidQuery.getResults()).isEmpty();
    }

    @Test
    void protectsStoredVectorsFromCallerMutation() {
        List<Double> embedding = new ArrayList<>(List.of(1.0, 0.0));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("category", "watches");

        String vectorId = service.storeVector("product", "watch-1", "Luxury watch", embedding, metadata);

        embedding.set(0, 0.0);
        metadata.put("category", "mutated");

        VectorRecord stored = service.getVector(vectorId).orElseThrow();
        assertThat(stored.getEmbedding()).containsExactly(1.0, 0.0);
        assertThat(stored.getMetadata()).containsEntry("category", "watches");

        stored.getEmbedding().set(0, 0.0);
        stored.getMetadata().put("category", "changed-from-read-model");

        VectorRecord reread = service.getVector(vectorId).orElseThrow();
        assertThat(reread.getEmbedding()).containsExactly(1.0, 0.0);
        assertThat(reread.getMetadata()).containsEntry("category", "watches");

        Map<String, Object> resultMetadata = (Map<String, Object>) service.search(
            List.of(1.0, 0.0),
            AISearchRequest.builder()
                .query("watch")
                .entityType("product")
                .limit(1)
                .threshold(0.5)
                .build()
        ).getResults().get(0).get("metadata");
        resultMetadata.put("category", "changed-from-result");

        assertThat(service.getVector(vectorId).orElseThrow().getMetadata())
            .containsEntry("category", "watches");
    }

    @Test
    void scanPaginatesMetadataFilterAndCanOmitHeavyFields() {
        service.storeVector("product", "p-1", "First product", List.of(1.0, 0.0), Map.of("tenant", "t1"));
        service.storeVector("product", "p-2", "Second product", List.of(0.8, 0.2), Map.of("tenant", "t1"));
        service.storeVector("product", "p-3", "Other tenant product", List.of(0.0, 1.0), Map.of("tenant", "t2"));

        VectorScanPage firstPage = service.scan(VectorScanRequest.builder()
            .entityType("product")
            .metadataEquals(Map.of("tenant", "t1"))
            .limit(1)
            .includeContent(false)
            .includeEmbedding(false)
            .includeMetadata(true)
            .build());

        assertThat(firstPage.getVectors()).hasSize(1);
        assertThat(firstPage.isHasMore()).isTrue();
        assertThat(firstPage.getNextCursor()).isNotBlank();
        assertThat(firstPage.getVectors().get(0).getContent()).isNull();
        assertThat(firstPage.getVectors().get(0).getEmbedding()).isNull();
        assertThat(firstPage.getVectors().get(0).getMetadata()).containsEntry("tenant", "t1");

        VectorScanPage secondPage = service.scan(VectorScanRequest.builder()
            .entityType("product")
            .metadataEquals(Map.of("tenant", "t1"))
            .limit(1)
            .cursor(firstPage.getNextCursor())
            .includeContent(false)
            .includeEmbedding(false)
            .includeMetadata(true)
            .build());

        assertThat(secondPage.getVectors()).hasSize(1);
        assertThat(secondPage.isHasMore()).isFalse();
        assertThat(secondPage.getNextCursor()).isNull();

        Set<String> entityIds = List.of(firstPage, secondPage).stream()
            .flatMap(page -> page.getVectors().stream())
            .map(VectorRecord::getEntityId)
            .collect(Collectors.toSet());
        assertThat(entityIds).containsExactlyInAnyOrder("p-1", "p-2");
    }

    @Test
    void updateBatchRemoveAndClearMaintainCounts() {
        List<String> vectorIds = service.batchStoreVectors(List.of(
            VectorRecord.builder()
                .entityType("product")
                .entityId("p-1")
                .content("First product")
                .embedding(List.of(1.0, 0.0))
                .metadata(Map.of("category", "one"))
                .build(),
            VectorRecord.builder()
                .entityType("product")
                .entityId("p-2")
                .content("Second product")
                .embedding(List.of(0.0, 1.0))
                .metadata(Map.of("category", "two"))
                .build(),
            VectorRecord.builder()
                .entityType("article")
                .entityId("a-1")
                .content("Article")
                .embedding(List.of(0.5, 0.5))
                .metadata(Map.of("category", "docs"))
                .build()
        ));

        assertThat(vectorIds).hasSize(3);
        assertThat(service.getVectorCountByEntityType("product")).isEqualTo(2);
        assertThat(service.vectorExists("product", "p-1")).isTrue();

        int updated = service.batchUpdateVectors(List.of(
            VectorRecord.builder()
                .vectorId(vectorIds.get(0))
                .entityType("product")
                .entityId("p-1")
                .content("Updated first product")
                .embedding(List.of(0.9, 0.1))
                .metadata(Map.of("category", "updated"))
                .build(),
            VectorRecord.builder()
                .vectorId("missing")
                .entityType("product")
                .entityId("missing")
                .content("Missing product")
                .embedding(List.of(0.0, 1.0))
                .metadata(Map.of())
                .build()
        ));

        assertThat(updated).isEqualTo(1);
        assertThat(service.getVector(vectorIds.get(0)).orElseThrow())
            .extracting(VectorRecord::getContent, VectorRecord::getVersion)
            .containsExactly("Updated first product", 2);

        assertThat(service.batchRemoveVectors(List.of(vectorIds.get(1), "missing"))).isEqualTo(1);
        assertThat(service.removeVector("product", "p-1")).isTrue();
        assertThat(service.clearVectorsByEntityType("article")).isEqualTo(1);
        assertThat(service.clearVectors()).isZero();
    }

    @Test
    void batchAndIdOperationsIgnoreNullOrBlankInputs() {
        List<VectorRecord> records = new ArrayList<>();
        records.add(null);
        records.add(VectorRecord.builder()
            .entityType("product")
            .entityId("p-1")
            .content("First product")
            .embedding(List.of(1.0, 0.0))
            .metadata(Map.of())
            .build());

        List<String> vectorIds = service.batchStoreVectors(records);

        assertThat(service.batchStoreVectors(null)).isEmpty();
        assertThat(vectorIds).hasSize(1);
        assertThat(service.getVector(null)).isEmpty();
        assertThat(service.updateVector(" ", "product", "p-1", "Ignored", List.of(0.0, 1.0), Map.of()))
            .isFalse();
        assertThat(service.removeVectorById(" ")).isFalse();

        int updated = service.batchUpdateVectors(Arrays.asList(
            null,
            VectorRecord.builder().vectorId("").build(),
            VectorRecord.builder()
                .vectorId(vectorIds.get(0))
                .entityType("product")
                .entityId("p-1")
                .content("Updated product")
                .embedding(List.of(0.9, 0.1))
                .metadata(Map.of())
                .build()
        ));

        assertThat(updated).isEqualTo(1);
        assertThat(service.batchUpdateVectors(null)).isZero();
        assertThat(service.batchRemoveVectors(Arrays.asList(null, " ", "missing", vectorIds.get(0)))).isEqualTo(1);
        assertThat(service.batchRemoveVectors(null)).isZero();
    }

    @Test
    void statisticsAndDiagnosticsDescribeMemoryStore() {
        service.storeVector("product", "p-1", "Product", List.of(1.0, 0.0), Map.of());
        service.storeVector("article", "a-1", "Article", List.of(0.0, 1.0), Map.of());

        assertThat(service.getStatistics())
            .containsEntry("type", "memory")
            .containsEntry("totalVectors", 2);
        assertThat((Map<String, Long>) service.getStatistics().get("entityTypeCounts"))
            .containsEntry("product", 1L)
            .containsEntry("article", 1L);

        assertThat(service.adminDiagnostics())
            .containsEntry("provider", "memory")
            .containsEntry("persistent", false)
            .containsEntry("sharedStorage", false)
            .containsEntry("supportsVectorScan", true)
            .containsEntry("supportsMetadataFiltering", true)
            .containsEntry("totalVectors", 2);
    }
}
