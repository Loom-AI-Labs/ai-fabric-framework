package ai.fabric.vector.milvus;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanRequest;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.CollectionSchema;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.FieldData;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.FlushResponse;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.grpc.GetLoadStateResponse;
import io.milvus.grpc.KeyValuePair;
import io.milvus.grpc.LoadState;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.ScalarField;
import io.milvus.grpc.StringArray;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.param.index.CreateIndexParam;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MilvusVectorDatabaseServiceTest {

    @Test
    void normalizeEntityTypeTokenLowercasesAndTrims() {
        assertThat(MilvusVectorDatabaseService.normalizeEntityTypeToken(" Test-Product "))
            .isEqualTo("test-product");
    }

    @Test
    void normalizeEntityTypeTokenRejectsBlank() {
        assertThatThrownBy(() -> MilvusVectorDatabaseService.normalizeEntityTypeToken("   "))
            .isInstanceOf(ai.fabric.exception.AIServiceException.class);
    }

    @Test
    void toCollectionNameRemovesInvalidCharactersAndIsStable() {
        String collection = MilvusVectorDatabaseService.toCollectionName("test-product");
        assertThat(collection)
            .doesNotContain("-")
            .matches("^[a-z_][a-z0-9_]*$");
    }

    @Test
    void toCollectionNameAvoidsCollisionsBetweenHyphenAndUnderscore() {
        String fromHyphen = MilvusVectorDatabaseService.toCollectionName("test-product");
        String fromUnderscore = MilvusVectorDatabaseService.toCollectionName("test_product");
        assertThat(fromHyphen).isNotEqualTo(fromUnderscore);
    }

    @Test
    void toCollectionNamePrefixesDigitStart() {
        String collection = MilvusVectorDatabaseService.toCollectionName("123abc");
        assertThat(collection).startsWith("c_");
    }

    @Test
    void toCollectionNamePrependsScopedPrefixWhenProvided() {
        String collection = MilvusVectorDatabaseService.toCollectionName("product", "customer_a__tenant_b__");
        assertThat(collection).startsWith("customer_a__tenant_b__");
        assertThat(collection).endsWith("product");
    }

    @Test
    void normalizesSearchLimitAndThresholdEdges() {
        assertThat(MilvusVectorDatabaseService.normalizeSearchLimit(null)).isEqualTo(10);
        assertThat(MilvusVectorDatabaseService.normalizeSearchLimit(0)).isEqualTo(10);
        assertThat(MilvusVectorDatabaseService.normalizeSearchLimit(500)).isEqualTo(100);
        assertThat(MilvusVectorDatabaseService.normalizeSearchLimit(25)).isEqualTo(25);

        assertThat(MilvusVectorDatabaseService.normalizeScoreThreshold(null)).isZero();
        assertThat(MilvusVectorDatabaseService.normalizeScoreThreshold(Double.NaN)).isZero();
        assertThat(MilvusVectorDatabaseService.normalizeScoreThreshold(Double.NEGATIVE_INFINITY)).isZero();
        assertThat(MilvusVectorDatabaseService.normalizeScoreThreshold(-0.5d)).isZero();
        assertThat(MilvusVectorDatabaseService.normalizeScoreThreshold(1.5d)).isEqualTo(1.0d);
        assertThat(MilvusVectorDatabaseService.normalizeScoreThreshold(0.35d)).isEqualTo(0.35d);
    }

    @Test
    void blankVectorIdsReturnEmptyOrFalseWithoutClientCalls() {
        RecordingMilvusService service = new RecordingMilvusService();

        assertThat(service.getVector(null)).isEmpty();
        assertThat(service.getVector(" ")).isEmpty();
        assertThat(service.updateVector(" ", "product", "product-1", "content", List.of(0.1d), Map.of())).isFalse();
        assertThat(service.removeVectorById(" ")).isFalse();
    }

    @Test
    void batchOperationsIgnoreNullRecordsAndBlankVectorIds() {
        RecordingMilvusService service = new RecordingMilvusService();
        VectorRecord valid = VectorRecord.builder()
            .vectorId("product::product-1")
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
        assertThat(service.updatedVectorIds).containsExactly("product::product-1");

        List<String> removeIds = new ArrayList<>();
        removeIds.add(null);
        removeIds.add(" ");
        removeIds.add("product::product-1");
        assertThat(service.batchRemoveVectors(removeIds)).isEqualTo(1);
    }

    @Test
    void updateVectorReturnsFalseWhenCollectionIsMissing() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.hasCollection(any())).thenReturn(R.success(false));
        MilvusVectorDatabaseService service = new MilvusVectorDatabaseService(baseConfig(), client);

        boolean updated = service.updateVector(
            "product::missing-product",
            "product",
            "missing-product",
            "Updated product",
            List.of(0.1d, 0.2d),
            Map.of("category", "watches")
        );

        assertThat(updated).isFalse();
        verify(client).hasCollection(any());
        verify(client, never()).upsert(any());
    }

    @Test
    void storeVectorCreatesCollectionIndexUpsertsAndFlushesWhenConfigured() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.hasCollection(any(HasCollectionParam.class))).thenReturn(R.success(false));
        when(client.createCollection(any(CreateCollectionParam.class))).thenReturn(R.success());
        when(client.createIndex(any(CreateIndexParam.class))).thenReturn(R.success());
        when(client.upsert(any(UpsertParam.class))).thenReturn(R.success(MutationResult.getDefaultInstance()));
        when(client.flush(any(FlushParam.class))).thenReturn(R.success(FlushResponse.getDefaultInstance()));

        AIProviderConfig config = baseConfig();
        config.getMilvus().setFlushOnWrite(true);
        MilvusVectorDatabaseService service = new MilvusVectorDatabaseService(config, client);

        String vectorId = service.storeVector(
            "product",
            "p-1",
            "Waterproof shell jacket",
            List.of(0.1d, 0.2d, 0.3d),
            Map.of("category", "outerwear")
        );

        String collection = MilvusVectorDatabaseService.toCollectionName("product");
        assertThat(vectorId).isEqualTo("product::p-1");

        ArgumentCaptor<CreateCollectionParam> createCaptor = ArgumentCaptor.forClass(CreateCollectionParam.class);
        verify(client).createCollection(createCaptor.capture());
        assertThat(createCaptor.getValue().getCollectionName()).isEqualTo(collection);
        assertThat(createCaptor.getValue().getFieldTypes())
            .anySatisfy(field -> {
                assertThat(field.getName()).isEqualTo("vector_id");
                assertThat(field.isPrimaryKey()).isTrue();
            })
            .anySatisfy(field -> {
                assertThat(field.getName()).isEqualTo("embedding");
                assertThat(field.getDimension()).isEqualTo(3);
            });

        ArgumentCaptor<CreateIndexParam> indexCaptor = ArgumentCaptor.forClass(CreateIndexParam.class);
        verify(client).createIndex(indexCaptor.capture());
        assertThat(indexCaptor.getValue().getCollectionName()).isEqualTo(collection);
        assertThat(indexCaptor.getValue().getFieldName()).isEqualTo("embedding");
        assertThat(indexCaptor.getValue().getIndexName()).isEqualTo(collection + "_embedding_idx");

        ArgumentCaptor<UpsertParam> upsertCaptor = ArgumentCaptor.forClass(UpsertParam.class);
        verify(client).upsert(upsertCaptor.capture());
        assertThat(upsertCaptor.getValue().getCollectionName()).isEqualTo(collection);
        assertThat(upsertCaptor.getValue().getRowCount()).isEqualTo(1);
        assertThat(upsertCaptor.getValue().getFields())
            .extracting(UpsertParam.Field::getName)
            .containsExactly("vector_id", "entity_id", "content", "metadata", "embedding");
        assertThat(upsertCaptor.getValue().getFields())
            .filteredOn(field -> "metadata".equals(field.getName()))
            .singleElement()
            .satisfies(field -> assertThat(field.getValues().getFirst().toString())
                .contains("_indexedCreatedAt")
                .contains("_indexedUpdatedAt"));

        ArgumentCaptor<FlushParam> flushCaptor = ArgumentCaptor.forClass(FlushParam.class);
        verify(client).flush(flushCaptor.capture());
        assertThat(flushCaptor.getValue().getCollectionNames()).containsExactly(collection);
    }

    @Test
    void storeVectorRejectsExistingCollectionDimensionMismatch() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.hasCollection(any(HasCollectionParam.class))).thenReturn(R.success(true));
        when(client.describeCollection(any())).thenReturn(R.success(describeCollectionWithDimension(2)));
        MilvusVectorDatabaseService service = new MilvusVectorDatabaseService(baseConfig(), client);

        assertThatThrownBy(() -> service.storeVector(
            "product",
            "p-1",
            "Waterproof shell jacket",
            List.of(0.1d, 0.2d, 0.3d),
            Map.of("category", "outerwear")
        ))
            .isInstanceOf(ai.fabric.exception.AIServiceException.class)
            .hasMessageContaining("was created with dimension 2 but 3 was provided");

        verify(client, never()).upsert(any());
        verify(client, never()).createIndex(any());
    }

    @Test
    void searchUsesImpossibleExpressionForUnsupportedMetadata() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        mockReadyCollection(client, 2);
        when(client.search(any(SearchParam.class)))
            .thenReturn(R.failed(R.Status.UnexpectedError, "mock search stopped after expression capture"));

        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("tenant", "retail");
        metadata.put("tags", List.of("public"));

        MilvusVectorDatabaseService service = new MilvusVectorDatabaseService(baseConfig(), client);

        assertThatThrownBy(() -> service.search(List.of(0.1d, 0.2d), AISearchRequest.builder()
                .query("jackets")
                .entityType("product")
                .metadata(metadata)
                .limit(5)
                .build()))
            .isInstanceOf(ai.fabric.exception.AIServiceException.class)
            .hasMessageContaining("execute search");

        ArgumentCaptor<SearchParam> searchCaptor = ArgumentCaptor.forClass(SearchParam.class);
        verify(client).search(searchCaptor.capture());
        assertThat(searchCaptor.getValue().getExpr())
            .contains("vector_id != \"\"")
            .contains("vector_id == \"__ai_fabric_no_match__\"")
            .doesNotContain("\"tenant\":\"retail\"");
    }

    @Test
    void searchBuildsMetadataExpressionFromStoredJsonStringShape() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        mockReadyCollection(client, 2);
        when(client.search(any(SearchParam.class)))
            .thenReturn(R.failed(R.Status.UnexpectedError, "mock search stopped after expression capture"));

        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("tenant", "retail");
        metadata.put("rank", 7);
        metadata.put("featured", true);
        metadata.put("sku", "SKU_100%");

        MilvusVectorDatabaseService service = new MilvusVectorDatabaseService(baseConfig(), client);

        assertThatThrownBy(() -> service.search(List.of(0.1d, 0.2d), AISearchRequest.builder()
                .query("jackets")
                .entityType("product")
                .metadata(metadata)
                .limit(5)
                .build()))
            .isInstanceOf(ai.fabric.exception.AIServiceException.class)
            .hasMessageContaining("execute search");

        ArgumentCaptor<SearchParam> searchCaptor = ArgumentCaptor.forClass(SearchParam.class);
        verify(client).search(searchCaptor.capture());
        assertThat(searchCaptor.getValue().getExpr())
            .contains("vector_id != \"\"")
            .contains("\\\"tenant\\\":\\\"retail\\\"")
            .contains("\\\"rank\\\":\\\"7\\\"")
            .contains("\\\"featured\\\":\\\"true\\\"")
            .contains("\\\"sku\\\":\\\"SKU\\_100\\%\\\"")
            .doesNotContain("\\\"rank\\\":7")
            .doesNotContain("\\\"featured\\\":true")
            .doesNotContain("SKU_100%");
    }

    @Test
    void scanUsesImpossibleExpressionForUnsupportedMetadata() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        mockReadyCollection(client, 2);
        when(client.query(any(QueryParam.class))).thenReturn(R.success(QueryResults.getDefaultInstance()));

        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("tenant", "retail");
        metadata.put("providerOperator", Map.of("$eq", "retail"));

        MilvusVectorDatabaseService service = new MilvusVectorDatabaseService(baseConfig(), client);

        service.scan(VectorScanRequest.builder()
            .entityType("product")
            .metadataEquals(metadata)
            .limit(5)
            .build());

        ArgumentCaptor<QueryParam> queryCaptor = ArgumentCaptor.forClass(QueryParam.class);
        verify(client).query(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getExpr())
            .contains("vector_id != \"\"")
            .contains("vector_id == \"__ai_fabric_no_match__\"")
            .doesNotContain("\"tenant\":\"retail\"");
    }

    @Test
    void scanBuildsMetadataExpressionFromStoredJsonStringShape() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        mockReadyCollection(client, 2);
        when(client.query(any(QueryParam.class))).thenReturn(R.success(QueryResults.getDefaultInstance()));

        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("tenant", "retail");
        metadata.put("rank", 7);
        metadata.put("featured", true);

        MilvusVectorDatabaseService service = new MilvusVectorDatabaseService(baseConfig(), client);

        service.scan(VectorScanRequest.builder()
            .entityType("product")
            .metadataEquals(metadata)
            .limit(5)
            .build());

        ArgumentCaptor<QueryParam> queryCaptor = ArgumentCaptor.forClass(QueryParam.class);
        verify(client).query(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getExpr())
            .contains("vector_id != \"\"")
            .contains("\\\"tenant\\\":\\\"retail\\\"")
            .contains("\\\"rank\\\":\\\"7\\\"")
            .contains("\\\"featured\\\":\\\"true\\\"")
            .doesNotContain("\\\"rank\\\":7")
            .doesNotContain("\\\"featured\\\":true");
    }

    @Test
    void removeVectorByIdReturnsFalseWhenScopedReadMisses() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        mockReadyCollection(client, 2);
        when(client.query(any(QueryParam.class))).thenReturn(R.success(QueryResults.getDefaultInstance()));

        MilvusVectorDatabaseService service = new MilvusVectorDatabaseService(baseConfig(), client);

        assertThat(service.removeVectorById("product::missing")).isFalse();
        verify(client).query(any(QueryParam.class));
        verify(client, never()).delete(any(DeleteParam.class));
    }

    @Test
    void clearByEntityTypeDropsCollectionAndReturnsVisibleRowCount() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        mockReadyCollection(client, 3);
        when(client.getCollectionStatistics(any(GetCollectionStatisticsParam.class))).thenReturn(R.success(
            GetCollectionStatisticsResponse.newBuilder()
                .addStats(KeyValuePair.newBuilder().setKey("row_count").setValue("3").build())
                .build()
        ));
        when(client.query(any(QueryParam.class))).thenReturn(R.success(
            queryResultsWithVectorIds("product::p-1", "product::p-2")
        ));
        when(client.dropCollection(any(DropCollectionParam.class))).thenReturn(R.success());
        MilvusVectorDatabaseService service = new MilvusVectorDatabaseService(baseConfig(), client);

        long removed = service.clearVectorsByEntityType("product");

        String collection = MilvusVectorDatabaseService.toCollectionName("product");
        assertThat(removed).isEqualTo(2L);

        ArgumentCaptor<DropCollectionParam> dropCaptor = ArgumentCaptor.forClass(DropCollectionParam.class);
        verify(client).dropCollection(dropCaptor.capture());
        assertThat(dropCaptor.getValue().getCollectionName()).isEqualTo(collection);
        verify(client).query(any(QueryParam.class));
        verify(client, never()).getCollectionStatistics(any(GetCollectionStatisticsParam.class));
    }

    @Test
    void countByEntityTypeUsesVisibleRowScanForLifecycleAccuracy() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        mockReadyCollection(client, 3);
        when(client.getCollectionStatistics(any(GetCollectionStatisticsParam.class))).thenReturn(R.success(
            GetCollectionStatisticsResponse.newBuilder()
                .addStats(KeyValuePair.newBuilder().setKey("row_count").setValue("12").build())
                .build()
        ));
        when(client.query(any(QueryParam.class))).thenReturn(R.success(
            queryResultsWithVectorIds("product::p-1", "product::p-2")
        ));
        MilvusVectorDatabaseService service = new MilvusVectorDatabaseService(baseConfig(), client);

        long count = service.getVectorCountByEntityType("product");

        assertThat(count).isEqualTo(2L);
        verify(client).query(any(QueryParam.class));
        verify(client, never()).getCollectionStatistics(any(GetCollectionStatisticsParam.class));
    }

    @Test
    void countByEntityTypeReturnsZeroWhenCollectionIsMissing() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.hasCollection(any(HasCollectionParam.class))).thenReturn(R.success(false));
        MilvusVectorDatabaseService service = new MilvusVectorDatabaseService(baseConfig(), client);

        long count = service.getVectorCountByEntityType("product");

        assertThat(count).isZero();
        verify(client, never()).getCollectionStatistics(any(GetCollectionStatisticsParam.class));
        verify(client, never()).query(any(QueryParam.class));
    }

    @Test
    void countByEntityTypeReturnsZeroWhenVisibleScanFindsNoRows() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        mockReadyCollection(client, 3);
        when(client.query(any(QueryParam.class))).thenReturn(R.success(QueryResults.getDefaultInstance()));
        MilvusVectorDatabaseService service = new MilvusVectorDatabaseService(baseConfig(), client);

        long count = service.getVectorCountByEntityType("product");

        assertThat(count).isZero();
        verify(client).query(any(QueryParam.class));
    }

    @Test
    void countByEntityTypePropagatesVisibleCountFailures() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        mockReadyCollection(client, 3);
        when(client.query(any(QueryParam.class)))
            .thenReturn(R.failed(R.Status.UnexpectedError, "query unavailable"));
        MilvusVectorDatabaseService service = new MilvusVectorDatabaseService(baseConfig(), client);

        assertThatThrownBy(() -> service.getVectorCountByEntityType("product"))
            .isInstanceOf(ai.fabric.exception.AIServiceException.class)
            .hasMessageContaining("count visible vectors");

        verify(client).query(any(QueryParam.class));
    }

    @Test
    void clearByEntityTypeFallsBackToStatisticsWhenVisibleCountFails() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        mockReadyCollection(client, 3);
        when(client.getCollectionStatistics(any(GetCollectionStatisticsParam.class))).thenReturn(R.success(
            GetCollectionStatisticsResponse.newBuilder()
                .addStats(KeyValuePair.newBuilder().setKey("row_count").setValue("3").build())
                .build()
        ));
        when(client.query(any(QueryParam.class)))
            .thenReturn(R.failed(R.Status.UnexpectedError, "query unavailable"));
        when(client.dropCollection(any(DropCollectionParam.class))).thenReturn(R.success());
        MilvusVectorDatabaseService service = new MilvusVectorDatabaseService(baseConfig(), client);

        long removed = service.clearVectorsByEntityType("product");

        assertThat(removed).isEqualTo(3L);
        verify(client).query(any(QueryParam.class));
        verify(client).getCollectionStatistics(any(GetCollectionStatisticsParam.class));
    }

    @Test
    void adminDiagnosticsExposeStableCapabilityKeysAndCollectionScope() {
        AIProviderConfig config = baseConfig();
        config.getMilvus().setCollectionPrefix("customer_a__tenant_b__");
        config.getMilvus().setDatabaseName("default");
        MilvusVectorDatabaseService service = new MilvusVectorDatabaseService(config, mock(MilvusServiceClient.class));

        assertThat(service.adminDiagnostics())
            .containsEntry("provider", "milvus")
            .containsEntry("sharedStorage", true)
            .containsEntry("scopeType", "COLLECTION_PREFIX")
            .containsEntry("rootResourceValue", "localhost")
            .containsEntry("databaseName", "default")
            .containsEntry("scopePrefix", "customer_a__tenant_b__")
            .containsEntry("scopePattern", "default/customer_a__tenant_b__<entity_type>")
            .containsEntry("supportsVectorScan", true)
            .containsEntry("supportsSearchMetadataFiltering", true)
            .containsEntry("supportsScanMetadataFiltering", true)
            .containsEntry("supportsExactFetchById", true)
            .containsEntry("supportsClearByEntityType", true)
            .containsEntry("supportsEfficientEntityTypeCount", false)
            .containsEntry("metadataFilteredSearch", true)
            .containsEntry("metadataFilteredScan", true)
            .containsEntry("searchFilterMode", "milvus-json-expression")
            .containsEntry("scanFilterMode", "milvus-json-expression")
            .containsEntry("countMode", "milvus-visible-row-scan")
            .containsEntry("clearMode", "milvus-drop-collection")
            .containsEntry("countFallbacks", Map.of())
            .containsEntry("countFallbackReasons", Map.of());
    }

    private static AIProviderConfig baseConfig() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.MilvusConfig milvus = config.getMilvus();
        milvus.setEnabled(true);
        milvus.setHost("localhost");
        milvus.setPort(19530);
        return config;
    }

    private static DescribeCollectionResponse describeCollectionWithDimension(int dimension) {
        return DescribeCollectionResponse.newBuilder()
            .setSchema(CollectionSchema.newBuilder()
                .addFields(FieldSchema.newBuilder()
                    .setName("embedding")
                    .addTypeParams(KeyValuePair.newBuilder()
                        .setKey("dim")
                        .setValue(Integer.toString(dimension))
                        .build())
                    .build())
                .build())
            .build();
    }

    private static void mockReadyCollection(MilvusServiceClient client, int dimension) {
        when(client.hasCollection(any(HasCollectionParam.class))).thenReturn(R.success(true));
        when(client.describeCollection(any())).thenReturn(R.success(describeCollectionWithDimension(dimension)));
        when(client.loadCollection(any())).thenReturn(R.success());
        when(client.getLoadState(any())).thenReturn(R.success(GetLoadStateResponse.newBuilder()
            .setState(LoadState.LoadStateLoaded)
            .build()));
    }

    private static QueryResults queryResultsWithVectorIds(String... vectorIds) {
        return QueryResults.newBuilder()
            .addOutputFields("vector_id")
            .addFieldsData(FieldData.newBuilder()
                .setFieldName("vector_id")
                .setType(DataType.VarChar)
                .setScalars(ScalarField.newBuilder()
                    .setStringData(StringArray.newBuilder()
                        .addAllData(List.of(vectorIds))
                        .build())
                    .build())
                .build())
            .build();
    }

    private static final class RecordingMilvusService extends MilvusVectorDatabaseService {
        private final List<String> updatedVectorIds = new ArrayList<>();

        private RecordingMilvusService() {
            super(baseConfig(), mock(MilvusServiceClient.class));
        }

        @Override
        public String storeVector(String entityType, String entityId, String content,
                                  List<Double> embedding, Map<String, Object> metadata) {
            return "stored-" + entityId;
        }

        @Override
        public boolean updateVector(String vectorId, String entityType, String entityId,
                                    String content, List<Double> embedding, Map<String, Object> metadata) {
            if (vectorId == null || vectorId.isBlank()) {
                return false;
            }
            updatedVectorIds.add(vectorId);
            return true;
        }

        @Override
        public Optional<VectorRecord> getVector(String vectorId) {
            return super.getVector(vectorId);
        }

        @Override
        public boolean removeVectorById(String vectorId) {
            return vectorId != null && !vectorId.isBlank();
        }
    }
}
