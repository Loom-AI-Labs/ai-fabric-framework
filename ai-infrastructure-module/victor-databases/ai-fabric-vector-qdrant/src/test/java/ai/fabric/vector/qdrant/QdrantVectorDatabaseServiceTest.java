package ai.fabric.vector.qdrant;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.config.VectorDatabaseConfig;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.dto.VectorScanRequest;
import ai.fabric.exception.AIServiceException;
import ai.fabric.vector.VectorProviderMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.Futures;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QdrantVectorDatabaseServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void scopedCollectionNamePrependsConfiguredPrefix() {
        assertThat(QdrantVectorDatabaseService.scopedCollectionName("product", "customer_a__tenant_b__"))
            .isEqualTo("customer_a__tenant_b__product");
    }

    @Test
    void scopedCollectionNameLeavesEntityTypeUntouchedWithoutPrefix() {
        assertThat(QdrantVectorDatabaseService.scopedCollectionName("product", ""))
            .isEqualTo("product");
    }

    @Test
    void normalizesSearchLimitAndThresholdEdges() {
        assertThat(QdrantVectorDatabaseService.normalizeSearchLimit(null)).isEqualTo(10);
        assertThat(QdrantVectorDatabaseService.normalizeSearchLimit(-4)).isEqualTo(10);
        assertThat(QdrantVectorDatabaseService.normalizeSearchLimit(500)).isEqualTo(100);
        assertThat(QdrantVectorDatabaseService.normalizeSearchLimit(25)).isEqualTo(25);

        assertThat(QdrantVectorDatabaseService.normalizeScoreThreshold(null)).isZero();
        assertThat(QdrantVectorDatabaseService.normalizeScoreThreshold(Double.NaN)).isZero();
        assertThat(QdrantVectorDatabaseService.normalizeScoreThreshold(Double.POSITIVE_INFINITY)).isZero();
        assertThat(QdrantVectorDatabaseService.normalizeScoreThreshold(-0.4d)).isZero();
        assertThat(QdrantVectorDatabaseService.normalizeScoreThreshold(1.4d)).isEqualTo(1.0d);
        assertThat(QdrantVectorDatabaseService.normalizeScoreThreshold(0.42d)).isEqualTo(0.42d);
    }

    @Test
    void adminDiagnosticsExposeResolvedCollectionScope() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.QdrantConfig qdrant = config.getQdrant();
        qdrant.setEnabled(true);
        qdrant.setHost("qdrant.internal");
        qdrant.setCollectionPrefix("customer_a__tenant_b__");

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config);

        assertThat(service.adminDiagnostics())
            .containsEntry("provider", "qdrant")
            .containsEntry("sharedStorage", true)
            .containsEntry("scopeType", "COLLECTION_PREFIX")
            .containsEntry("rootResourceValue", "qdrant.internal")
            .containsEntry("scopePrefix", "customer_a__tenant_b__")
            .containsEntry("scopePattern", "customer_a__tenant_b__<entity_type>")
            .containsEntry("supportsVectorScan", true)
            .containsEntry("supportsSearchMetadataFiltering", true)
            .containsEntry("supportsScanMetadataFiltering", true)
            .containsEntry("supportsExactFetchById", true)
            .containsEntry("supportsClearByEntityType", true)
            .containsEntry("supportsEfficientEntityTypeCount", true)
            .containsEntry("metadataFilteredSearch", true)
            .containsEntry("metadataFilteredScan", true)
            .containsEntry("searchFilterMode", "qdrant-payload-filter-with-client-side-fallback")
            .containsEntry("scanFilterMode", "qdrant-payload-filter")
            .containsEntry("requiredPayloadIndexFields", List.of("knowledgeSourceHandleRef"))
            .containsEntry("payloadIndexReadinessSource", "lazy-cache")
            .containsEntry("verifiedPayloadIndexes", List.of())
            .containsEntry("payloadIndexesSeenMissing", List.of())
            .containsEntry("payloadIndexCreateAttempts", Map.of())
            .containsEntry("payloadIndexCreateFailures", Map.of())
            .containsEntry("metadataFilterFallbacks", Map.of());
    }

    @Test
    void restTransportStoresVectorWhenPreferGrpcIsFalse() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        List<String> upsertBodies = new CopyOnWriteArrayList<>();
        List<String> apiKeyHeaders = new CopyOnWriteArrayList<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            calls.add(method + " " + path);
            apiKeyHeaders.add(exchange.getRequestHeaders().getFirst("api-key"));

            if ("GET".equals(method) && "/collections".equals(path)) {
                writeJson(exchange, 200, "{\"result\":{\"collections\":[]},\"status\":\"ok\"}");
                return;
            }
            if ("PUT".equals(method) && "/collections/customer_a__tenant_b__product".equals(path)) {
                writeJson(exchange, 200, "{\"result\":true,\"status\":\"ok\"}");
                return;
            }
            if ("GET".equals(method) && "/collections/customer_a__tenant_b__product".equals(path)) {
                writeJson(exchange, 200, "{\"result\":{\"payload_schema\":{}},\"status\":\"ok\"}");
                return;
            }
            if ("PUT".equals(method) && "/collections/customer_a__tenant_b__product/index".equals(path)) {
                writeJson(exchange, 200, "{\"result\":{\"operation_id\":1,\"status\":\"completed\"},\"status\":\"ok\"}");
                return;
            }
            if ("PUT".equals(method) && "/collections/customer_a__tenant_b__product/points".equals(path)) {
                upsertBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                writeJson(exchange, 200, "{\"result\":{\"operation_id\":2,\"status\":\"completed\"},\"status\":\"ok\"}");
                return;
            }
            writeJson(exchange, 404, "{\"status\":\"error\",\"result\":null}");
        });
        server.start();

        try {
            AIProviderConfig config = baseConfig();
            AIProviderConfig.QdrantConfig qdrant = config.getQdrant();
            qdrant.setHost("http://127.0.0.1:" + server.getAddress().getPort());
            qdrant.setApiKey("rest-secret");
            qdrant.setPreferGrpc(false);

            QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config);
            String vectorId = service.storeVector(
                "product",
                "commerce://resource/Product/1",
                "Waterproof shell jacket",
                List.of(0.1d, 0.2d),
                java.util.Map.of("knowledgeSourceHandleRef", "plugin/mkp-data-commerce-catalog")
            );

            assertThat(vectorId).isNotBlank();
            assertThat(calls).contains(
                "GET /collections",
                "PUT /collections/customer_a__tenant_b__product",
                "GET /collections/customer_a__tenant_b__product",
                "PUT /collections/customer_a__tenant_b__product/index",
                "PUT /collections/customer_a__tenant_b__product/points"
            );
            assertThat(apiKeyHeaders).contains("rest-secret");
            assertThat(upsertBodies).hasSize(1);
            JsonNode upsert = OBJECT_MAPPER.readTree(upsertBodies.getFirst());
            JsonNode point = upsert.path("points").get(0);
            assertThat(point.path("payload").path("entityType").asText()).isEqualTo("product");
            assertThat(point.path("payload").path("entityId").asText()).isEqualTo("commerce://resource/Product/1");
            assertThat(point.path("payload").path("knowledgeSourceHandleRef").asText()).isEqualTo("plugin/mkp-data-commerce-catalog");
            assertThat(point.path("payload").path("_indexedCreatedAt").asText()).isNotBlank();
            assertThat(point.path("payload").path("_indexedUpdatedAt").asText()).isNotBlank();
            assertThat(point.path("vector")).hasSize(2);

            assertThat(service.adminDiagnostics())
                .containsEntry("transport", "rest")
                .containsEntry("verifiedPayloadIndexes",
                    List.of("customer_a__tenant_b__product::knowledgeSourceHandleRef"))
                .containsEntry("payloadIndexCreateAttempts",
                    Map.of("customer_a__tenant_b__product::knowledgeSourceHandleRef", 1))
                .containsEntry("payloadIndexesSeenMissing", List.of())
                .containsEntry("payloadIndexCreateFailures", Map.of());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void restSearchNormalizesLimitBeforeSendingRequest() throws Exception {
        List<String> searchBodies = new CopyOnWriteArrayList<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("GET".equals(method) && "/collections".equals(path)) {
                writeJson(exchange, 200,
                    "{\"result\":{\"collections\":[{\"name\":\"customer_a__tenant_b__faq-article\"}]},\"status\":\"ok\"}");
                return;
            }
            if ("GET".equals(method) && "/collections/customer_a__tenant_b__faq-article".equals(path)) {
                writeJson(exchange, 200,
                    "{\"result\":{\"payload_schema\":{\"knowledgeSourceHandleRef\":{}}},\"status\":\"ok\"}");
                return;
            }
            if ("POST".equals(method) && "/collections/customer_a__tenant_b__faq-article/points/search".equals(path)) {
                searchBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                writeJson(exchange, 200, """
                    {"result":[{"id":"16d5a5d3-8c90-41d1-8c7f-17d4bd84615f","score":0.25,
                    "payload":{"entityType":"faq-article","entityId":"kb://reset","content":"Reset instructions"},
                    "vector":[0.1,0.2]}],"status":"ok"}""");
                return;
            }
            writeJson(exchange, 404, "{\"status\":\"error\",\"result\":null}");
        });
        server.start();

        try {
            AIProviderConfig config = baseConfig();
            AIProviderConfig.QdrantConfig qdrant = config.getQdrant();
            qdrant.setHost("http://127.0.0.1:" + server.getAddress().getPort());
            qdrant.setPreferGrpc(false);

            QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config);

            AISearchResponse response = service.search(List.of(0.1d, 0.2d), AISearchRequest.builder()
                .query("reset password")
                .entityType("faq-article")
                .limit(-100)
                .threshold(Double.NaN)
                .build());

            assertThat(response.getResults()).hasSize(1);
            assertThat(searchBodies).hasSize(1);
            JsonNode searchBody = OBJECT_MAPPER.readTree(searchBodies.getFirst());
            assertThat(searchBody.path("limit").asInt()).isEqualTo(10);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void restSearchPreservesEmptyStringMetadataFilterValue() throws Exception {
        List<String> searchBodies = new CopyOnWriteArrayList<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("GET".equals(method) && "/collections".equals(path)) {
                writeJson(exchange, 200,
                    "{\"result\":{\"collections\":[{\"name\":\"customer_a__tenant_b__faq-article\"}]},\"status\":\"ok\"}");
                return;
            }
            if ("GET".equals(method) && "/collections/customer_a__tenant_b__faq-article".equals(path)) {
                writeJson(exchange, 200,
                    "{\"result\":{\"payload_schema\":{\"knowledgeSourceHandleRef\":{}}},\"status\":\"ok\"}");
                return;
            }
            if ("POST".equals(method) && "/collections/customer_a__tenant_b__faq-article/points/search".equals(path)) {
                searchBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                writeJson(exchange, 200, "{\"result\":[],\"status\":\"ok\"}");
                return;
            }
            writeJson(exchange, 404, "{\"status\":\"error\",\"result\":null}");
        });
        server.start();

        try {
            AIProviderConfig config = baseConfig();
            AIProviderConfig.QdrantConfig qdrant = config.getQdrant();
            qdrant.setHost("http://127.0.0.1:" + server.getAddress().getPort());
            qdrant.setPreferGrpc(false);

            QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config);

            service.search(List.of(0.1d, 0.2d), AISearchRequest.builder()
                .query("reset password")
                .entityType("faq-article")
                .limit(5)
                .metadata(Map.of("status", ""))
                .build());

            assertThat(searchBodies).hasSize(1);
            JsonNode must = OBJECT_MAPPER.readTree(searchBodies.getFirst())
                .path("filter")
                .path("must");
            assertThat(must).hasSize(1);
            assertThat(must.get(0).path("key").asText()).isEqualTo("status");
            assertThat(must.get(0).path("match").has("value")).isTrue();
            assertThat(must.get(0).path("match").path("value").asText()).isEqualTo("");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void searchEnsuresKnowledgeSourceHandlePayloadIndexForExistingCollection() {
        AIProviderConfig config = baseConfig();
        QdrantClient client = mock(QdrantClient.class);
        when(client.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of("customer_a__tenant_b__faq-article")));
        when(client.getCollectionInfoAsync("customer_a__tenant_b__faq-article"))
            .thenReturn(Futures.immediateFuture(Collections.CollectionInfo.newBuilder().build()));
        when(client.createPayloadIndexAsync(
            eq("customer_a__tenant_b__faq-article"),
            eq("knowledgeSourceHandleRef"),
            eq(Collections.PayloadSchemaType.Keyword),
            isNull(),
            eq(true),
            isNull(),
            isNull()
        )).thenReturn(Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));
        when(client.searchAsync(any())).thenReturn(Futures.immediateFuture(List.of()));

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);

        service.search(List.of(0.1d, 0.2d), AISearchRequest.builder()
            .query("reset password")
            .entityType("faq-article")
            .limit(5)
            .build());

        verify(client).createPayloadIndexAsync(
            eq("customer_a__tenant_b__faq-article"),
            eq("knowledgeSourceHandleRef"),
            eq(Collections.PayloadSchemaType.Keyword),
            isNull(),
            eq(true),
            isNull(),
            isNull()
        );
        assertThat(service.adminDiagnostics())
            .containsEntry("transport", "grpc")
            .containsEntry("verifiedPayloadIndexes",
                List.of("customer_a__tenant_b__faq-article::knowledgeSourceHandleRef"))
            .containsEntry("payloadIndexCreateAttempts",
                Map.of("customer_a__tenant_b__faq-article::knowledgeSourceHandleRef", 1))
            .containsEntry("payloadIndexesSeenMissing", List.of())
            .containsEntry("payloadIndexCreateFailures", Map.of());
    }

    @Test
    void grpcPayloadIndexCreationFailureIsExposedInDiagnostics() {
        AIProviderConfig config = baseConfig();
        QdrantClient client = mock(QdrantClient.class);
        String collection = "customer_a__tenant_b__faq-article";
        String cacheKey = collection + "::knowledgeSourceHandleRef";
        when(client.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of(collection)));
        when(client.getCollectionInfoAsync(collection))
            .thenReturn(Futures.immediateFuture(Collections.CollectionInfo.newBuilder().build()));
        when(client.createPayloadIndexAsync(
            eq(collection),
            eq("knowledgeSourceHandleRef"),
            eq(Collections.PayloadSchemaType.Keyword),
            isNull(),
            eq(true),
            isNull(),
            isNull()
        )).thenReturn(Futures.immediateFailedFuture(new RuntimeException("permission denied")));

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);

        assertThatThrownBy(() -> service.search(List.of(0.1d, 0.2d), AISearchRequest.builder()
            .query("reset password")
            .entityType("faq-article")
            .limit(5)
            .build()))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("create payload index");

        Map<String, Object> diagnostics = service.adminDiagnostics();
        assertThat(diagnostics.get("verifiedPayloadIndexes")).isEqualTo(List.of());
        assertThat(diagnostics.get("payloadIndexesSeenMissing")).isEqualTo(List.of(cacheKey));
        assertThat(diagnostics.get("payloadIndexCreateAttempts")).isEqualTo(Map.of(cacheKey, 1));
        assertThat(diagnostics.get("payloadIndexCreateFailures")).isInstanceOfSatisfying(Map.class, failures -> {
            assertThat(failures).containsKey(cacheKey);
            assertThat(String.valueOf(failures.get(cacheKey))).contains("permission denied");
        });
    }

    @Test
    void grpcSearchClampsProviderLimit() {
        AIProviderConfig config = baseConfig();
        QdrantClient client = mock(QdrantClient.class);
        when(client.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of("customer_a__tenant_b__faq-article")));
        when(client.getCollectionInfoAsync("customer_a__tenant_b__faq-article"))
            .thenReturn(Futures.immediateFuture(
                Collections.CollectionInfo.newBuilder()
                    .putPayloadSchema("knowledgeSourceHandleRef", Collections.PayloadSchemaInfo.getDefaultInstance())
                    .build()
            ));
        when(client.searchAsync(any())).thenReturn(Futures.immediateFuture(List.of()));

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);

        service.search(List.of(0.1d, 0.2d), AISearchRequest.builder()
            .query("reset password")
            .entityType("faq-article")
            .limit(500)
            .build());

        ArgumentCaptor<Points.SearchPoints> searchCaptor = ArgumentCaptor.forClass(Points.SearchPoints.class);
        verify(client).searchAsync(searchCaptor.capture());
        assertThat(searchCaptor.getValue().getLimit()).isEqualTo(100);
    }

    @Test
    void grpcSearchPreservesEmptyStringMetadataFilterValue() {
        AIProviderConfig config = baseConfig();
        QdrantClient client = mock(QdrantClient.class);
        when(client.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of("customer_a__tenant_b__faq-article")));
        when(client.getCollectionInfoAsync("customer_a__tenant_b__faq-article"))
            .thenReturn(Futures.immediateFuture(
                Collections.CollectionInfo.newBuilder()
                    .putPayloadSchema("knowledgeSourceHandleRef", Collections.PayloadSchemaInfo.getDefaultInstance())
                    .build()
            ));
        when(client.searchAsync(any())).thenReturn(Futures.immediateFuture(List.of()));

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);

        service.search(List.of(0.1d, 0.2d), AISearchRequest.builder()
            .query("reset password")
            .entityType("faq-article")
            .limit(5)
            .metadata(Map.of("status", ""))
            .build());

        ArgumentCaptor<Points.SearchPoints> searchCaptor = ArgumentCaptor.forClass(Points.SearchPoints.class);
        verify(client).searchAsync(searchCaptor.capture());
        Points.SearchPoints search = searchCaptor.getValue();

        assertThat(search.hasFilter()).isTrue();
        assertThat(search.getFilter().getMustList()).hasSize(1);
        Common.FieldCondition field = search.getFilter().getMust(0).getField();
        assertThat(field.getKey()).isEqualTo("status");
        assertThat(field.hasMatch()).isTrue();
        assertThat(field.getMatch().hasKeyword()).isTrue();
        assertThat(field.getMatch().getKeyword()).isEqualTo("");
    }

    @Test
    void searchSkipsKnowledgeSourceHandlePayloadIndexCreationWhenSchemaAlreadyExists() {
        AIProviderConfig config = baseConfig();
        QdrantClient client = mock(QdrantClient.class);
        when(client.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of("customer_a__tenant_b__faq-article")));
        when(client.getCollectionInfoAsync("customer_a__tenant_b__faq-article"))
            .thenReturn(Futures.immediateFuture(
                Collections.CollectionInfo.newBuilder()
                    .putPayloadSchema("knowledgeSourceHandleRef", Collections.PayloadSchemaInfo.getDefaultInstance())
                    .build()
            ));
        when(client.searchAsync(any())).thenReturn(Futures.immediateFuture(List.of()));

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);

        service.search(List.of(0.1d, 0.2d), AISearchRequest.builder()
            .query("reset password")
            .entityType("faq-article")
            .limit(5)
            .build());

        verify(client, never()).createPayloadIndexAsync(
            eq("customer_a__tenant_b__faq-article"),
            eq("knowledgeSourceHandleRef"),
            eq(Collections.PayloadSchemaType.Keyword),
            isNull(),
            eq(true),
            isNull(),
            isNull()
        );
    }

    @Test
    void searchFallsBackToUnfilteredQueryWhenPayloadIndexIsStillMissing() {
        UUID vectorId = UUID.fromString("16d5a5d3-8c90-41d1-8c7f-17d4bd84615f");
        UUID filteredOutVectorId = UUID.fromString("26d5a5d3-8c90-41d1-8c7f-17d4bd84615f");
        AIProviderConfig config = baseConfig();
        QdrantClient client = mock(QdrantClient.class);
        when(client.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of("customer_a__tenant_b__faq-article")));
        when(client.getCollectionInfoAsync("customer_a__tenant_b__faq-article"))
            .thenReturn(Futures.immediateFuture(Collections.CollectionInfo.newBuilder().build()));
        when(client.createPayloadIndexAsync(
            eq("customer_a__tenant_b__faq-article"),
            eq("knowledgeSourceHandleRef"),
            eq(Collections.PayloadSchemaType.Keyword),
            isNull(),
            eq(true),
            isNull(),
            isNull()
        )).thenReturn(Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));
        when(client.searchAsync(any()))
            .thenReturn(
                Futures.immediateFailedFuture(new RuntimeException(
                    "INVALID_ARGUMENT: Bad request: Index required but not found for \"knowledgeSourceHandleRef\""
                )),
                Futures.immediateFuture(List.of(Points.ScoredPoint.newBuilder()
                    .setId(PointIdFactory.id(vectorId))
                    .setScore(0.92f)
                    .putPayload("entityType", ValueFactory.value("faq-article"))
                    .putPayload("entityId", ValueFactory.value("kb://reset"))
                    .putPayload("content", ValueFactory.value("Reset instructions"))
                    .putPayload("knowledgeSourceHandleRef", ValueFactory.value("plugin/mkp-data-help-center"))
                    .build(),
                    Points.ScoredPoint.newBuilder()
                        .setId(PointIdFactory.id(filteredOutVectorId))
                        .setScore(0.91f)
                        .putPayload("entityType", ValueFactory.value("faq-article"))
                        .putPayload("entityId", ValueFactory.value("kb://other"))
                        .putPayload("content", ValueFactory.value("Other instructions"))
                        .putPayload("knowledgeSourceHandleRef", ValueFactory.value("plugin/other-source"))
                        .build()))
            );

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);

        try {
            AISearchResponse response = service.search(List.of(0.1d, 0.2d), AISearchRequest.builder()
                .query("reset password")
                .entityType("faq-article")
                .limit(5)
                .metadata(java.util.Map.of("knowledgeSourceHandleRef", "plugin/mkp-data-help-center"))
                .build());

            assertThat(response.getResults()).hasSize(1);
            Map<String, Object> row = response.getResults().getFirst();
            assertThat(row).containsEntry("entityId", "kb://reset");
            assertThat(row).containsEntry("metadataFilterFallback", true);
            assertThat(row.get("metadata")).isInstanceOfSatisfying(Map.class,
                metadata -> assertThat(metadata)
                    .containsEntry("knowledgeSourceHandleRef", "plugin/mkp-data-help-center")
                    .containsEntry("metadataFilterFallback", true));
            assertThat(service.adminDiagnostics())
                .containsEntry("metadataFilterFallbacks",
                    Map.of("customer_a__tenant_b__faq-article", 1));
            assertThat(registry.counter(
                VectorProviderMetrics.FALLBACK_COUNTER,
                "provider", "qdrant",
                "operation", "search",
                "reason", "missing_payload_index"
            ).count()).isEqualTo(1.0d);
            verify(client, atLeast(2)).searchAsync(any());
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    @Test
    void restSearchMarksResultsWhenPayloadIndexFallbackIsUsed() throws Exception {
        List<String> searchBodies = new CopyOnWriteArrayList<>();
        AtomicInteger searchCount = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("GET".equals(method) && "/collections".equals(path)) {
                writeJson(exchange, 200,
                    "{\"result\":{\"collections\":[{\"name\":\"customer_a__tenant_b__faq-article\"}]},\"status\":\"ok\"}");
                return;
            }
            if ("GET".equals(method) && "/collections/customer_a__tenant_b__faq-article".equals(path)) {
                writeJson(exchange, 200,
                    "{\"result\":{\"payload_schema\":{}},\"status\":\"ok\"}");
                return;
            }
            if ("PUT".equals(method) && "/collections/customer_a__tenant_b__faq-article/index".equals(path)) {
                writeJson(exchange, 200, "{\"result\":{},\"status\":\"ok\"}");
                return;
            }
            if ("POST".equals(method) && "/collections/customer_a__tenant_b__faq-article/points/search".equals(path)) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                searchBodies.add(body);
                if (searchCount.incrementAndGet() == 1) {
                    writeJson(exchange, 400,
                        "{\"status\":{\"error\":\"Index required but not found for \\\"knowledgeSourceHandleRef\\\"\"}}");
                    return;
                }
                writeJson(exchange, 200, """
                    {"result":[{"id":"16d5a5d3-8c90-41d1-8c7f-17d4bd84615f","score":0.92,
                    "payload":{"entityType":"faq-article","entityId":"kb://reset","content":"Reset instructions",
                    "knowledgeSourceHandleRef":"plugin/mkp-data-help-center"},
                    "vector":[0.1,0.2]},
                    {"id":"26d5a5d3-8c90-41d1-8c7f-17d4bd84615f","score":0.91,
                    "payload":{"entityType":"faq-article","entityId":"kb://other","content":"Other instructions",
                    "knowledgeSourceHandleRef":"plugin/other-source"},
                    "vector":[0.2,0.1]}],"status":"ok"}""");
                return;
            }
            writeJson(exchange, 404, "{\"status\":\"error\",\"result\":null}");
        });
        server.start();

        try {
            AIProviderConfig config = baseConfig();
            AIProviderConfig.QdrantConfig qdrant = config.getQdrant();
            qdrant.setHost("http://127.0.0.1:" + server.getAddress().getPort());
            qdrant.setPreferGrpc(false);

            QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            Metrics.addRegistry(registry);

            try {
                AISearchResponse response = service.search(List.of(0.1d, 0.2d), AISearchRequest.builder()
                    .query("reset password")
                    .entityType("faq-article")
                    .limit(5)
                    .metadata(java.util.Map.of("knowledgeSourceHandleRef", "plugin/mkp-data-help-center"))
                    .build());

                assertThat(searchBodies).hasSize(2);
                assertThat(OBJECT_MAPPER.readTree(searchBodies.getFirst()).has("filter")).isTrue();
                assertThat(OBJECT_MAPPER.readTree(searchBodies.get(1)).has("filter")).isFalse();
                assertThat(response.getResults()).hasSize(1);
                Map<String, Object> row = response.getResults().getFirst();
                assertThat(row).containsEntry("entityId", "kb://reset");
                assertThat(row).containsEntry("metadataFilterFallback", true);
                assertThat(row.get("metadata")).isInstanceOfSatisfying(Map.class,
                    metadata -> assertThat(metadata)
                        .containsEntry("knowledgeSourceHandleRef", "plugin/mkp-data-help-center")
                        .containsEntry("metadataFilterFallback", true));
                assertThat(service.adminDiagnostics())
                    .containsEntry("metadataFilterFallbacks",
                        Map.of("customer_a__tenant_b__faq-article", 1));
                assertThat(registry.counter(
                    VectorProviderMetrics.FALLBACK_COUNTER,
                    "provider", "qdrant",
                    "operation", "search",
                    "reason", "missing_payload_index"
                ).count()).isEqualTo(1.0d);
            } finally {
                Metrics.removeRegistry(registry);
                registry.close();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void searchFailsClosedWhenPayloadIndexIsMissingAndConfiguredToFail() {
        AIProviderConfig config = baseConfig();
        VectorDatabaseConfig vectorDatabaseConfig = new VectorDatabaseConfig();
        vectorDatabaseConfig.getOperations().setFailOnMissingPayloadIndex(true);

        QdrantClient client = mock(QdrantClient.class);
        when(client.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of("customer_a__tenant_b__faq-article")));
        when(client.getCollectionInfoAsync("customer_a__tenant_b__faq-article"))
            .thenReturn(Futures.immediateFuture(Collections.CollectionInfo.newBuilder().build()));
        when(client.createPayloadIndexAsync(
            eq("customer_a__tenant_b__faq-article"),
            eq("knowledgeSourceHandleRef"),
            eq(Collections.PayloadSchemaType.Keyword),
            isNull(),
            eq(true),
            isNull(),
            isNull()
        )).thenReturn(Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));
        when(client.searchAsync(any()))
            .thenReturn(Futures.immediateFailedFuture(new RuntimeException(
                "INVALID_ARGUMENT: Bad request: Index required but not found for \"knowledgeSourceHandleRef\""
            )));

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, vectorDatabaseConfig, client);

        assertThatThrownBy(() -> service.search(List.of(0.1d, 0.2d), AISearchRequest.builder()
            .query("reset password")
            .entityType("faq-article")
            .limit(5)
            .metadata(java.util.Map.of("knowledgeSourceHandleRef", "plugin/mkp-data-help-center"))
            .build()))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("payload index");

        verify(client).searchAsync(any());
    }

    @Test
    void grpcSearchFailsClosedForUnsupportedMetadataFilterWithoutProviderCall() {
        AIProviderConfig config = baseConfig();
        QdrantClient client = mock(QdrantClient.class);
        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);

        AISearchResponse response = service.search(List.of(0.1d, 0.2d), AISearchRequest.builder()
            .query("reset password")
            .entityType("faq-article")
            .limit(5)
            .metadata(Map.of("tags", List.of("password", "security")))
            .build());

        assertThat(response.getResults()).isEmpty();
        assertThat(response.getTotalResults()).isZero();
        verify(client, never()).listCollectionsAsync();
        verify(client, never()).searchAsync(any());
    }

    @Test
    void grpcScanFailsClosedForUnsupportedMetadataFilterWithoutProviderCall() {
        AIProviderConfig config = baseConfig();
        QdrantClient client = mock(QdrantClient.class);
        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);

        VectorScanPage page = service.scan(VectorScanRequest.builder()
            .entityType("faq-article")
            .metadataEquals(Map.of("tags", List.of("password", "security")))
            .build());

        assertThat(page.getVectors()).isEmpty();
        assertThat(page.isHasMore()).isFalse();
        verify(client, never()).listCollectionsAsync();
        verify(client, never()).scrollAsync(any());
    }

    @Test
    void restSearchFailsClosedForUnsupportedMetadataFilterWithoutHttpCall() throws Exception {
        AtomicInteger httpCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            httpCalls.incrementAndGet();
            writeJson(exchange, 500, "{\"status\":\"error\",\"result\":null}");
        });
        server.start();

        try {
            AIProviderConfig config = baseConfig();
            AIProviderConfig.QdrantConfig qdrant = config.getQdrant();
            qdrant.setHost("http://127.0.0.1:" + server.getAddress().getPort());
            qdrant.setPreferGrpc(false);

            QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config);

            AISearchResponse response = service.search(List.of(0.1d, 0.2d), AISearchRequest.builder()
                .query("reset password")
                .entityType("faq-article")
                .limit(5)
                .metadata(Map.of("tags", List.of("password", "security")))
                .build());

            assertThat(response.getResults()).isEmpty();
            assertThat(response.getTotalResults()).isZero();
            assertThat(httpCalls).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void restScanFailsClosedForUnsupportedMetadataFilterWithoutHttpCall() throws Exception {
        AtomicInteger httpCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            httpCalls.incrementAndGet();
            writeJson(exchange, 500, "{\"status\":\"error\",\"result\":null}");
        });
        server.start();

        try {
            AIProviderConfig config = baseConfig();
            AIProviderConfig.QdrantConfig qdrant = config.getQdrant();
            qdrant.setHost("http://127.0.0.1:" + server.getAddress().getPort());
            qdrant.setPreferGrpc(false);

            QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config);

            VectorScanPage page = service.scan(VectorScanRequest.builder()
                .entityType("faq-article")
                .metadataEquals(Map.of("tags", List.of("password", "security")))
                .build());

            assertThat(page.getVectors()).isEmpty();
            assertThat(page.isHasMore()).isFalse();
            assertThat(httpCalls).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void restScanProjectionReturnsNullForSuppressedFields() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod()) && "/collections".equals(path)) {
                writeJson(exchange, 200, """
                    {"result":{"collections":[{"name":"customer_a__tenant_b__product"}]}}
                    """);
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())
                && "/collections/customer_a__tenant_b__product/points/scroll".equals(path)) {
                writeJson(exchange, 200, """
                    {"result":{"points":[{
                      "id":"product-vector-1",
                      "payload":{
                        "entityType":"product",
                        "entityId":"product-1",
                        "content":"Waterproof shell jacket",
                        "category":"outerwear",
                        "raw":"{\\"category\\":\\"outerwear\\"}",
                        "_ai_fabric_embedding":[0.1,0.2]
                      },
                      "vector":[0.1,0.2]
                    }]}}
                    """);
                return;
            }
            writeJson(exchange, 404, "{\"status\":\"not_found\"}");
        });
        server.start();

        try {
            AIProviderConfig config = baseConfig();
            AIProviderConfig.QdrantConfig qdrant = config.getQdrant();
            qdrant.setHost("http://127.0.0.1:" + server.getAddress().getPort());
            qdrant.setPreferGrpc(false);

            QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config);

            VectorScanPage page = service.scan(VectorScanRequest.builder()
                .entityType("product")
                .limit(1)
                .includeContent(false)
                .includeEmbedding(false)
                .includeMetadata(false)
                .build());

            assertThat(page.getVectors()).hasSize(1);
            assertThat(page.getVectors().getFirst().getContent()).isNull();
            assertThat(page.getVectors().getFirst().getEmbedding()).isNull();
            assertThat(page.getVectors().getFirst().getMetadata()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getVectorCountByEntityTypeUsesCountProbeWithoutListingCollections() {
        AIProviderConfig config = baseConfig();
        QdrantClient client = mock(QdrantClient.class);
        when(client.countAsync(eq("customer_a__tenant_b__product"), any(), eq(true)))
            .thenReturn(Futures.immediateFuture(7L));

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);

        assertThat(service.getVectorCountByEntityType("product")).isEqualTo(7L);

        verify(client, never()).listCollectionsAsync();
    }

    @Test
    void getVectorCountByEntityTypeReturnsZeroWhenCollectionIsMissing() {
        AIProviderConfig config = baseConfig();
        QdrantClient client = mock(QdrantClient.class);
        when(client.countAsync(eq("customer_a__tenant_b__product"), any(), eq(true)))
            .thenReturn(Futures.immediateFailedFuture(new RuntimeException(
                "NOT_FOUND: Collection `customer_a__tenant_b__product` does not exist"
            )));

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);

        assertThat(service.getVectorCountByEntityType("product")).isZero();

        verify(client, never()).listCollectionsAsync();
    }

    @Test
    void grpcGetVectorByRawIdPropagatesProviderFailures() {
        AIProviderConfig config = baseConfig();
        QdrantClient client = mock(QdrantClient.class);
        String collection = "customer_a__tenant_b__product";
        String vectorId = UUID.randomUUID().toString();

        when(client.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of(collection)));
        when(client.retrieveAsync(eq(collection), anyList(), any(), any(), isNull()))
            .thenReturn(Futures.immediateFailedFuture(new RuntimeException("UNAVAILABLE: backend down")));

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);

        assertThatThrownBy(() -> service.getVector(vectorId))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("retrieve point");
    }

    @Test
    void grpcGetVectorByRawIdPropagatesCandidateCollectionListFailures() {
        AIProviderConfig config = baseConfig();
        QdrantClient client = mock(QdrantClient.class);
        String vectorId = UUID.randomUUID().toString();

        when(client.listCollectionsAsync())
            .thenReturn(Futures.immediateFailedFuture(new RuntimeException("UNAVAILABLE: list failed")));

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);

        assertThatThrownBy(() -> service.getVector(vectorId))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("list candidate collections for getVector");
    }

    @Test
    void grpcGetVectorByRawIdTreatsMissingCandidateCollectionAsAbsent() {
        AIProviderConfig config = baseConfig();
        QdrantClient client = mock(QdrantClient.class);
        String collection = "customer_a__tenant_b__product";
        String vectorId = UUID.randomUUID().toString();

        when(client.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of(collection)));
        when(client.retrieveAsync(eq(collection), anyList(), any(), any(), isNull()))
            .thenReturn(Futures.immediateFailedFuture(new RuntimeException(
                "NOT_FOUND: Collection `customer_a__tenant_b__product` does not exist"
            )));

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);

        assertThat(service.getVector(vectorId)).isEmpty();
    }

    @Test
    void grpcRemoveVectorByRawIdPropagatesProviderFailures() {
        AIProviderConfig config = baseConfig();
        QdrantClient client = mock(QdrantClient.class);
        String collection = "customer_a__tenant_b__product";
        String vectorId = UUID.randomUUID().toString();

        when(client.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of(collection)));
        when(client.retrieveAsync(eq(collection), anyList(), any(), any(), isNull()))
            .thenReturn(Futures.immediateFuture(List.of(
                Points.RetrievedPoint.newBuilder()
                    .setId(PointIdFactory.id(UUID.fromString(vectorId)))
                    .build()
            )));
        when(client.deleteAsync(eq(collection), anyList()))
            .thenReturn(Futures.immediateFailedFuture(new RuntimeException("PERMISSION_DENIED: delete denied")));

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);

        assertThatThrownBy(() -> service.removeVectorById(vectorId))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("delete point");
    }

    @Test
    void grpcRemoveVectorByRawIdPropagatesCandidateCollectionListFailures() {
        AIProviderConfig config = baseConfig();
        QdrantClient client = mock(QdrantClient.class);
        String vectorId = UUID.randomUUID().toString();

        when(client.listCollectionsAsync())
            .thenReturn(Futures.immediateFailedFuture(new RuntimeException("UNAVAILABLE: list failed")));

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);

        assertThatThrownBy(() -> service.removeVectorById(vectorId))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("list candidate collections for removeVectorById");
    }

    @Test
    void grpcRemoveVectorByRawIdTreatsMissingCandidateCollectionAsAbsent() {
        AIProviderConfig config = baseConfig();
        QdrantClient client = mock(QdrantClient.class);
        String collection = "customer_a__tenant_b__product";
        String vectorId = UUID.randomUUID().toString();

        when(client.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of(collection)));
        when(client.retrieveAsync(eq(collection), anyList(), any(), any(), isNull()))
            .thenReturn(Futures.immediateFailedFuture(new RuntimeException(
                "NOT_FOUND: Collection `customer_a__tenant_b__product` does not exist"
            )));

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);

        assertThat(service.removeVectorById(vectorId)).isFalse();
        verify(client, never()).deleteAsync(eq(collection), anyList());
    }

    @Test
    void grpcRemoveVectorByRawIdReturnsFalseWhenPointIsMissingInScopedCollection() {
        AIProviderConfig config = baseConfig();
        QdrantClient client = mock(QdrantClient.class);
        String collection = "customer_a__tenant_b__product";
        String vectorId = UUID.randomUUID().toString();

        when(client.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of(collection)));
        when(client.retrieveAsync(eq(collection), anyList(), any(), any(), isNull()))
            .thenReturn(Futures.immediateFuture(List.of()));

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);

        assertThat(service.removeVectorById(vectorId)).isFalse();
        verify(client, never()).deleteAsync(eq(collection), anyList());
    }

    @Test
    void grpcSearchWithoutEntityTypePropagatesCandidateCollectionListFailures() {
        AIProviderConfig config = baseConfig();
        QdrantClient client = mock(QdrantClient.class);

        when(client.listCollectionsAsync())
            .thenReturn(Futures.immediateFailedFuture(new RuntimeException("UNAVAILABLE: list failed")));

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);

        assertThatThrownBy(() -> service.search(List.of(0.1d, 0.2d), AISearchRequest.builder()
            .query("find anything")
            .limit(5)
            .build()))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("list candidate collections for search collections");
    }

    @Test
    void grpcClearVectorsPropagatesCandidateCollectionListFailures() {
        AIProviderConfig config = baseConfig();
        QdrantClient client = mock(QdrantClient.class);

        when(client.listCollectionsAsync())
            .thenReturn(Futures.immediateFailedFuture(new RuntimeException("UNAVAILABLE: list failed")));

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);

        assertThatThrownBy(service::clearVectors)
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("list candidate collections for clearVectors");
    }

    @Test
    void grpcUpdateVectorReturnsFalseWhenTargetPointIsMissing() {
        AIProviderConfig config = baseConfig();
        QdrantClient client = mock(QdrantClient.class);
        String collection = "customer_a__tenant_b__product";
        String vectorId = UUID.randomUUID().toString();

        when(client.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of(collection)));
        when(client.retrieveAsync(eq(collection), anyList(), any(), any(), isNull()))
            .thenReturn(Futures.immediateFuture(List.of()));

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);

        boolean updated = service.updateVector(
            vectorId,
            "product",
            "missing-product",
            "Updated product",
            List.of(0.1d, 0.2d),
            Map.of("category", "watches")
        );

        assertThat(updated).isFalse();
        verify(client).retrieveAsync(eq(collection), anyList(), any(), any(), isNull());
        verify(client, never()).upsertAsync(anyString(), anyList());
    }

    @Test
    void batchOperationsIgnoreNullRecordsAndBlankVectorIds() {
        RecordingQdrantService service = new RecordingQdrantService();
        VectorRecord valid = VectorRecord.builder()
            .vectorId("vector-1")
            .entityType("product")
            .entityId("product-1")
            .content("Waterproof shell jacket")
            .embedding(List.of(0.1d, 0.2d))
            .metadata(Map.of("brand", "Loom"))
            .build();
        VectorRecord missingId = VectorRecord.builder()
            .entityType("product")
            .entityId("product-2")
            .embedding(List.of(0.3d, 0.4d))
            .build();
        VectorRecord blankId = VectorRecord.builder()
            .vectorId(" ")
            .entityType("product")
            .entityId("product-3")
            .embedding(List.of(0.5d, 0.6d))
            .build();

        List<VectorRecord> storeRecords = new ArrayList<>();
        storeRecords.add(null);
        storeRecords.add(valid);
        assertThat(service.batchStoreVectors(storeRecords)).containsExactly("stored-product-1");

        List<VectorRecord> updateRecords = new ArrayList<>();
        updateRecords.add(null);
        updateRecords.add(missingId);
        updateRecords.add(blankId);
        updateRecords.add(valid);
        assertThat(service.batchUpdateVectors(updateRecords)).isEqualTo(1);
        assertThat(service.updatedVectorIds).containsExactly("vector-1");

        List<String> removeIds = new ArrayList<>();
        removeIds.add(null);
        removeIds.add(" ");
        removeIds.add("vector-1");
        assertThat(service.batchRemoveVectors(removeIds)).isEqualTo(1);
    }

    @Test
    void directLifecycleRejectsBlankIdentityBeforeNativeClientCalls() {
        QdrantClient client = mock(QdrantClient.class);
        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(baseConfig(), null, client);

        assertThatThrownBy(() -> service.storeVector(" ", "product-1", "Invalid product",
            List.of(0.1d, 0.2d), Map.of()))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("entityType");
        assertThatThrownBy(() -> service.storeVector("product", " ", "Invalid product",
            List.of(0.1d, 0.2d), Map.of()))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("entityId");

        assertThat(service.updateVector("vector-1", " ", "product-1", "Invalid product",
            List.of(0.1d, 0.2d), Map.of())).isFalse();
        assertThat(service.getVectorByEntity(" ", "product-1")).isEmpty();
        assertThat(service.removeVector("product", " ")).isFalse();
        assertThat(service.clearVectorsByEntityType(" ")).isZero();
        verifyNoInteractions(client);
    }

    private static AIProviderConfig baseConfig() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.QdrantConfig qdrant = config.getQdrant();
        qdrant.setEnabled(true);
        qdrant.setHost("qdrant.internal");
        qdrant.setCollectionPrefix("customer_a__tenant_b__");
        return config;
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class RecordingQdrantService extends QdrantVectorDatabaseService {
        private final List<String> updatedVectorIds = new ArrayList<>();

        private RecordingQdrantService() {
            super(baseConfig(), null, mock(QdrantClient.class));
        }

        @Override
        public String storeVector(String entityType, String entityId, String content, List<Double> embedding, Map<String, Object> metadata) {
            return "stored-" + entityId;
        }

        @Override
        public boolean updateVector(String vectorId, String entityType, String entityId, String content,
                                    List<Double> embedding, Map<String, Object> metadata) {
            updatedVectorIds.add(vectorId);
            return true;
        }

        @Override
        public boolean removeVectorById(String vectorId) {
            return vectorId != null && !vectorId.isBlank();
        }
    }
}
