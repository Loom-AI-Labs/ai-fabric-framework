package com.ai.infrastructure.config;

import com.ai.infrastructure.dto.AIEntityConfig;
import com.ai.infrastructure.dto.AISearchableField;
import com.ai.infrastructure.dto.AIEmbeddableField;
import com.ai.infrastructure.dto.AIMetadataField;
import com.ai.infrastructure.dto.AICrudOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI Entity Configuration Loader
 * 
 * Loads and parses AI entity configuration from YAML files.
 * Provides configuration lookup for AI processing.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Slf4j
public class AIEntityConfigurationLoader {
    
    private final ResourceLoader resourceLoader;
    private final Map<String, AIEntityConfig> entityConfigs = new ConcurrentHashMap<>();
    private final Map<String, Object> globalConfig = new ConcurrentHashMap<>();
    
    @Value("${ai.config.default-file:ai-entity-config.yml}")
    private String defaultConfigFile;
    
    public AIEntityConfigurationLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }
    
    @PostConstruct
    public void loadConfiguration() {
        try {
            log.info("Loading AI entity configuration from: {}", defaultConfigFile);
            loadConfigurationFromFile(defaultConfigFile);
            log.info("Successfully loaded configuration for {} entities", entityConfigs.size());
        } catch (Exception e) {
            log.error("Failed to load AI entity configuration", e);
            throw new RuntimeException("Failed to load AI entity configuration", e);
        }
    }
    
    /**
     * Load configuration from file with override control.
     *
     * @param configFile file path (supports classpath: prefix)
     * @param allowOverride when false, skip entities already defined
     */
    public void loadConfigurationFromFile(String configFile, boolean allowOverride) {
        try {
            Resource resource = resolveResource(configFile);
            if (resource == null) {
                log.warn("Configuration file not found: {}", configFile);
                return;
            }
            
            Map<String, Object> config;
            try (InputStream inputStream = resource.getInputStream()) {
                Yaml yaml = new Yaml();
                config = yaml.load(inputStream);
            } catch (FileNotFoundException ex) {
                log.warn("Configuration file not found: {}", configFile);
                return;
            }
            
            if (config == null) {
                log.warn("Configuration file {} is empty", configFile);
                return;
            }
            
            // Load global configuration
            if (config.containsKey("ai-config") && allowOverride) {
                globalConfig.putAll((Map<String, Object>) config.get("ai-config"));
            }
            
            // Load entity configurations
            if (config.containsKey("ai-entities")) {
                Map<String, Object> entities = (Map<String, Object>) config.get("ai-entities");
                log.info("Found {} entities in configuration", entities.size());
                for (Map.Entry<String, Object> entry : entities.entrySet()) {
                    String entityType = entry.getKey();
                    Map<String, Object> entityConfig = (Map<String, Object>) entry.getValue();
                    log.info("Processing entity type: {} with config keys: {}", entityType, entityConfig.keySet());
                    
                    if (!allowOverride && entityConfigs.containsKey(entityType)) {
                        log.debug("Skipping {} - existing configuration preserved", entityType);
                        continue;
                    }
                    
                    AIEntityConfig configObj = parseEntityConfig(entityType, entityConfig);
                    entityConfigs.put(entityType, configObj);
                }
            } else {
                log.warn("No ai-entities found in configuration");
            }
            
        } catch (Exception e) {
            log.error("Failed to load configuration from file: {}", configFile, e);
            throw new RuntimeException("Failed to load configuration from file: " + configFile, e);
        }
    }
    
    /**
     * Backward-compatible loader (defaults to allowing overrides).
     */
    public void loadConfigurationFromFile(String configFile) {
        loadConfigurationFromFile(configFile, true);
    }

    private Resource resolveResource(String configFile) {
        String location = configFile == null ? "" : configFile.trim();
        if (!StringUtils.hasText(location)) {
            return null;
        }
        if (ResourceUtils.isUrl(location)) {
            return resourceLoader.getResource(location);
        }
        return resourceLoader.getResource("classpath:" + location);
    }
    
    private AIEntityConfig parseEntityConfig(String entityType, Map<String, Object> config) {
        log.debug("Parsing entity config for type: {} with keys: {}", entityType, config.keySet());
        AIEntityConfig.AIEntityConfigBuilder builder = AIEntityConfig.builder()
            .entityType(entityType)
            .features((List<String>) config.getOrDefault("features", Arrays.asList("embedding", "search")))
            .autoProcess((Boolean) config.getOrDefault("auto-process", true))
            .enableSearch((Boolean) config.getOrDefault("enable-search", true))
            .enableRecommendations((Boolean) config.getOrDefault("enable-recommendations", false))
            .autoEmbedding((Boolean) config.getOrDefault("auto-embedding", true))
            .indexable((Boolean) config.getOrDefault("indexable", true));
        
        // Parse searchable fields
        if (config.containsKey("searchable-fields")) {
            List<Map<String, Object>> searchableFields = (List<Map<String, Object>>) config.get("searchable-fields");
            List<AISearchableField> fields = new ArrayList<>();
            for (Map<String, Object> field : searchableFields) {
                fields.add(AISearchableField.builder()
                    .name((String) field.get("name"))
                    .includeInRAG((Boolean) field.getOrDefault("include-in-rag", true))
                    .enableSemanticSearch((Boolean) field.getOrDefault("enable-semantic-search", true))
                    .weight(((Number) field.getOrDefault("weight", 1.0)).doubleValue())
                    .build());
            }
            builder.searchableFields(fields);
        }
        
        // Parse embeddable fields
        if (config.containsKey("embeddable-fields")) {
            List<Map<String, Object>> embeddableFields = (List<Map<String, Object>>) config.get("embeddable-fields");
            List<AIEmbeddableField> fields = new ArrayList<>();
            for (Map<String, Object> field : embeddableFields) {
                fields.add(AIEmbeddableField.builder()
                    .name((String) field.get("name"))
                    .model((String) field.getOrDefault("model", "text-embedding-3-small"))
                    .autoGenerate((Boolean) field.getOrDefault("auto-generate", true))
                    .includeInSimilarity((Boolean) field.getOrDefault("include-in-similarity", true))
                    .build());
            }
            builder.embeddableFields(fields);
        }
        
        // Parse metadata fields
        if (config.containsKey("metadata-fields")) {
            List<Map<String, Object>> metadataFields = (List<Map<String, Object>>) config.get("metadata-fields");
            log.info("Found {} metadata fields for entity {}", metadataFields.size(), entityType);
            List<AIMetadataField> fields = new ArrayList<>();
            for (Map<String, Object> field : metadataFields) {
                fields.add(AIMetadataField.builder()
                    .name((String) field.get("name"))
                    .type((String) field.get("type"))
                    .includeInSearch((Boolean) field.getOrDefault("include-in-search", true))
                    .build());
            }
            builder.metadataFields(fields);
            log.info("Successfully parsed {} metadata fields", fields.size());
        } else {
            log.info("No metadata-fields found for entity {}", entityType);
        }
        
        // Parse CRUD operations
        if (config.containsKey("crud-operations")) {
            Map<String, Object> crudOps = (Map<String, Object>) config.get("crud-operations");
            Map<String, AICrudOperation> operations = new HashMap<>();
            
            for (Map.Entry<String, Object> entry : crudOps.entrySet()) {
                String operation = entry.getKey().toLowerCase(java.util.Locale.ROOT);
                Map<String, Object> opConfig = (Map<String, Object>) entry.getValue();
                operations.put(operation, AICrudOperation.builder()
                    .operation(operation)
                    .generateEmbedding((Boolean) opConfig.getOrDefault("generate-embedding", true))
                    .indexForSearch((Boolean) opConfig.getOrDefault("index-for-search", true))
                    .enableAnalysis((Boolean) opConfig.getOrDefault("enable-analysis", false))
                    .removeFromSearch((Boolean) opConfig.getOrDefault("remove-from-search", true))
                    .cleanupEmbeddings((Boolean) opConfig.getOrDefault("cleanup-embeddings", true))
                    .build());
            }
            builder.crudOperations(operations);
        }
        
        return builder.build();
    }
    
    public AIEntityConfig getEntityConfig(String entityType) {
        AIEntityConfig config = entityConfigs.get(entityType);
        log.debug("getEntityConfig called for entityType: {}, config: {}, metadataFields: {}", 
            entityType, 
            config != null ? "found" : "null",
            config != null && config.getMetadataFields() != null ? config.getMetadataFields().size() : "null");
        return config;
    }

    /**
     * Merge additional metadata field definitions into an existing entity config.
     *
     * <p>Intended for annotation-driven @AIContext schemas, where YAML remains the
     * primary source of truth. Existing fields are preserved (YAML wins), while
     * missing fields are appended. If an existing field is missing a type, the
     * provided type is applied.</p>
     *
     * @param entityType entity type key
     * @param additionalFields additional metadata fields to merge
     * @param createIfMissing when true, create a minimal config when none exists
     * @return the updated config (or null if no config exists and createIfMissing=false)
     */
    public AIEntityConfig mergeMetadataFields(String entityType,
                                              List<AIMetadataField> additionalFields,
                                              boolean createIfMissing) {
        if (entityType == null || entityType.isBlank()) {
            return null;
        }
        if (additionalFields == null || additionalFields.isEmpty()) {
            return entityConfigs.get(entityType);
        }

        return entityConfigs.compute(entityType, (key, existing) -> {
            AIEntityConfig base = existing;
            if (base == null) {
                if (!createIfMissing) {
                    return null;
                }
                base = createDefaultEntityConfig(key);
            }

            List<AIMetadataField> merged = mergeMetadataFieldLists(base.getMetadataFields(), additionalFields);
            base.setMetadataFields(merged);
            return base;
        });
    }

    /**
     * Convenience overload - does not create a config if missing.
     */
    public AIEntityConfig mergeMetadataFields(String entityType, List<AIMetadataField> additionalFields) {
        return mergeMetadataFields(entityType, additionalFields, false);
    }

    private List<AIMetadataField> mergeMetadataFieldLists(List<AIMetadataField> existing,
                                                          List<AIMetadataField> additional) {
        LinkedHashMap<String, AIMetadataField> merged = new LinkedHashMap<>();

        if (existing != null) {
            for (AIMetadataField field : existing) {
                if (field == null || field.getName() == null || field.getName().isBlank()) {
                    continue;
                }
                merged.put(field.getName(), field);
            }
        }

        for (AIMetadataField field : additional) {
            if (field == null || field.getName() == null || field.getName().isBlank()) {
                continue;
            }

            AIMetadataField current = merged.get(field.getName());
            if (current == null) {
                merged.put(field.getName(), field);
                continue;
            }

            String currentType = current.getType();
            String additionalType = field.getType();
            boolean needsType = currentType == null || currentType.isBlank();
            boolean hasAdditionalType = additionalType != null && !additionalType.isBlank();
            if (needsType && hasAdditionalType) {
                merged.put(field.getName(), AIMetadataField.builder()
                    .name(current.getName())
                    .type(additionalType)
                    .includeInSearch(current.isIncludeInSearch())
                    .build());
            }
        }

        return merged.isEmpty() ? null : List.copyOf(merged.values());
    }

    private AIEntityConfig createDefaultEntityConfig(String entityType) {
        // Minimal safe defaults for greenfield usage. YAML overrides remain preferred.
        Map<String, AICrudOperation> crud = new LinkedHashMap<>();
        for (String op : List.of("create", "update", "delete")) {
            crud.put(op, AICrudOperation.builder()
                .operation(op)
                .generateEmbedding(true)
                .indexForSearch(true)
                .enableAnalysis(false)
                .removeFromSearch(true)
                .cleanupEmbeddings(true)
                .build());
        }

        return AIEntityConfig.builder()
            .entityType(entityType)
            .features(List.of("embedding", "search"))
            .autoProcess(true)
            .enableSearch(true)
            .enableRecommendations(false)
            .autoEmbedding(true)
            .indexable(true)
            .searchableFields(null)
            .embeddableFields(null)
            .metadataFields(null)
            .crudOperations(Collections.unmodifiableMap(crud))
            .build();
    }
    
    public boolean hasEntityConfig(String entityType) {
        return entityConfigs.containsKey(entityType);
    }
    
    public Set<String> getSupportedEntityTypes() {
        return entityConfigs.keySet();
    }
    
    public Object getGlobalConfig(String key) {
        return globalConfig.get(key);
    }
    
    public String getDefaultEmbeddingModel() {
        return (String) globalConfig.getOrDefault("default-embedding-model", "text-embedding-3-small");
    }
    
    public int getDefaultSearchLimit() {
        return ((Number) globalConfig.getOrDefault("default-search-limit", 10)).intValue();
    }
    
    public double getDefaultSimilarityThreshold() {
        return ((Number) globalConfig.getOrDefault("default-similarity-threshold", 0.7)).doubleValue();
    }
    
}
