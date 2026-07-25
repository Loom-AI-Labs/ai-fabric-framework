package ai.fabric.indexing.observability;

import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.model.AIIndexDocument;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.List;

/**
 * Bounded-cardinality indexing metrics. Entity IDs and failure messages are
 * deliberately excluded from all tags.
 */
public class IndexingMetrics {

    private static final String PROVIDER = "configured";

    private final MeterRegistry registry;

    public IndexingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void accepted(AIIndexDocument document, IndexingStrategy strategy) {
        increment(
            "aifabric.indexing.accepted",
            tags(document.entityType(), strategy, document.sourceOperation())
        );
    }

    public void completed(IndexingQueueEntry entry, Duration duration) {
        List<Tag> tags = tags(entry);
        increment("aifabric.indexing.completed", tags);
        if (registry != null) {
            Timer.builder("aifabric.indexing.duration")
                .tags(tags)
                .register(registry)
                .record(duration.isNegative() ? Duration.ZERO : duration);
        }
    }

    public void failed(IndexingQueueEntry entry, boolean deadLettered) {
        List<Tag> tags = tags(entry);
        increment("aifabric.indexing.failed", tags);
        if (deadLettered) {
            increment("aifabric.indexing.dead_lettered", tags);
        } else {
            increment("aifabric.indexing.retried", tags);
        }
    }

    public void superseded(IndexingQueueEntry entry) {
        increment("aifabric.indexing.superseded", tags(entry));
    }

    public void projectionFailure(
        String entityType,
        IndexingStrategy strategy,
        AIProcessOperation operation
    ) {
        increment(
            "aifabric.indexing.projection_failures",
            tags(entityType, strategy, operation)
        );
    }

    private List<Tag> tags(IndexingQueueEntry entry) {
        return tags(
            entry.getEntityType(),
            entry.getStrategy(),
            entry.getSourceOperation()
        );
    }

    private List<Tag> tags(
        String entityType,
        IndexingStrategy strategy,
        AIProcessOperation operation
    ) {
        return List.of(
            Tag.of("provider", PROVIDER),
            Tag.of("strategy", strategy == null ? "unknown" : strategy.name()),
            Tag.of("operation", operation == null ? "unknown" : operation.name()),
            Tag.of(
                "entity.type",
                entityType == null || entityType.isBlank() ? "unknown" : entityType
            )
        );
    }

    private void increment(String name, List<Tag> tags) {
        if (registry != null) {
            registry.counter(name, tags).increment();
        }
    }
}
