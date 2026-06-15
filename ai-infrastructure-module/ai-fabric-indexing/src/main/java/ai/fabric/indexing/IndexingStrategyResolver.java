package ai.fabric.indexing;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIProcess;

import ai.fabric.indexing.api.IndexingStrategy;
/**
 * Resolves the effective indexing strategy for a given entity operation.
 */
public class IndexingStrategyResolver {

    /**
     * Resolve strategy for the supplied entity and process metadata.
     *
     * @param entityClass    entity type annotated with {@link AICapable}
     * @param operation      lifecycle operation
     * @param processMetadata optional method-level metadata (can be {@code null})
     * @return resolved strategy
     */
    public IndexingStrategy resolve(
        Class<?> entityClass,
        IndexingOperation operation,
        AIProcess processMetadata
    ) {
        IndexingConfiguration configuration = configurationFor(entityClass);
        IndexingStrategy methodOverride =
            processMetadata != null ? processMetadata.indexingStrategy() : IndexingStrategy.AUTO;
        return configuration.resolve(operation, methodOverride);
    }

    /**
     * Resolve strategy when only the legacy {@link AIProcess#processType()} value is available.
     */
    public IndexingStrategy resolve(
        Class<?> entityClass,
        String processType,
        AIProcess processMetadata
    ) {
        return resolve(entityClass, mapProcessType(processType), processMetadata);
    }

    /**
     * Extract immutable configuration for the entity.
     */
    public IndexingConfiguration configurationFor(Class<?> entityClass) {
        AICapable annotation = entityClass.getAnnotation(AICapable.class);
        if (annotation == null) {
            throw new IllegalArgumentException(
                "Class %s is not annotated with @AICapable".formatted(entityClass.getName())
            );
        }

        return IndexingConfiguration.builder()
            .defaultStrategy(annotation.indexingStrategy())
            .onCreateStrategy(annotation.onCreateStrategy())
            .onUpdateStrategy(annotation.onUpdateStrategy())
            .onDeleteStrategy(annotation.onDeleteStrategy())
            .build();
    }

    private IndexingOperation mapProcessType(String processType) {
        if (processType == null || processType.isBlank()) {
            return IndexingOperation.CREATE;
        }

        return switch (processType.trim().toLowerCase()) {
            case "create", "insert" -> IndexingOperation.CREATE;
            case "update", "edit" -> IndexingOperation.UPDATE;
            case "delete", "remove" -> IndexingOperation.DELETE;
            default -> IndexingOperation.CREATE;
        };
    }
}
