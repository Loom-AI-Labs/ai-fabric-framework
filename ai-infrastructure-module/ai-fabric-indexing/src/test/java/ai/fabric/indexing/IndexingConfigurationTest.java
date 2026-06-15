package ai.fabric.indexing;

import ai.fabric.indexing.api.IndexingStrategy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IndexingConfigurationTest {

    @Test
    void methodOverrideTakesPrecedence() {
        IndexingConfiguration configuration = IndexingConfiguration.builder()
            .defaultStrategy(IndexingStrategy.BATCH)
            .onCreateStrategy(IndexingStrategy.ASYNC)
            .build();

        IndexingStrategy resolved = configuration.resolve(
            IndexingOperation.CREATE,
            IndexingStrategy.SYNC
        );

        assertThat(resolved).isEqualTo(IndexingStrategy.SYNC);
    }

    @Test
    void operationOverrideFallsBackToEntityDefaultWhenAuto() {
        IndexingConfiguration configuration = IndexingConfiguration.builder()
            .defaultStrategy(IndexingStrategy.ASYNC)
            .onUpdateStrategy(IndexingStrategy.AUTO)
            .build();

        IndexingStrategy resolved = configuration.resolve(
            IndexingOperation.UPDATE,
            IndexingStrategy.AUTO
        );

        assertThat(resolved).isEqualTo(IndexingStrategy.ASYNC);
    }

    @Test
    void operationSpecificStrategyWinsWhenProvided() {
        IndexingConfiguration configuration = IndexingConfiguration.builder()
            .defaultStrategy(IndexingStrategy.BATCH)
            .onDeleteStrategy(IndexingStrategy.SYNC)
            .build();

        IndexingStrategy resolved = configuration.resolve(
            IndexingOperation.DELETE,
            IndexingStrategy.AUTO
        );

        assertThat(resolved).isEqualTo(IndexingStrategy.SYNC);
    }
}
