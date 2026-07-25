package ai.fabric.indexing.observability;

import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.model.AIIndexDocument;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IndexingMetricsTest {

    @Test
    void recordsBoundedLifecycleMetricsWithoutEntityIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IndexingMetrics metrics = new IndexingMetrics(registry);
        AIIndexDocument document = new AIIndexDocument(
            AIIndexDocument.CURRENT_SCHEMA_VERSION,
            "a".repeat(64),
            "product",
            "sensitive-entity-id",
            AIIndexWorkType.UPSERT,
            AIProcessOperation.UPDATE,
            "content",
            "",
            Map.of(),
            Map.of(),
            Map.of(),
            null,
            "sensitive-correlation",
            Instant.EPOCH
        );
        IndexingQueueEntry entry = new IndexingQueueEntry();
        entry.setEntityType("product");
        entry.setEntityId("sensitive-entity-id");
        entry.setStrategy(IndexingStrategy.ASYNC);
        entry.setSourceOperation(AIProcessOperation.UPDATE);

        metrics.accepted(document, IndexingStrategy.ASYNC);
        metrics.completed(entry, Duration.ofMillis(25));
        metrics.failed(entry, false);
        metrics.failed(entry, true);
        metrics.superseded(entry);
        metrics.projectionFailure(
            "product",
            IndexingStrategy.ASYNC,
            AIProcessOperation.UPDATE
        );

        assertThat(registry.getMeters()).hasSize(8);
        assertThat(registry.getMeters())
            .allSatisfy(meter -> {
                assertThat(meter.getId().getTag("entity.type")).isEqualTo("product");
                assertThat(meter.getId().getTags())
                    .noneMatch(tag -> tag.getValue().contains("sensitive"));
            });
    }
}
