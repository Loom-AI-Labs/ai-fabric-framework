package ai.fabric.vector.lucene;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.dto.VectorScanRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LuceneVectorDatabaseServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private LuceneVectorDatabaseService service;

    @AfterEach
    void cleanup() {
        if (service != null) {
            service.cleanup();
        }
    }

    @Test
    void storesSearchesAndReadsEscapedMetadata() throws Exception {
        service = createService();
        Map<String, Object> metadata = Map.of(
            "tier", "paid \"vip\"",
            "score", 42,
            "tags", List.of("quote \"one\"", "two")
        );

        String vectorId = service.storeVector("order", "order-1", "Red running shoes",
            vector(1.0, 0.0, 0.0), metadata);

        VectorRecord stored = service.getVector(vectorId).orElseThrow();
        assertThat(stored.getMetadata())
            .containsEntry("tier", "paid \"vip\"")
            .containsEntry("score", 42)
            .containsKey("raw");

        AISearchResponse response = service.search(vector(1.0, 0.0, 0.0), AISearchRequest.builder()
            .query("red shoes")
            .entityType("order")
            .limit(null)
            .threshold(null)
            .build());

        assertThat(response.getResults()).hasSize(1);
        Map<String, Object> result = response.getResults().get(0);
        assertThat(result).containsEntry("id", "order-1");

        Map<String, Object> parsedMetadata = OBJECT_MAPPER.readValue(
            result.get("metadata").toString(),
            new TypeReference<>() {}
        );
        assertThat(parsedMetadata)
            .containsEntry("tier", "paid \"vip\"")
            .containsEntry("score", 42);
        assertThat(parsedMetadata.get("tags")).asList().containsExactly("quote \"one\"", "two");
    }

    @Test
    void searchAppliesIndexedScalarMetadataFilters() {
        service = createService();
        service.storeVector("product", "public-1", "Public catalog item",
            vector(1.0, 0.0, 0.0), Map.of("visibility", "public", "featured", true, "rank", 7));
        service.storeVector("product", "private-1", "Private catalog item",
            vector(1.0, 0.0, 0.0), Map.of("visibility", "private", "featured", false, "rank", 2));

        AISearchResponse response = service.search(vector(1.0, 0.0, 0.0), AISearchRequest.builder()
            .query("catalog")
            .entityType("product")
            .metadata(Map.of("visibility", "public", "featured", true, "rank", 7))
            .limit(10)
            .threshold(0.0d)
            .build());

        assertThat(service.supportsSearchMetadataFiltering()).isTrue();
        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().getFirst()).containsEntry("id", "public-1");
    }

    @Test
    void searchAppliesMetadataFilterBeforeKnnCandidateSelection() {
        service = createService();
        for (int i = 0; i < 8; i++) {
            service.storeVector("guide", "other-" + i, "Guide from another workspace",
                vector(1.0, 0.0, 0.0), Map.of("workspaceId", "other-workspace"));
        }
        service.storeVector("guide", "current-guide", "Guide from the current workspace",
            vector(0.99, 0.1, 0.0), Map.of("workspaceId", "current-workspace"));

        AISearchResponse response = service.search(vector(1.0, 0.0, 0.0), AISearchRequest.builder()
            .query("workspace guide")
            .entityType("guide")
            .metadata(Map.of("workspaceId", "current-workspace"))
            .limit(1)
            .threshold(0.0d)
            .build());

        assertThat(response.getResults())
            .extracting(row -> row.get("id"))
            .containsExactly("current-guide");
    }

    @Test
    void adminDiagnosticsExposeStableCapabilityKeysAndIndexPath() {
        service = createService();

        assertThat(service.adminDiagnostics())
            .containsEntry("provider", "lucene")
            .containsEntry("persistent", true)
            .containsEntry("sharedStorage", false)
            .containsEntry("scopeType", "LOCAL_INDEX")
            .containsEntry("supportsVectorScan", true)
            .containsEntry("supportsSearchMetadataFiltering", true)
            .containsEntry("supportsScanMetadataFiltering", true)
            .containsEntry("supportsExactFetchById", true)
            .containsEntry("supportsClearByEntityType", true)
            .containsEntry("supportsEfficientEntityTypeCount", true)
            .containsEntry("metadataFilteredSearch", true)
            .containsEntry("metadataFilteredScan", true)
            .containsEntry("searchFilterMode", "lucene-indexed-metadata-query")
            .containsEntry("scanFilterMode", "lucene-indexed-metadata-query");
        assertThat(service.adminDiagnostics().get("rootResourceValue").toString())
            .contains(tempDir.toAbsolutePath().normalize().toString());
    }

    @Test
    void unsupportedMetadataFiltersFailClosedInsteadOfBroadeningResults() {
        service = createService();
        service.storeVector("product", "public-1", "Public catalog item",
            vector(1.0, 0.0, 0.0), Map.of("tags", List.of("public", "sale")));

        AISearchResponse response = service.search(vector(1.0, 0.0, 0.0), AISearchRequest.builder()
            .query("catalog")
            .entityType("product")
            .metadata(Map.of("tags", List.of("public", "sale")))
            .limit(10)
            .threshold(0.0d)
            .build());

        assertThat(response.getResults()).isEmpty();
    }

    @Test
    void searchSupportsLuceneSpecificDecimalMetadataEquality() {
        service = createService();
        service.storeVector("product", "exact-score", "Exact score item",
            vector(1.0, 0.0, 0.0), Map.of("qualityScore", 0.75d));
        service.storeVector("product", "different-score", "Different score item",
            vector(1.0, 0.0, 0.0), Map.of("qualityScore", 0.74d));

        AISearchResponse response = service.search(vector(1.0, 0.0, 0.0), AISearchRequest.builder()
            .query("catalog")
            .entityType("product")
            .metadata(Map.of("qualityScore", 0.75d))
            .limit(10)
            .threshold(0.0d)
            .build());

        assertThat(response.getResults())
            .extracting(row -> row.get("id"))
            .containsExactly("exact-score");
        assertThat(service.adminDiagnostics())
            .containsEntry("metadataFilterSubset", "scalar-string-boolean-integer-long-decimal");
    }

    @Test
    void scanSupportsLuceneSpecificDecimalMetadataEquality() {
        service = createService();
        service.storeVector("document", "doc-good", "Good quality document",
            vector(1.0, 0.0, 0.0), Map.of("qualityScore", 0.75d));
        service.storeVector("document", "doc-other", "Other quality document",
            vector(0.0, 1.0, 0.0), Map.of("qualityScore", 0.74d));

        VectorScanPage page = service.scan(VectorScanRequest.builder()
            .entityType("document")
            .metadataEquals(Map.of("qualityScore", 0.75d))
            .limit(10)
            .build());

        assertThat(page.getVectors())
            .extracting(VectorRecord::getEntityId)
            .containsExactly("doc-good");
    }

    @Test
    void removeVectorOnlyDeletesMatchingEntityTypeAndId() {
        service = createService();
        String productVectorId = service.storeVector("product", "shared-1", "Product profile",
            vector(1.0, 0.0, 0.0), Map.of("kind", "product"));
        String customerVectorId = service.storeVector("customer", "shared-1", "Customer profile",
            vector(0.0, 1.0, 0.0), Map.of("kind", "customer"));

        assertThat(service.removeVector("product", "shared-1")).isTrue();

        assertThat(service.getVector(productVectorId)).isEmpty();
        assertThat(service.getVector(customerVectorId)).isPresent();
        assertThat(service.getVectorByEntity("product", "shared-1")).isEmpty();
        assertThat(service.getVectorByEntity("customer", "shared-1")).isPresent();
    }

    @Test
    void updatePreservesCreatedAtAndRefreshesUpdatedAt() throws Exception {
        service = createService();
        String vectorId = service.storeVector("document", "doc-1", "Original content",
            vector(1.0, 0.0, 0.0), Map.of("source", "initial"));
        VectorRecord original = service.getVector(vectorId).orElseThrow();

        Thread.sleep(5L);

        boolean updated = service.updateVector(vectorId, "document", "doc-1", "Updated content",
            vector(0.9, 0.1, 0.0), Map.of("source", "updated"));

        assertThat(updated).isTrue();
        VectorRecord stored = service.getVector(vectorId).orElseThrow();
        assertThat(stored.getContent()).isEqualTo("Updated content");
        assertThat(stored.getMetadata()).containsEntry("source", "updated");
        assertThat(stored.getCreatedAt()).isEqualTo(original.getCreatedAt());
        assertThat(stored.getUpdatedAt()).isAfterOrEqualTo(original.getUpdatedAt());
    }

    @Test
    void scanFiltersByMetadataAndHonorsFieldInclusionFlags() {
        service = createService();
        service.storeVector("document", "doc-help", "Help article",
            vector(1.0, 0.0, 0.0), Map.of("source", "help"));
        service.storeVector("document", "doc-private", "Private note",
            vector(0.0, 1.0, 0.0), Map.of("source", "private"));

        VectorScanPage page = service.scan(VectorScanRequest.builder()
            .entityType("document")
            .metadataEquals(Map.of("source", "help"))
            .limit(10)
            .includeContent(false)
            .includeEmbedding(false)
            .includeMetadata(false)
            .build());

        assertThat(page.isHasMore()).isFalse();
        assertThat(page.getVectors()).hasSize(1);
        VectorRecord record = page.getVectors().get(0);
        assertThat(record.getEntityId()).isEqualTo("doc-help");
        assertThat(record.getContent()).isNull();
        assertThat(record.getEmbedding()).isNull();
        assertThat(record.getMetadata()).isNull();
    }

    private LuceneVectorDatabaseService createService() {
        LuceneVectorDatabaseService luceneService = new LuceneVectorDatabaseService(new AIProviderConfig());
        setField(luceneService, "indexPath", tempDir.resolve(UUID.randomUUID().toString()).toString());
        setField(luceneService, "similarityThreshold", 0.0d);
        setField(luceneService, "maxResults", 50);
        setField(luceneService, "cleanupOnClose", true);
        luceneService.initialize();
        return luceneService;
    }

    private static List<Double> vector(Double... values) {
        return List.of(values);
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to set field " + fieldName, e);
        }
    }
}
