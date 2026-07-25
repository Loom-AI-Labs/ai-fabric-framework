package ai.fabric.indexing.model;

import ai.fabric.indexing.api.EntityIdentityResolver;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical immutable entity contract used by every indexing ingress.
 */
public record AIEntityDescriptor(
    Class<?> entityClass,
    String entityType,
    EntityIdentityResolver identityResolver,
    String identitySource,
    List<AISearchFieldDescriptor> searchableFields,
    List<AIContextFieldDescriptor> contextFields,
    boolean indexingEnabled,
    int projectionMaxCharacters,
    AIAnalysisPolicy analysisPolicy,
    IndexingStrategy defaultStrategy,
    IndexingStrategy createStrategy,
    IndexingStrategy updateStrategy,
    IndexingStrategy deleteStrategy,
    Class<? extends JpaRepository<?, ?>> migrationRepository,
    String projectionHash,
    Set<AIEntityCapability> effectiveCapabilities,
    Map<String, String> configurationSources
) {
    public AIEntityDescriptor {
        searchableFields = List.copyOf(searchableFields);
        contextFields = List.copyOf(contextFields);
        effectiveCapabilities = Set.copyOf(effectiveCapabilities);
        configurationSources = Map.copyOf(configurationSources);
    }

    public IndexingStrategy strategyFor(AIProcessOperation operation) {
        IndexingStrategy configured = switch (operation) {
            case CREATE -> createStrategy;
            case UPDATE -> updateStrategy;
            case DELETE -> deleteStrategy;
        };
        return configured == IndexingStrategy.AUTO ? defaultStrategy : configured;
    }
}
