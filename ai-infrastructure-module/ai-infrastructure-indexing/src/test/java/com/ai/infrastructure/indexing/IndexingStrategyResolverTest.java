package com.ai.infrastructure.indexing;

import com.ai.infrastructure.annotation.AICapable;
import com.ai.infrastructure.annotation.AIProcess;
import com.ai.infrastructure.indexing.api.IndexingStrategy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexingStrategyResolverTest {

    private final IndexingStrategyResolver resolver = new IndexingStrategyResolver();

    @Test
    void configurationRequiresAICapableAnnotation() {
        assertThatThrownBy(() -> resolver.configurationFor(String.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not annotated with @AICapable");
    }

    @Test
    void resolvesOperationSpecificStrategy() {
        IndexingStrategy strategy = resolver.resolve(
            OrderEntity.class,
            IndexingOperation.DELETE,
            null
        );

        assertThat(strategy).isEqualTo(IndexingStrategy.SYNC);
    }

    @Test
    void resolvesUsingLegacyProcessTypeValue() {
        IndexingStrategy strategy = resolver.resolve(
            ProductEntity.class,
            "update",
            null
        );

        assertThat(strategy).isEqualTo(IndexingStrategy.BATCH);
    }

    @Test
    void methodOverrideViaAIProcessWins() throws Exception {
        Method method = ProcessMetadataProvider.class.getDeclaredMethod("inventoryUpdate");
        AIProcess metadata = method.getAnnotation(AIProcess.class);

        IndexingStrategy strategy = resolver.resolve(
            InventoryEntity.class,
            IndexingOperation.UPDATE,
            metadata
        );

        assertThat(strategy).isEqualTo(IndexingStrategy.SYNC);
    }

    @AICapable(
        entityType = "order",
        indexingStrategy = IndexingStrategy.BATCH,
        onDeleteStrategy = IndexingStrategy.SYNC
    )
    private static class OrderEntity {
    }

    @AICapable(
        entityType = "product",
        indexingStrategy = IndexingStrategy.ASYNC,
        onUpdateStrategy = IndexingStrategy.BATCH
    )
    private static class ProductEntity {
    }

    @AICapable(
        entityType = "inventory",
        indexingStrategy = IndexingStrategy.BATCH
    )
    private static class InventoryEntity {
    }

    private static class ProcessMetadataProvider {

        @AIProcess(
            entityType = "inventory",
            processType = "update",
            indexingStrategy = IndexingStrategy.SYNC
        )
        void inventoryUpdate() {
        }
    }
}
