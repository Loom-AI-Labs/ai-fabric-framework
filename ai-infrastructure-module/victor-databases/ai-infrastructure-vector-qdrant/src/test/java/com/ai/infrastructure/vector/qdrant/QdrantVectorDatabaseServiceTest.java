package com.ai.infrastructure.vector.qdrant;

import com.ai.infrastructure.config.AIProviderConfig;
import com.ai.infrastructure.dto.AISearchRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.Futures;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    void adminDiagnosticsExposeResolvedCollectionScope() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.QdrantConfig qdrant = config.getQdrant();
        qdrant.setEnabled(true);
        qdrant.setHost("qdrant.internal");
        qdrant.setCollectionPrefix("customer_a__tenant_b__");

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config);

        assertThat(service.adminDiagnostics())
            .containsEntry("sharedStorage", true)
            .containsEntry("scopeType", "COLLECTION_PREFIX")
            .containsEntry("rootResourceValue", "qdrant.internal")
            .containsEntry("scopePrefix", "customer_a__tenant_b__")
            .containsEntry("scopePattern", "customer_a__tenant_b__<entity_type>");
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
            assertThat(point.path("vector")).hasSize(2);
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
                Futures.immediateFuture(List.of())
            );

        QdrantVectorDatabaseService service = new QdrantVectorDatabaseService(config, null, client);

        service.search(List.of(0.1d, 0.2d), AISearchRequest.builder()
            .query("reset password")
            .entityType("faq-article")
            .limit(5)
            .metadata(java.util.Map.of("knowledgeSourceHandleRef", "plugin/mkp-data-help-center"))
            .build());

        verify(client, atLeast(2)).searchAsync(any());
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

    private AIProviderConfig baseConfig() {
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
}
