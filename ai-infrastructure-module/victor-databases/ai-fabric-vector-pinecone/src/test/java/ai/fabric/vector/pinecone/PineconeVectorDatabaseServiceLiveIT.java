package ai.fabric.vector.pinecone;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.config.VectorDatabaseConfig;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class PineconeVectorDatabaseServiceLiveIT {

    private final List<String> cleanupEntityTypes = new ArrayList<>();
    private PineconeVectorDatabaseService service;

    @AfterEach
    void tearDown() {
        if (service == null) {
            return;
        }
        cleanupEntityTypes.forEach(entityType -> {
            try {
                service.clearVectorsByEntityType(entityType);
            } catch (Exception ignored) {
                // best-effort live cleanup
            }
        });
        service.close();
    }

    @Test
    void liveLifecycleHandlesEventualConsistencyAndMetadataFiltering() {
        String scope = uniqueScope();
        service = liveService(scope);

        String entityType = "pinecone_live_lifecycle";
        cleanupEntityTypes.add(entityType);
        service.clearVectorsByEntityType(entityType);

        String suite = "suite-" + scope;
        String primaryEntityId = "primary-" + scope;
        String excludedEntityId = "excluded-" + scope;
        int dimension = embeddingDimension(service);
        List<Double> primaryEmbedding = embedding(dimension, 0.17d);
        List<Double> excludedEmbedding = embedding(dimension, 0.31d);

        String primaryVectorId = service.storeVector(
            entityType,
            primaryEntityId,
            "Primary Pinecone live lifecycle vector",
            primaryEmbedding,
            Map.of("suite", suite, "group", "included", "priority", 1)
        );
        service.storeVector(
            entityType,
            excludedEntityId,
            "Excluded Pinecone live lifecycle vector",
            excludedEmbedding,
            Map.of("suite", suite + "-other", "group", "excluded", "priority", 2)
        );

        String expectedNamespace = PineconeVectorDatabaseService.scopedNamespace(entityType, scope);
        assertThat(primaryVectorId).isEqualTo(expectedNamespace + "::" + primaryEntityId);

        VectorRecord primary = awaitVector(entityType, primaryEntityId);
        assertThat(primary.getContent()).isEqualTo("Primary Pinecone live lifecycle vector");
        assertThat(primary.getEmbedding()).hasSize(dimension);
        assertThat(primary.getMetadata()).containsEntry("suite", suite).containsEntry("group", "included");

        Map<String, Object> metadataFilter = Map.of("suite", suite);
        AISearchResponse filteredSearch = awaitSearchResult(entityType, primaryEmbedding, metadataFilter, primaryEntityId);
        assertThat(filteredSearch.getResults())
            .extracting(row -> row.get("entityId"))
            .contains(primaryEntityId)
            .doesNotContain(excludedEntityId);
        assertThat(filteredSearch.getResults().get(0).get("metadata"))
            .isInstanceOfSatisfying(Map.class, metadata -> assertThat(metadata).containsEntry("suite", suite));

        boolean updated = service.updateVector(
            primaryVectorId,
            entityType,
            primaryEntityId,
            "Updated Pinecone live lifecycle vector",
            primaryEmbedding,
            Map.of("suite", suite, "group", "updated", "priority", 3)
        );
        assertThat(updated).isTrue();
        await("updated vector is visible through exact fetch", () -> service.getVectorByEntity(entityType, primaryEntityId)
            .filter(record -> "Updated Pinecone live lifecycle vector".equals(record.getContent()))
            .filter(record -> "updated".equals(String.valueOf(record.getMetadata().get("group"))))
            .isPresent());

        service.clearVectorsByEntityType(entityType);
        await("cleared vectors disappear from exact fetch", () ->
            service.getVectorByEntity(entityType, primaryEntityId).isEmpty()
                && service.getVectorByEntity(entityType, excludedEntityId).isEmpty());
    }

    @Test
    void liveSparseIndexRoundTripsSparseEmbeddingsWhenConfigured() {
        String scope = uniqueScope();
        service = liveService(scope);
        Assumptions.assumeTrue(isSparseIndex(service), "Pinecone live index is dense; sparse-index verification skipped");

        String entityType = "pinecone_live_sparse";
        cleanupEntityTypes.add(entityType);
        service.clearVectorsByEntityType(entityType);

        String suite = "sparse-" + scope;
        String entityId = "item-" + scope;
        List<Double> sparseEmbedding = List.of(0.0d, 1.5d, 0.0d, 2.5d);

        service.storeVector(
            entityType,
            entityId,
            "Sparse Pinecone live vector",
            sparseEmbedding,
            Map.of("suite", suite, "shape", "sparse")
        );

        VectorRecord stored = awaitVector(entityType, entityId);
        assertThat(stored.getEmbedding()).containsExactlyElementsOf(sparseEmbedding);
        assertThat(stored.getMetadata()).containsEntry("shape", "sparse");

        awaitSearchResult(entityType, sparseEmbedding, Map.of("suite", suite), entityId);
        assertThat(service.adminDiagnostics()).containsEntry("sparseIndexDetected", true);
    }

    private PineconeVectorDatabaseService liveService(String scope) {
        String apiKey = firstNonBlank(
            property("ai.providers.pinecone.api-key"),
            property("ai.providers.pinecone.apiKey"),
            property("ai.vector-db.pinecone.api-key"),
            env("AI_PROVIDERS_PINECONE_API_KEY"),
            env("PINECONE_API_KEY")
        );
        String apiHost = firstNonBlank(
            property("ai.providers.pinecone.api-host"),
            property("ai.providers.pinecone.apiHost"),
            property("ai.vector-db.pinecone.api-host"),
            env("AI_PROVIDERS_PINECONE_API_HOST"),
            env("PINECONE_API_HOST")
        );
        String indexName = firstNonBlank(
            property("ai.providers.pinecone.index-name"),
            property("ai.providers.pinecone.indexName"),
            property("ai.vector-db.pinecone.index-name"),
            env("AI_PROVIDERS_PINECONE_INDEX_NAME"),
            env("PINECONE_INDEX_NAME")
        );
        String environment = firstNonBlank(
            property("ai.providers.pinecone.environment"),
            property("ai.vector-db.pinecone.environment"),
            env("AI_PROVIDERS_PINECONE_ENVIRONMENT"),
            env("PINECONE_ENVIRONMENT")
        );

        requireLiveConfiguration(StringUtils.hasText(apiKey),
            "Set PINECONE_API_KEY or AI_PROVIDERS_PINECONE_API_KEY to run Pinecone live tests");
        requireLiveConfiguration(StringUtils.hasText(apiHost) || StringUtils.hasText(indexName),
            "Set PINECONE_API_HOST or PINECONE_INDEX_NAME to run Pinecone live tests");
        requireLiveConfiguration(StringUtils.hasText(apiHost) || StringUtils.hasText(environment),
            "Set PINECONE_API_HOST or PINECONE_ENVIRONMENT to run Pinecone live tests");

        AIProviderConfig providerConfig = new AIProviderConfig();
        AIProviderConfig.PineconeConfig pinecone = providerConfig.getPinecone();
        pinecone.setEnabled(true);
        pinecone.setApiKey(apiKey);
        pinecone.setApiHost(apiHost);
        pinecone.setIndexName(indexName);
        pinecone.setEnvironment(environment);
        pinecone.setNamespacePrefix(scope);
        pinecone.setDimensions(configuredDimension());

        VectorDatabaseConfig vectorDatabaseConfig = new VectorDatabaseConfig();
        vectorDatabaseConfig.getOperations().setAwaitClearConsistency(true);
        vectorDatabaseConfig.getOperations().setAwaitClearTimeoutMs(timeout().toMillis());

        return new PineconeVectorDatabaseService(providerConfig, vectorDatabaseConfig);
    }

    private VectorRecord awaitVector(String entityType, String entityId) {
        final VectorRecord[] holder = new VectorRecord[1];
        await("vector is visible through exact fetch", () -> {
            Optional<VectorRecord> record = service.getVectorByEntity(entityType, entityId);
            record.ifPresent(value -> holder[0] = value);
            return record.isPresent();
        });
        return holder[0];
    }

    private AISearchResponse awaitSearchResult(String entityType,
                                               List<Double> queryEmbedding,
                                               Map<String, Object> metadataFilter,
                                               String expectedEntityId) {
        final AISearchResponse[] holder = new AISearchResponse[1];
        await("metadata-filtered search returns expected vector", () -> {
            AISearchResponse response = service.search(
                queryEmbedding,
                AISearchRequest.builder()
                    .query("pinecone live verification")
                    .entityType(entityType)
                    .limit(10)
                    .threshold(0.0d)
                    .metadata(metadataFilter)
                    .build()
            );
            holder[0] = response;
            return response.getResults().stream()
                .anyMatch(row -> expectedEntityId.equals(String.valueOf(row.get("entityId"))));
        });
        return holder[0];
    }

    private static int embeddingDimension(PineconeVectorDatabaseService service) {
        Object dimension = service.getStatistics().get("dimension");
        if (dimension instanceof Number number) {
            int value = number.intValue();
            if (value > 0) {
                return value;
            }
        }
        return configuredDimension();
    }

    private static boolean isSparseIndex(PineconeVectorDatabaseService service) {
        Object dimension = service.getStatistics().get("dimension");
        return dimension instanceof Number number && number.intValue() == 0;
    }

    private static List<Double> embedding(int dimension, double seed) {
        int size = Math.max(1, dimension);
        List<Double> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            double value = ((i % 17) + 1) * seed;
            values.add(value);
        }
        return values;
    }

    private static int configuredDimension() {
        String value = firstNonBlank(
            property("ai.providers.pinecone.dimensions"),
            property("ai.vector-db.pinecone.dimensions"),
            env("AI_PROVIDERS_PINECONE_DIMENSIONS"),
            env("PINECONE_DIMENSIONS")
        );
        if (StringUtils.hasText(value)) {
            try {
                return Math.max(1, Integer.parseInt(value.trim()));
            } catch (NumberFormatException ignored) {
                // fall through to the common embedding dimension
            }
        }
        return 1536;
    }

    private static void await(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout().toNanos();
        Throwable lastFailure = null;

        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (Throwable ex) {
                lastFailure = ex;
            }
            sleep(Duration.ofMillis(750));
        }

        if (lastFailure != null) {
            fail(description + " did not become true before timeout", lastFailure);
        }
        fail(description + " did not become true before timeout");
    }

    private static Duration timeout() {
        String value = firstNonBlank(property("pinecone.live.timeout-ms"), env("PINECONE_LIVE_TIMEOUT_MS"));
        if (StringUtils.hasText(value)) {
            try {
                return Duration.ofMillis(Math.max(1_000L, Long.parseLong(value.trim())));
            } catch (NumberFormatException ignored) {
                // use default below
            }
        }
        return Duration.ofSeconds(90);
    }

    static void requireLiveConfiguration(boolean condition, String message) {
        if (condition) {
            return;
        }
        if (liveConfigurationRequired()) {
            fail(message + " because Pinecone live verification is required");
        }
        Assumptions.assumeTrue(false, message);
    }

    static boolean liveConfigurationRequired() {
        String value = firstNonBlank(property("pinecone.live.required"), env("PINECONE_LIVE_REQUIRED"));
        return StringUtils.hasText(value)
            && ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value));
    }

    private static String uniqueScope() {
        return "aifabriclive" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String property(String key) {
        return key != null ? System.getProperty(key) : null;
    }

    private static String env(String key) {
        return key != null ? System.getenv(key) : null;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Pinecone live verification", ex);
        }
    }
}
