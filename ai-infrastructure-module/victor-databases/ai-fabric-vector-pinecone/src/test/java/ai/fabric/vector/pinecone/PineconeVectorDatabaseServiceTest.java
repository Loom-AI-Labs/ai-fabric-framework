package ai.fabric.vector.pinecone;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.config.VectorDatabaseConfig;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.dto.VectorScanRequest;
import ai.fabric.exception.AIServiceException;
import ai.fabric.vector.VectorProviderMetrics;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.pinecone.clients.Index;
import io.pinecone.configs.PineconeConnection;
import io.pinecone.proto.DeleteResponse;
import io.pinecone.proto.DescribeIndexStatsResponse;
import io.pinecone.proto.FetchResponse;
import io.pinecone.proto.ListItem;
import io.pinecone.proto.ListResponse;
import io.pinecone.proto.NamespaceSummary;
import io.pinecone.proto.Pagination;
import io.pinecone.proto.QueryResponse;
import io.pinecone.proto.ScoredVector;
import io.pinecone.proto.UpsertRequest;
import io.pinecone.proto.VectorServiceGrpc;
import io.pinecone.unsigned_indices_model.QueryResponseWithUnsignedIndices;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PineconeVectorDatabaseServiceTest {

    private PineconeVectorDatabaseService service;
    private Index index;

    @BeforeEach
    void setUp() {
        AIProviderConfig config = enabledPineconeConfig();
        index = mock(Index.class);
        service = new PineconeVectorDatabaseService(config, (connection, indexName) -> index);
    }

    @Test
    void storeVectorCallsUpsertEndpoint() {
        ArgumentCaptor<Struct> metadataCaptor = ArgumentCaptor.forClass(Struct.class);

        String vectorId = service.storeVector(
            "product",
            "123",
            "Luxury watch",
            List.of(0.1, 0.2, 0.3),
            Map.of("category", "watches")
        );

        assertEquals("product::123", vectorId);

        verify(index).upsert(
            eq("product::123"),
            anyList(),
            isNull(),
            isNull(),
            metadataCaptor.capture(),
            eq("product")
        );

        Struct captured = metadataCaptor.getValue();
        assertEquals("product", captured.getFieldsOrThrow("entityType").getStringValue());
        assertEquals("123", captured.getFieldsOrThrow("entityId").getStringValue());
        assertEquals("Luxury watch", captured.getFieldsOrThrow("content").getStringValue());
        assertEquals(3.0, captured.getFieldsOrThrow("embeddingDim").getNumberValue());
        assertFalse(captured.getFieldsOrThrow("embeddingBase64").getStringValue().isBlank());
        assertTrue(captured.getFieldsOrThrow("raw").getStringValue().contains("watches"));
        assertTrue(captured.getFieldsOrThrow("raw").getStringValue().contains("_indexedCreatedAt"));
        assertTrue(captured.getFieldsOrThrow("raw").getStringValue().contains("_indexedUpdatedAt"));
        assertEquals("watches", captured.getFieldsOrThrow("category").getStringValue());
        assertFalse(captured.getFieldsOrThrow("_indexedCreatedAt").getStringValue().isBlank());
        assertFalse(captured.getFieldsOrThrow("_indexedUpdatedAt").getStringValue().isBlank());
    }

    @Test
    void storeVectorUsesSparseUpsertWhenIndexIsSparse() {
        when(index.describeIndexStats()).thenReturn(DescribeIndexStatsResponse.newBuilder().setDimension(0).build());

        PineconeConnection connection = mock(PineconeConnection.class);
        VectorServiceGrpc.VectorServiceBlockingStub blockingClient = mock(VectorServiceGrpc.VectorServiceBlockingStub.class);
        when(connection.getBlockingStub()).thenReturn(blockingClient);

        service = new PineconeVectorDatabaseService(enabledPineconeConfig(), connection, index);

        service.storeVector(
            "product",
            "123",
            "Luxury watch",
            List.of(0.1, 0.2, 0.3),
            Map.of("category", "watches")
        );

        ArgumentCaptor<UpsertRequest> requestCaptor = ArgumentCaptor.forClass(UpsertRequest.class);
        verify(blockingClient).upsert(requestCaptor.capture());
        UpsertRequest request = requestCaptor.getValue();
        assertEquals("product", request.getNamespace());
        assertEquals(1, request.getVectorsCount());
        assertEquals("product::123", request.getVectors(0).getId());
        assertEquals(0, request.getVectors(0).getValuesCount());
        assertEquals(List.of(0, 1, 2), request.getVectors(0).getSparseValues().getIndicesList());
        assertEquals(List.of(0.1f, 0.2f, 0.3f), request.getVectors(0).getSparseValues().getValuesList());
    }

    @Test
    void denseUpsertFailureCachesSparseFallback() {
        when(index.describeIndexStats()).thenReturn(
            DescribeIndexStatsResponse.newBuilder().setDimension(3).build(),
            DescribeIndexStatsResponse.newBuilder().setDimension(0).build()
        );
        doThrow(new IllegalArgumentException("sparse index requires sparse values"))
            .when(index)
            .upsert(eq("product::first"), anyList(), isNull(), isNull(), any(Struct.class), eq("product"));

        PineconeConnection connection = mock(PineconeConnection.class);
        VectorServiceGrpc.VectorServiceBlockingStub blockingClient = mock(VectorServiceGrpc.VectorServiceBlockingStub.class);
        when(connection.getBlockingStub()).thenReturn(blockingClient);
        service = new PineconeVectorDatabaseService(enabledPineconeConfig(), connection, index);

        service.storeVector("product", "first", "First product", List.of(0.1, 0.2, 0.3), Map.of());
        service.storeVector("product", "second", "Second product", List.of(0.4, 0.5, 0.6), Map.of());

        verify(index, times(1)).upsert(anyString(), anyList(), isNull(), isNull(), any(Struct.class), anyString());
        verify(index, times(2)).describeIndexStats();

        ArgumentCaptor<UpsertRequest> requestCaptor = ArgumentCaptor.forClass(UpsertRequest.class);
        verify(blockingClient, times(2)).upsert(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues())
            .extracting(request -> request.getVectors(0).getId())
            .containsExactly("product::first", "product::second");
    }

    @Test
    void storeVectorWithNamespacePrefixPreservesOriginalEntityTypeMetadata() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.PineconeConfig pinecone = config.getPinecone();
        pinecone.setEnabled(true);
        pinecone.setApiKey("test-key");
        pinecone.setApiHost("https://mock-pinecone.test");
        pinecone.setIndexName("test-index");
        pinecone.setEnvironment("test-env");
        pinecone.setDimensions(3);
        pinecone.setNamespacePrefix("customer-a--tenant-b");

        Index prefixedIndex = mock(Index.class);
        PineconeVectorDatabaseService prefixedService = new PineconeVectorDatabaseService(
            config,
            (connection, indexName) -> prefixedIndex
        );
        ArgumentCaptor<Struct> metadataCaptor = ArgumentCaptor.forClass(Struct.class);

        prefixedService.storeVector(
            "product",
            "123",
            "Luxury watch",
            List.of(0.1, 0.2, 0.3),
            Map.of("category", "watches")
        );

        verify(prefixedIndex).upsert(
            eq("customer-a--tenant-b__product::123"),
            anyList(),
            isNull(),
            isNull(),
            metadataCaptor.capture(),
            eq("customer-a--tenant-b__product")
        );
        assertEquals("product", metadataCaptor.getValue().getFieldsOrThrow("entityType").getStringValue());
    }

    @Test
    void adminDiagnosticsExposeResolvedNamespaceScope() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.PineconeConfig pinecone = config.getPinecone();
        pinecone.setEnabled(true);
        pinecone.setApiKey("test-key");
        pinecone.setApiHost("https://mock-pinecone.test");
        pinecone.setIndexName("tenant-shared-index");
        pinecone.setNamespacePrefix("customer-a--tenant-b");

        PineconeVectorDatabaseService scopedService = new PineconeVectorDatabaseService(
            config,
            (connection, indexName) -> mock(Index.class)
        );

        Map<String, Object> diagnostics = scopedService.adminDiagnostics();

        assertThat(diagnostics)
            .containsEntry("provider", "pinecone")
            .containsEntry("sharedStorage", true)
            .containsEntry("scopeType", "NAMESPACE_PREFIX")
            .containsEntry("rootResourceValue", "tenant-shared-index")
            .containsEntry("scopePrefix", "customer-a--tenant-b")
            .containsEntry("scopePattern", "customer-a--tenant-b__<entity-type>")
            .containsEntry("supportsVectorScan", true)
            .containsEntry("supportsSearchMetadataFiltering", true)
            .containsEntry("supportsScanMetadataFiltering", true)
            .containsEntry("supportsExactFetchById", true)
            .containsEntry("supportsClearByEntityType", true)
            .containsEntry("supportsEfficientEntityTypeCount", true)
            .containsEntry("metadataFilteredSearch", true)
            .containsEntry("metadataFilteredScan", true)
            .containsEntry("searchFilterMode", "provider-side-portable-scalar")
            .containsEntry("scanFilterMode", "client-side-list-fetch-portable-scalar");
        assertThat(scopedService.supportsSearchMetadataFiltering()).isTrue();
        assertThat(scopedService.supportsScanMetadataFiltering()).isTrue();
        assertThat(scopedService.supportsMetadataFiltering()).isTrue();
    }

    @Test
    void searchReturnsFilteredResults() {
        Struct highMetadata = Struct.newBuilder()
            .putFields("entityType", Value.newBuilder().setStringValue("product").build())
            .putFields("entityId", Value.newBuilder().setStringValue("123").build())
            .putFields("content", Value.newBuilder().setStringValue("Luxury watch").build())
            .build();

        Struct lowMetadata = Struct.newBuilder()
            .putFields("entityType", Value.newBuilder().setStringValue("product").build())
            .putFields("entityId", Value.newBuilder().setStringValue("456").build())
            .build();

        ScoredVector highScore = ScoredVector.newBuilder()
            .setId("product::123")
            .setScore(0.92f)
            .setMetadata(highMetadata)
            .build();

        ScoredVector lowScore = ScoredVector.newBuilder()
            .setId("product::456")
            .setScore(0.4f)
            .setMetadata(lowMetadata)
            .build();

        QueryResponse queryResponse = QueryResponse.newBuilder()
            .addMatches(highScore)
            .addMatches(lowScore)
            .build();

        when(index.queryByVector(eq(5), anyList(), eq("product"), nullable(Struct.class), eq(false), eq(true)))
            .thenReturn(new QueryResponseWithUnsignedIndices(queryResponse));

        AISearchResponse response = service.search(
            List.of(0.2, 0.3, 0.4),
            AISearchRequest.builder()
                .query("luxury")
                .entityType("product")
                .limit(5)
                .threshold(0.5)
                .build()
        );

        assertEquals(1, response.getTotalResults());
        assertEquals("product::123", response.getResults().get(0).get("vectorId"));
    }

    @Test
    void searchBuildsValidPineconeFilterStruct() {
        when(index.queryByVector(eq(5), anyList(), eq("product"), nullable(Struct.class), eq(false), eq(true)))
            .thenReturn(new QueryResponseWithUnsignedIndices(QueryResponse.getDefaultInstance()));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenantId", "t-1");
        metadata.put("featured", true);
        metadata.put("rank", 7);

        service.search(
            List.of(0.2, 0.3, 0.4),
            AISearchRequest.builder()
                .query("luxury")
                .entityType("product")
                .limit(5)
                .threshold(0.0)
                .metadata(metadata)
                .build()
        );

        ArgumentCaptor<Struct> filterCaptor = ArgumentCaptor.forClass(Struct.class);
        verify(index).queryByVector(eq(5), anyList(), eq("product"), filterCaptor.capture(), eq(false), eq(true));

        Struct filter = filterCaptor.getValue();
        assertNotNull(filter);

        Value tenantCond = filter.getFieldsOrThrow("tenantId");
        assertTrue(tenantCond.hasStructValue());
        assertEquals("t-1", tenantCond.getStructValue().getFieldsOrThrow("$eq").getStringValue());

        Value featuredCond = filter.getFieldsOrThrow("featured");
        assertTrue(featuredCond.hasStructValue());
        assertTrue(featuredCond.getStructValue().getFieldsOrThrow("$eq").getBoolValue());

        Value rankCond = filter.getFieldsOrThrow("rank");
        assertTrue(rankCond.hasStructValue());
        assertEquals(7.0, rankCond.getStructValue().getFieldsOrThrow("$eq").getNumberValue());
    }

    @Test
    void searchPreservesEmptyStringMetadataFilterValue() {
        when(index.queryByVector(eq(5), anyList(), eq("product"), nullable(Struct.class), eq(false), eq(true)))
            .thenReturn(new QueryResponseWithUnsignedIndices(QueryResponse.getDefaultInstance()));

        service.search(
            List.of(0.2, 0.3, 0.4),
            AISearchRequest.builder()
                .query("luxury")
                .entityType("product")
                .limit(5)
                .threshold(0.0)
                .metadata(Map.of("status", ""))
                .build()
        );

        ArgumentCaptor<Struct> filterCaptor = ArgumentCaptor.forClass(Struct.class);
        verify(index).queryByVector(eq(5), anyList(), eq("product"), filterCaptor.capture(), eq(false), eq(true));

        Struct filter = filterCaptor.getValue();
        assertNotNull(filter);
        Value statusCond = filter.getFieldsOrThrow("status");
        assertTrue(statusCond.hasStructValue());
        assertEquals("", statusCond.getStructValue().getFieldsOrThrow("$eq").getStringValue());
    }

    @Test
    void searchFailsClosedWhenPineconeFilterContainsUnsupportedShape() {
        when(index.queryByVector(eq(5), anyList(), eq("product"), nullable(Struct.class), eq(false), eq(true)))
            .thenReturn(new QueryResponseWithUnsignedIndices(QueryResponse.getDefaultInstance()));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenantId", "t-1");
        metadata.put("sessionId", null);
        metadata.put("piiDetectedTypes", List.of("SSN"));
        metadata.put("providerOperator", Map.of("$eq", "native"));

        service.search(
            List.of(0.2, 0.3, 0.4),
            AISearchRequest.builder()
                .query("luxury")
                .entityType("product")
                .limit(5)
                .threshold(0.0)
                .metadata(metadata)
                .build()
        );

        ArgumentCaptor<Struct> filterCaptor = ArgumentCaptor.forClass(Struct.class);
        verify(index).queryByVector(eq(5), anyList(), eq("product"), filterCaptor.capture(), eq(false), eq(true));

        Struct filter = filterCaptor.getValue();
        assertThat(filter.getFieldsMap()).containsKey("__ai_fabric_unsupported_metadata_filter__");
        assertThat(filter.getFieldsMap()).doesNotContainKeys("tenantId", "sessionId", "piiDetectedTypes", "providerOperator");
    }

    @Test
    void searchNormalizesInvalidLimitAndThreshold() {
        ScoredVector match = ScoredVector.newBuilder()
            .setId("product::123")
            .setScore(0.25f)
            .setMetadata(Struct.newBuilder()
                .putFields("entityType", Value.newBuilder().setStringValue("product").build())
                .putFields("entityId", Value.newBuilder().setStringValue("123").build())
                .build())
            .build();
        QueryResponse queryResponse = QueryResponse.newBuilder()
            .addMatches(match)
            .build();

        when(index.queryByVector(eq(10), anyList(), eq("product"), nullable(Struct.class), eq(false), eq(true)))
            .thenReturn(new QueryResponseWithUnsignedIndices(queryResponse));

        AISearchResponse response = service.search(
            List.of(0.2, 0.3, 0.4),
            AISearchRequest.builder()
                .query("luxury")
                .entityType("product")
                .limit(0)
                .threshold(Double.NaN)
                .build()
        );

        assertEquals(1, response.getTotalResults());
        verify(index).queryByVector(eq(10), anyList(), eq("product"), nullable(Struct.class), eq(false), eq(true));
    }

    @Test
    void scanProjectionOmitsExcludedFields() {
        Struct metadata = Struct.newBuilder()
            .putFields("entityType", Value.newBuilder().setStringValue("product").build())
            .putFields("entityId", Value.newBuilder().setStringValue("123").build())
            .putFields("content", Value.newBuilder().setStringValue("Luxury watch").build())
            .putFields("category", Value.newBuilder().setStringValue("watches").build())
            .build();
        io.pinecone.proto.Vector vector = io.pinecone.proto.Vector.newBuilder()
            .setId("product::123")
            .addAllValues(List.of(0.1f, 0.3f, 0.5f))
            .setMetadata(metadata)
            .build();

        when(index.list(eq("product"), eq(1))).thenReturn(ListResponse.newBuilder()
            .addVectors(ListItem.newBuilder().setId("product::123").build())
            .build());
        when(index.fetch(eq(List.of("product::123")), eq("product"))).thenReturn(FetchResponse.newBuilder()
            .putVectors("product::123", vector)
            .build());

        VectorScanPage page = service.scan(VectorScanRequest.builder()
            .entityType("product")
            .limit(1)
            .includeContent(false)
            .includeEmbedding(false)
            .includeMetadata(false)
            .build());

        assertThat(page.getVectors()).hasSize(1);
        VectorRecord record = page.getVectors().get(0);
        assertThat(record.getEntityId()).isEqualTo("123");
        assertThat(record.getContent()).isNull();
        assertThat(record.getEmbedding()).isNull();
        assertThat(record.getMetadata()).isNull();
    }

    @Test
    void scanPreservesPineconeListOrderWhenFetchMapOrderDiffers() {
        when(index.list(eq("product"), eq(2))).thenReturn(ListResponse.newBuilder()
            .addVectors(listItem("product::b"))
            .addVectors(listItem("product::a"))
            .build());
        when(index.fetch(eq(List.of("product::b", "product::a")), eq("product"))).thenReturn(FetchResponse.newBuilder()
            .putVectors("product::a", pineconeVector("product::a", "a", "watches"))
            .putVectors("product::b", pineconeVector("product::b", "b", "watches"))
            .build());

        VectorScanPage page = service.scan(VectorScanRequest.builder()
            .entityType("product")
            .limit(2)
            .build());

        assertThat(page.getVectors())
            .extracting(VectorRecord::getEntityId)
            .containsExactly("b", "a");
    }

    @Test
    void scanAppliesMetadataFilterAcrossPagesWithoutDroppingFinalMatch() {
        io.pinecone.proto.Vector firstMatch = pineconeVector("product::1", "1", "watches");
        io.pinecone.proto.Vector firstMiss = pineconeVector("product::2", "2", "bags");
        io.pinecone.proto.Vector finalMatch = pineconeVector("product::3", "3", "watches");

        when(index.list(eq("product"), eq(2))).thenReturn(ListResponse.newBuilder()
            .addVectors(listItem("product::1"))
            .addVectors(listItem("product::2"))
            .setPagination(Pagination.newBuilder().setNext("cursor-1").build())
            .build());
        when(index.fetch(eq(List.of("product::1", "product::2")), eq("product"))).thenReturn(FetchResponse.newBuilder()
            .putVectors("product::1", firstMatch)
            .putVectors("product::2", firstMiss)
            .build());
        when(index.list(eq("product"), eq(1), eq("cursor-1"))).thenReturn(ListResponse.newBuilder()
            .addVectors(listItem("product::3"))
            .build());
        when(index.fetch(eq(List.of("product::3")), eq("product"))).thenReturn(FetchResponse.newBuilder()
            .putVectors("product::3", finalMatch)
            .build());

        VectorScanPage page = service.scan(VectorScanRequest.builder()
            .entityType("product")
            .metadataEquals(Map.of("category", "watches"))
            .limit(2)
            .build());

        assertThat(page.getVectors())
            .extracting(VectorRecord::getEntityId)
            .containsExactly("1", "3");
        assertThat(page.isHasMore()).isFalse();
        assertThat(page.getNextCursor()).isNull();
        verify(index).list(eq("product"), eq(1), eq("cursor-1"));
    }

    @Test
    void scanFailsClosedForUnsupportedMetadataFilterWithoutCallingPinecone() {
        VectorScanPage page = service.scan(VectorScanRequest.builder()
            .entityType("product")
            .metadataEquals(Map.of("tags", List.of("public")))
            .limit(5)
            .build());

        assertThat(page.getVectors()).isEmpty();
        assertThat(page.isHasMore()).isFalse();
        assertThat(page.getNextCursor()).isNull();
        verifyNoInteractions(index);
    }

    @Test
    void getVectorsByEntityTypePreservesPineconeListOrderWhenFetchMapOrderDiffers() {
        when(index.list(eq("product"), eq(100))).thenReturn(ListResponse.newBuilder()
            .addVectors(listItem("product::b"))
            .addVectors(listItem("product::a"))
            .build());
        when(index.fetch(eq(List.of("product::b", "product::a")), eq("product"))).thenReturn(FetchResponse.newBuilder()
            .putVectors("product::a", pineconeVector("product::a", "a", "watches"))
            .putVectors("product::b", pineconeVector("product::b", "b", "watches"))
            .build());

        List<VectorRecord> records = service.getVectorsByEntityType("product");

        assertThat(records)
            .extracting(VectorRecord::getEntityId)
            .containsExactly("b", "a");
    }

    @Test
    void clearByEntityTypeUsesListDeletePathWhenAwaitingConsistency() {
        VectorDatabaseConfig vectorDatabaseConfig = new VectorDatabaseConfig();
        vectorDatabaseConfig.getOperations().setAwaitClearConsistency(true);
        vectorDatabaseConfig.getOperations().setAwaitClearTimeoutMs(1_000L);
        service = new PineconeVectorDatabaseService(
            enabledPineconeConfig(),
            vectorDatabaseConfig,
            (connection, indexName) -> index
        );

        when(index.describeIndexStats()).thenReturn(DescribeIndexStatsResponse.newBuilder()
            .putNamespaces("product", NamespaceSummary.newBuilder().setVectorCount(1).build())
            .build());
        when(index.list(eq("product"), eq(100))).thenReturn(
            ListResponse.newBuilder()
                .addVectors(ListItem.newBuilder().setId("product::123").build())
                .build(),
            ListResponse.getDefaultInstance()
        );
        when(index.list(eq("product"), eq(1))).thenReturn(ListResponse.getDefaultInstance());

        long cleared = service.clearVectorsByEntityType("product");

        assertThat(cleared).isEqualTo(1L);
        verify(index).deleteByIds(eq(List.of("product::123")), eq("product"));
        verify(index, never()).deleteAll(anyString());
    }

    @Test
    void statisticsRetryTransientPineconeFailure() {
        when(index.describeIndexStats()).thenThrow(new StatusRuntimeException(Status.UNAVAILABLE))
            .thenReturn(DescribeIndexStatsResponse.newBuilder()
                .putNamespaces("product", NamespaceSummary.newBuilder().setVectorCount(7).build())
                .build());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);

        try {
            long count = service.getVectorCountByEntityType("product");

            assertThat(count).isEqualTo(7L);
            verify(index, times(2)).describeIndexStats();
            assertThat(registry.counter(
                VectorProviderMetrics.RETRY_COUNTER,
                "provider", "pinecone",
                "operation", "describeindexstats",
                "reason", "unavailable"
            ).count()).isEqualTo(1.0d);
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    @Test
    void searchRetriesTransientPineconeQueryFailure() {
        ScoredVector match = ScoredVector.newBuilder()
            .setId("product::123")
            .setScore(0.91f)
            .setMetadata(Struct.newBuilder()
                .putFields("entityType", stringValue("product"))
                .putFields("entityId", stringValue("123"))
                .build())
            .build();
        QueryResponse queryResponse = QueryResponse.newBuilder()
            .addMatches(match)
            .build();
        when(index.queryByVector(eq(5), anyList(), eq("product"), nullable(Struct.class), eq(false), eq(true)))
            .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE))
            .thenReturn(new QueryResponseWithUnsignedIndices(queryResponse));

        AISearchResponse response = service.search(
            List.of(0.2, 0.3, 0.4),
            AISearchRequest.builder()
                .query("luxury")
                .entityType("product")
                .limit(5)
                .threshold(0.0)
                .build()
        );

        assertThat(response.getTotalResults()).isEqualTo(1);
        verify(index, times(2)).queryByVector(eq(5), anyList(), eq("product"), nullable(Struct.class), eq(false), eq(true));
    }

    @Test
    void scanRetriesTransientPineconeListAndFetchFailures() {
        when(index.list(eq("product"), eq(1)))
            .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE))
            .thenReturn(ListResponse.newBuilder()
                .addVectors(listItem("product::123"))
                .build());
        when(index.fetch(eq(List.of("product::123")), eq("product")))
            .thenThrow(new StatusRuntimeException(Status.DEADLINE_EXCEEDED))
            .thenReturn(FetchResponse.newBuilder()
                .putVectors("product::123", pineconeVector("product::123", "123", "watches"))
                .build());

        VectorScanPage page = service.scan(VectorScanRequest.builder()
            .entityType("product")
            .limit(1)
            .build());

        assertThat(page.getVectors())
            .extracting(VectorRecord::getEntityId)
            .containsExactly("123");
        verify(index, times(2)).list(eq("product"), eq(1));
        verify(index, times(2)).fetch(eq(List.of("product::123")), eq("product"));
    }

    @Test
    void removeVectorByIdRetriesTransientPineconeDeleteFailure() {
        when(index.deleteByIds(eq(List.of("product::123")), eq("product")))
            .thenThrow(new StatusRuntimeException(Status.RESOURCE_EXHAUSTED))
            .thenReturn(DeleteResponse.getDefaultInstance());

        assertTrue(service.removeVectorById("product::123"));

        verify(index, times(2)).deleteByIds(eq(List.of("product::123")), eq("product"));
    }

    @Test
    void getVectorFetchesFromApi() {
        Struct metadata = Struct.newBuilder()
            .putFields("entityType", Value.newBuilder().setStringValue("product").build())
            .putFields("entityId", Value.newBuilder().setStringValue("123").build())
            .putFields("content", Value.newBuilder().setStringValue("Luxury watch").build())
            .putFields("raw", Value.newBuilder().setStringValue("{\"category\":\"watches\"}").build())
            .build();

        io.pinecone.proto.Vector vector = io.pinecone.proto.Vector.newBuilder()
            .setId("product::123")
            .addAllValues(List.of(0.1f, 0.3f, 0.5f))
            .setMetadata(metadata)
            .build();

        FetchResponse fetchResponse = FetchResponse.newBuilder()
            .putVectors("product::123", vector)
            .build();

        when(index.fetch(eq(List.of("product::123")), eq("product"))).thenReturn(fetchResponse);

        Optional<VectorRecord> record = service.getVector("product::123");

        assertTrue(record.isPresent());
        assertEquals("123", record.get().getEntityId());
        assertEquals(3, record.get().getEmbedding().size());
        assertEquals("product::123", record.get().getVectorId());
        verify(index).fetch(eq(List.of("product::123")), eq("product"));
    }

    @Test
    void updateVectorReturnsFalseWhenTargetVectorIsMissing() {
        when(index.fetch(eq(List.of("product::missing")), eq("product")))
            .thenReturn(FetchResponse.getDefaultInstance());

        boolean updated = service.updateVector(
            "product::missing",
            "product",
            "missing",
            "Updated product",
            List.of(0.1, 0.2, 0.3),
            Map.of("category", "watches")
        );

        assertFalse(updated);
        verify(index).fetch(eq(List.of("product::missing")), eq("product"));
        verify(index, never()).upsert(anyString(), anyList(), any(), any(), any(), anyString());
    }

    @Test
    void storeVectorRejectsMissingEmbeddingBeforeCallingPinecone() {
        AIServiceException nullEmbedding = assertThrows(AIServiceException.class, () -> service.storeVector(
            "product",
            "missing-embedding",
            "Invalid product",
            null,
            Map.of("category", "watches")
        ));
        assertThat(nullEmbedding.getMessage()).contains("non-empty embedding vector");

        AIServiceException emptyEmbedding = assertThrows(AIServiceException.class, () -> service.storeVector(
            "product",
            "empty-embedding",
            "Invalid product",
            List.of(),
            Map.of("category", "watches")
        ));
        assertThat(emptyEmbedding.getMessage()).contains("non-empty embedding vector");

        verify(index, never()).upsert(anyString(), anyList(), any(), any(), any(), anyString());
    }

    @Test
    void updateVectorRejectsMissingEmbeddingAfterExistingVectorCheck() {
        when(index.fetch(eq(List.of("product::123")), eq("product"))).thenReturn(FetchResponse.newBuilder()
            .putVectors("product::123", pineconeVector("product::123", "123", "watches"))
            .build());

        AIServiceException exception = assertThrows(AIServiceException.class, () -> service.updateVector(
            "product::123",
            "product",
            "123",
            "Invalid update",
            List.of(),
            Map.of("category", "watches")
        ));

        assertThat(exception.getMessage()).contains("non-empty embedding vector");
        verify(index).fetch(eq(List.of("product::123")), eq("product"));
        verify(index, never()).upsert(anyString(), anyList(), any(), any(), any(), anyString());
    }

    @Test
    void directLifecycleRejectsBlankIdentityBeforeNativeCalls() {
        AIServiceException blankEntityType = assertThrows(AIServiceException.class, () -> service.storeVector(
            " ",
            "123",
            "Invalid product",
            List.of(0.1, 0.2, 0.3),
            Map.of("category", "watches")
        ));
        assertThat(blankEntityType.getMessage()).contains("entityType");

        AIServiceException blankEntityId = assertThrows(AIServiceException.class, () -> service.storeVector(
            "product",
            " ",
            "Invalid product",
            List.of(0.1, 0.2, 0.3),
            Map.of("category", "watches")
        ));
        assertThat(blankEntityId.getMessage()).contains("entityId");

        assertThat(service.updateVector("product::123", " ", "123", "Invalid product",
            List.of(0.1, 0.2, 0.3), Map.of())).isFalse();
        assertThat(service.getVectorByEntity(" ", "123")).isEmpty();
        assertThat(service.removeVector("product", " ")).isFalse();
        assertThat(service.clearVectorsByEntityType(" ")).isZero();
        verifyNoInteractions(index);
    }

    @Test
    void batchOperationsIgnoreNullRecordsAndBlankVectorIds() {
        VectorRecord validStore = VectorRecord.builder()
            .entityType("product")
            .entityId("123")
            .content("Luxury watch")
            .embedding(List.of(0.1, 0.2, 0.3))
            .metadata(Map.of("category", "watches"))
            .build();

        assertThat(service.batchStoreVectors(Arrays.asList(null, validStore)))
            .containsExactly("product::123");
        verify(index).upsert(eq("product::123"), anyList(), isNull(), isNull(), any(Struct.class), eq("product"));

        reset(index);
        when(index.fetch(eq(List.of("product::123")), eq("product"))).thenReturn(FetchResponse.newBuilder()
            .putVectors("product::123", pineconeVector("product::123", "123", "watches"))
            .build());

        VectorRecord blankUpdate = VectorRecord.builder()
            .vectorId(" ")
            .entityType("product")
            .entityId("blank")
            .embedding(List.of(0.1, 0.2, 0.3))
            .build();
        VectorRecord validUpdate = VectorRecord.builder()
            .vectorId("product::123")
            .entityType("product")
            .entityId("123")
            .content("Updated luxury watch")
            .embedding(List.of(0.2, 0.3, 0.4))
            .metadata(Map.of("category", "watches"))
            .build();

        assertThat(service.batchUpdateVectors(Arrays.asList(null, blankUpdate, validUpdate))).isEqualTo(1);
        verify(index).fetch(eq(List.of("product::123")), eq("product"));
        verify(index).upsert(eq("product::123"), anyList(), isNull(), isNull(), any(Struct.class), eq("product"));

        reset(index);
        assertThat(service.batchRemoveVectors(Arrays.asList(null, " ", "product::123"))).isEqualTo(1);
        verify(index).deleteByIds(eq(List.of("product::123")), eq("product"));
    }

    @Test
    void removeVectorByIdTreatsNamespaceNotFoundAsSuccess() {
        doThrow(new StatusRuntimeException(Status.NOT_FOUND.withDescription("Namespace not found")))
            .when(index)
            .deleteByIds(eq(List.of("product::missing")), eq("product"));

        assertTrue(service.removeVectorById("product::missing"));
    }

    @Test
    void searchTreatsNamespaceNotFoundAsEmptyResultSet() {
        when(index.queryByVector(eq(5), anyList(), eq("product"), nullable(Struct.class), eq(false), eq(true)))
            .thenThrow(new StatusRuntimeException(Status.NOT_FOUND.withDescription("Namespace not found")));

        AISearchResponse response = service.search(
            List.of(0.2, 0.3, 0.4),
            AISearchRequest.builder()
                .query("luxury")
                .entityType("product")
                .limit(5)
                .threshold(0.0)
                .build()
        );

        assertNotNull(response);
        assertEquals(0, response.getTotalResults());
        assertTrue(response.getResults().isEmpty());
    }

    private static AIProviderConfig enabledPineconeConfig() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.PineconeConfig pinecone = config.getPinecone();
        pinecone.setEnabled(true);
        pinecone.setApiKey("test-key");
        pinecone.setApiHost("https://mock-pinecone.test");
        pinecone.setIndexName("test-index");
        pinecone.setEnvironment("test-env");
        pinecone.setDimensions(3);
        return config;
    }

    private static ListItem listItem(String id) {
        return ListItem.newBuilder().setId(id).build();
    }

    private static io.pinecone.proto.Vector pineconeVector(String vectorId, String entityId, String category) {
        Struct metadata = Struct.newBuilder()
            .putFields("entityType", stringValue("product"))
            .putFields("entityId", stringValue(entityId))
            .putFields("content", stringValue("Product " + entityId))
            .putFields("category", stringValue(category))
            .build();
        return io.pinecone.proto.Vector.newBuilder()
            .setId(vectorId)
            .addAllValues(List.of(0.1f, 0.2f, 0.3f))
            .setMetadata(metadata)
            .build();
    }

    private static Value stringValue(String value) {
        return Value.newBuilder().setStringValue(value).build();
    }
}
