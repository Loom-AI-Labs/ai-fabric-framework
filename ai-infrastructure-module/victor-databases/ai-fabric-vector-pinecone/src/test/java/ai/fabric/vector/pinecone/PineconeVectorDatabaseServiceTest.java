package ai.fabric.vector.pinecone;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.pinecone.clients.Index;
import io.pinecone.configs.PineconeConnection;
import io.pinecone.proto.DescribeIndexStatsResponse;
import io.pinecone.proto.FetchResponse;
import io.pinecone.proto.QueryResponse;
import io.pinecone.proto.ScoredVector;
import io.pinecone.proto.UpsertRequest;
import io.pinecone.proto.VectorServiceGrpc;
import io.pinecone.unsigned_indices_model.QueryResponseWithUnsignedIndices;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
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
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.PineconeConfig pinecone = config.getPinecone();
        pinecone.setEnabled(true);
        pinecone.setApiKey("test-key");
        pinecone.setApiHost("https://mock-pinecone.test");
        pinecone.setIndexName("test-index");
        pinecone.setEnvironment("test-env");
        pinecone.setDimensions(3);

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
        assertEquals("watches", captured.getFieldsOrThrow("category").getStringValue());
    }

    @Test
    void storeVectorUsesSparseUpsertWhenIndexIsSparse() {
        when(index.describeIndexStats()).thenReturn(DescribeIndexStatsResponse.newBuilder().setDimension(0).build());

        PineconeConnection connection = mock(PineconeConnection.class);
        VectorServiceGrpc.VectorServiceBlockingStub stub = mock(VectorServiceGrpc.VectorServiceBlockingStub.class);
        when(connection.getBlockingStub()).thenReturn(stub);

        service = new PineconeVectorDatabaseService(new AIProviderConfig() {{
            AIProviderConfig.PineconeConfig pinecone = getPinecone();
            pinecone.setEnabled(true);
            pinecone.setApiKey("test-key");
            pinecone.setApiHost("https://mock-pinecone.test");
            pinecone.setIndexName("test-index");
            pinecone.setEnvironment("test-env");
            pinecone.setDimensions(3);
        }}, connection, index);

        service.storeVector(
            "product",
            "123",
            "Luxury watch",
            List.of(0.1, 0.2, 0.3),
            Map.of("category", "watches")
        );

        ArgumentCaptor<UpsertRequest> requestCaptor = ArgumentCaptor.forClass(UpsertRequest.class);
        verify(stub).upsert(requestCaptor.capture());
        UpsertRequest request = requestCaptor.getValue();
        assertEquals("product", request.getNamespace());
        assertEquals(1, request.getVectorsCount());
        assertEquals("product::123", request.getVectors(0).getId());
        assertEquals(0, request.getVectors(0).getValuesCount());
        assertEquals(List.of(0, 1, 2), request.getVectors(0).getSparseValues().getIndicesList());
        assertEquals(List.of(0.1f, 0.2f, 0.3f), request.getVectors(0).getSparseValues().getValuesList());
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
            .containsEntry("sharedStorage", true)
            .containsEntry("scopeType", "NAMESPACE_PREFIX")
            .containsEntry("rootResourceValue", "tenant-shared-index")
            .containsEntry("scopePrefix", "customer-a--tenant-b")
            .containsEntry("scopePattern", "customer-a--tenant-b__<entity-type>");
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
        metadata.put("sessionId", null);
        metadata.put("piiDetectedTypes", List.of("SSN"));
        metadata.put("tenantId", "t-1");

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

        Assertions.assertFalse(filter.getFieldsMap().containsKey("sessionId"));

        Value tenantCond = filter.getFieldsOrThrow("tenantId");
        assertTrue(tenantCond.hasStructValue());
        assertEquals("t-1", tenantCond.getStructValue().getFieldsOrThrow("$eq").getStringValue());

        Value piiCond = filter.getFieldsOrThrow("piiDetectedTypes");
        assertTrue(piiCond.hasStructValue());
        Value inValue = piiCond.getStructValue().getFieldsOrThrow("$in");
        assertTrue(inValue.hasListValue());
        assertEquals("SSN", inValue.getListValue().getValues(0).getStringValue());
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
}
