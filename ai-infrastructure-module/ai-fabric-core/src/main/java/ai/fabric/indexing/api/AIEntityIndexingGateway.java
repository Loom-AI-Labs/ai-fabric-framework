package ai.fabric.indexing.api;

import ai.fabric.indexing.model.AIIndexDocument;

/**
 * Explicit application boundary for annotation-driven entity indexing.
 *
 * <p>Calls made inside a source-data transaction are persisted atomically with
 * that transaction. Provider work runs only after commit. Calls outside a
 * transaction are accepted in their own durable indexing transaction.</p>
 */
public interface AIEntityIndexingGateway {

    default IndexingOutcome upsert(Object entity) {
        return upsert(entity, AIProcessOperation.UPDATE);
    }

    IndexingOutcome upsert(Object entity, AIProcessOperation operation);

    IndexingOutcome upsert(
        Object entity,
        AIProcessOperation operation,
        IndexingStrategy strategy
    );

    IndexingOutcome delete(Object entity);

    IndexingOutcome delete(Object entity, IndexingStrategy strategy);

    IndexingOutcome delete(Class<?> entityClass, String entityId);

    IndexingOutcome delete(
        Class<?> entityClass,
        String entityId,
        IndexingStrategy strategy
    );

    /**
     * Submits an already approved projection, primarily for trusted YAML-only
     * ingestion paths. This method never performs entity reflection.
     */
    IndexingOutcome submit(AIIndexDocument document, IndexingStrategy strategy);

    AIIndexDocument preview(Object entity, AIProcessOperation operation);
}
