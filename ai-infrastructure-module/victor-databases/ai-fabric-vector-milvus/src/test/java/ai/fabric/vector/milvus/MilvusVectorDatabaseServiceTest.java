package ai.fabric.vector.milvus;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.VectorRecord;
import io.milvus.client.MilvusServiceClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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

    private static AIProviderConfig baseConfig() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.MilvusConfig milvus = config.getMilvus();
        milvus.setEnabled(true);
        milvus.setHost("localhost");
        milvus.setPort(19530);
        return config;
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
