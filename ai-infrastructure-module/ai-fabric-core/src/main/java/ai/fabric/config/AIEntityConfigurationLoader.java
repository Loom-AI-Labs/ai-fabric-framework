package ai.fabric.config;

import ai.fabric.dto.AIEntityAnalysisPolicy;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIEntityIndexingPolicy;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Binds optional entity policy from Spring Boot Config Data.
 *
 * <p>Applications import modular files with {@code spring.config.import}. This
 * loader does not open annotation-selected resources or maintain a second YAML
 * precedence system.</p>
 */
public class AIEntityConfigurationLoader {

    private final Environment environment;
    private final Map<String, AIEntityConfig> entityConfigs = new LinkedHashMap<>();

    public AIEntityConfigurationLoader(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public synchronized void loadConfiguration() {
        Map<String, AIEntityConfig> bound = Binder.get(environment)
            .bind("ai-entities", Bindable.mapOf(String.class, AIEntityConfig.class))
            .orElseGet(Map::of);

        entityConfigs.clear();
        bound.forEach((key, value) -> {
            String entityType = normalizeEntityType(key);
            AIEntityConfig config = value == null ? new AIEntityConfig() : value;
            if (StringUtils.hasText(config.getEntityType())) {
                throw new IllegalStateException(
                    "Nested ai-entities.%s.entity-type is not supported; "
                        .formatted(entityType)
                        + "the map key is the canonical entity type"
                );
            }
            config.setEntityType(entityType);
            if (config.getIndexing() == null) {
                config.setIndexing(new AIEntityIndexingPolicy());
            }
            if (config.getAnalysis() == null) {
                config.setAnalysis(new AIEntityAnalysisPolicy());
            }
            entityConfigs.put(entityType, config);
        });
    }

    public synchronized void registerEntityConfig(
        String entityType,
        AIEntityConfig config,
        boolean allowOverride
    ) {
        String normalized = normalizeEntityType(entityType);
        if (!allowOverride && entityConfigs.containsKey(normalized)) {
            throw new IllegalStateException("Entity policy already registered for " + normalized);
        }
        AIEntityConfig candidate = config == null ? new AIEntityConfig() : config;
        candidate.setEntityType(normalized);
        if (candidate.getIndexing() == null) {
            candidate.setIndexing(new AIEntityIndexingPolicy());
        }
        if (candidate.getAnalysis() == null) {
            candidate.setAnalysis(new AIEntityAnalysisPolicy());
        }
        entityConfigs.put(normalized, candidate);
    }

    public AIEntityConfig getEntityConfig(String entityType) {
        if (!StringUtils.hasText(entityType)) {
            return null;
        }
        return entityConfigs.get(entityType.trim());
    }

    public boolean hasEntityConfig(String entityType) {
        return getEntityConfig(entityType) != null;
    }

    public Set<String> getSupportedEntityTypes() {
        return Collections.unmodifiableSet(entityConfigs.keySet());
    }

    public Map<String, AIEntityConfig> getEntityConfigs() {
        return Collections.unmodifiableMap(entityConfigs);
    }

    public String getDefaultEmbeddingModel() {
        return environment.getProperty(
            "ai-config.default-embedding-model",
            "text-embedding-3-small"
        );
    }

    public int getDefaultSearchLimit() {
        return environment.getProperty("ai-config.default-search-limit", Integer.class, 10);
    }

    public double getDefaultSimilarityThreshold() {
        return environment.getProperty(
            "ai-config.default-similarity-threshold",
            Double.class,
            0.7
        );
    }

    private String normalizeEntityType(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("entityType is required");
        }
        return value.trim();
    }
}
