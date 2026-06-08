package com.ai.fabric.runtime;

import com.ai.fabric.runtime.config.RuntimeVectorBackendStartupValidator;
import com.ai.infrastructure.rag.VectorDatabaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeVectorBackendStartupValidatorTest {

    @Test
    void failsWhenExternalVectorBackendIsConfiguredWithoutVectorDatabaseService() {
        RuntimeVectorBackendStartupValidator validator = new RuntimeVectorBackendStartupValidator();
        validator.setEnvironment(new MockEnvironment().withProperty("ai.vector-db.type", "pinecone"));

        assertThatThrownBy(() -> validator.postProcessBeanFactory(new DefaultListableBeanFactory()))
            .isInstanceOf(BeanCreationException.class)
            .hasMessageContaining("ai.vector-db.type='pinecone'")
            .hasMessageContaining("VectorDatabaseService");
    }

    @Test
    void allowsNonStrictVectorTypesWithoutVectorDatabaseService() {
        RuntimeVectorBackendStartupValidator validator = new RuntimeVectorBackendStartupValidator();
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

        validator.setEnvironment(new MockEnvironment().withProperty("ai.vector-db.type", "lucene"));
        assertThatCode(() -> validator.postProcessBeanFactory(beanFactory)).doesNotThrowAnyException();

        validator.setEnvironment(new MockEnvironment().withProperty("ai.vector-db.type", "disabled"));
        assertThatCode(() -> validator.postProcessBeanFactory(beanFactory)).doesNotThrowAnyException();

        validator.setEnvironment(new MockEnvironment());
        assertThatCode(() -> validator.postProcessBeanFactory(beanFactory)).doesNotThrowAnyException();
    }

    @Test
    void allowsExternalVectorBackendWhenVectorDatabaseServiceIsPresent() {
        RuntimeVectorBackendStartupValidator validator = new RuntimeVectorBackendStartupValidator();
        validator.setEnvironment(new MockEnvironment().withProperty("ai.vector-db.type", "qdrant"));

        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("vectorDatabaseService", new StubVectorDatabaseService());

        assertThatCode(() -> validator.postProcessBeanFactory(beanFactory)).doesNotThrowAnyException();
    }

    @Test
    void failsFastForUnsupportedVectorType() {
        RuntimeVectorBackendStartupValidator validator = new RuntimeVectorBackendStartupValidator();
        validator.setEnvironment(new MockEnvironment().withProperty("ai.vector-db.type", "zilliz"));

        assertThatThrownBy(() -> validator.postProcessBeanFactory(new DefaultListableBeanFactory()))
            .isInstanceOf(BeanCreationException.class)
            .hasMessageContaining("Unsupported ai.vector-db.type='zilliz'");
    }

    private static final class StubVectorDatabaseService implements VectorDatabaseService {

        @Override
        public String storeVector(String entityType, String entityId, String content, List<Double> embedding, Map<String, Object> metadata) {
            return "vector-1";
        }

        @Override
        public boolean updateVector(String vectorId, String entityType, String entityId, String content, List<Double> embedding, Map<String, Object> metadata) {
            return true;
        }

        @Override
        public Optional<com.ai.infrastructure.dto.VectorRecord> getVector(String vectorId) {
            return Optional.empty();
        }

        @Override
        public Optional<com.ai.infrastructure.dto.VectorRecord> getVectorByEntity(String entityType, String entityId) {
            return Optional.empty();
        }

        @Override
        public com.ai.infrastructure.dto.AISearchResponse search(List<Double> queryVector, com.ai.infrastructure.dto.AISearchRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.ai.infrastructure.dto.AISearchResponse searchByEntityType(List<Double> queryVector, String entityType, int limit, double threshold) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeVector(String entityType, String entityId) {
            return true;
        }

        @Override
        public boolean removeVectorById(String vectorId) {
            return true;
        }

        @Override
        public List<String> batchStoreVectors(List<com.ai.infrastructure.dto.VectorRecord> vectors) {
            return List.of();
        }

        @Override
        public int batchUpdateVectors(List<com.ai.infrastructure.dto.VectorRecord> vectors) {
            return 0;
        }

        @Override
        public int batchRemoveVectors(List<String> vectorIds) {
            return 0;
        }

        @Override
        public List<com.ai.infrastructure.dto.VectorRecord> getVectorsByEntityType(String entityType) {
            return List.of();
        }

        @Override
        public long getVectorCountByEntityType(String entityType) {
            return 0;
        }

        @Override
        public boolean vectorExists(String entityType, String entityId) {
            return true;
        }

        @Override
        public Map<String, Object> getStatistics() {
            return Map.of();
        }

        @Override
        public long clearVectors() {
            return 0;
        }

        @Override
        public long clearVectorsByEntityType(String entityType) {
            return 0;
        }
    }
}
