package ai.fabric.service;

import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AISearchableField;
import ai.fabric.dto.AIEmbeddableField;
import ai.fabric.dto.AIMetadataField;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.core.AICoreService;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.processor.AnnotationFieldScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.util.*;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * AI Capability Service
 * 
 * Core service that performs AI processing based on entity configuration.
 * Handles embedding generation, search indexing, and AI analysis.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Slf4j
public class AICapabilityService {

    private static final String TEMPLATE_FAMILY = "core/capability";
    private static final String TEMPLATE_ANALYZE_ENTITY_CONTENT = "analyze-entity-content";
    private static final String PLACEHOLDER_ENTITY_TYPE = "entity_type";
    private static final String PLACEHOLDER_CONTENT = "content";
    
    private final AIEmbeddingService embeddingService;
    private final AICoreService aiCoreService;
    private final AIEntityConfigurationLoader configurationLoader;
    private final VectorManagementService vectorManagementService;
    private final AnnotationFieldScanner annotationFieldScanner;
    private final PromptTemplateResolver promptTemplateResolver;
    private final PromptRenderer promptRenderer;
    
    public AICapabilityService(AIEmbeddingService embeddingService,
                              AICoreService aiCoreService,
                              AIEntityConfigurationLoader configurationLoader,
                              VectorManagementService vectorManagementService,
                              AnnotationFieldScanner annotationFieldScanner,
                              PromptTemplateResolver promptTemplateResolver,
                              PromptRenderer promptRenderer) {
        this.embeddingService = embeddingService;
        this.aiCoreService = aiCoreService;
        this.configurationLoader = configurationLoader;
        this.vectorManagementService = Objects.requireNonNull(vectorManagementService,
            "VectorManagementService must be configured for AICapabilityService");
        this.annotationFieldScanner = Objects.requireNonNull(annotationFieldScanner,
            "AnnotationFieldScanner must be configured for AICapabilityService");
        this.promptTemplateResolver = Objects.requireNonNull(promptTemplateResolver, "promptTemplateResolver");
        this.promptRenderer = Objects.requireNonNull(promptRenderer, "promptRenderer");
    }
    
    // Debug method to access configurationLoader
    public AIEntityConfigurationLoader getConfigurationLoader() {
        return configurationLoader;
    }
    
    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("AICapabilityService initialized with configurationLoader: {}", configurationLoader != null ? "present" : "null");
        if (configurationLoader != null) {
            log.info("Configuration loader supports entity types: {}", configurationLoader.getSupportedEntityTypes());
        }
    }
    
    /**
     * Validate entity based on configuration
     */
    public void validateEntity(Object entity, AIEntityConfig config) {
        try {
            log.debug("Validating entity of type: {}", config.getEntityType());
            
            // Extract searchable content
            String searchableContent = extractSearchableContent(entity, config);
            
            // Validate content if needed
            if (isFeatureEnabled(config, "validation")) {
                // Perform AI-powered validation
                boolean isValid = aiCoreService.validateContent(searchableContent, Map.of()).containsKey("valid");
                if (!isValid) {
                    throw new RuntimeException("Entity validation failed");
                }
            }
            
        } catch (Exception e) {
            log.error("Error validating entity", e);
            throw new RuntimeException("Entity validation failed", e);
        }
    }
    
    /**
     * Generate embeddings for entity based on configuration
     */
    @Transactional
    public void generateEmbeddings(Object entity, AIEntityConfig config) {
        try {
            log.debug("Generating embeddings for entity of type: {}", config.getEntityType());
            log.debug("Config metadata fields: {}", config.getMetadataFields() != null ? config.getMetadataFields().size() : "null");

            if (!isFeatureEnabled(config, "embedding")) {
                log.debug("Embedding feature disabled for entity type: {}", config.getEntityType());
                return;
            }
            
            if (!config.isAutoEmbedding()) {
                log.debug("Auto-embedding disabled for entity type: {}", config.getEntityType());
                return;
            }
            
            // Extract embeddable content
            String embeddableContent = extractEmbeddableContent(entity, config);
            
            if (embeddableContent == null || embeddableContent.trim().isEmpty()) {
                log.warn("No embeddable content found for entity");
                return;
            }
            
            // Generate embeddings
            List<Double> embeddings = embeddingService.generateEmbedding(
                ai.fabric.dto.AIEmbeddingRequest.builder()
                    .text(embeddableContent)
                    .build()
            ).getEmbedding();
            
            // Store in searchable entity
            storeSearchableEntity(entity, config, embeddableContent, embeddings);
            
        } catch (Exception e) {
            log.error("Error generating embeddings for entity", e);
        }
    }
    
    /**
     * Index entity for search based on configuration
     */
    @Transactional
    public void indexForSearch(Object entity, AIEntityConfig config) {
        try {
            log.debug("Indexing entity for search of type: {}", config.getEntityType());

            if (!isFeatureEnabled(config, "search")) {
                log.debug("Search feature disabled for entity type: {}", config.getEntityType());
                return;
            }
            
            if (!config.isIndexable()) {
                log.debug("Indexing disabled for entity type: {}", config.getEntityType());
                return;
            }
            
            // Extract searchable content
            String searchableContent = extractSearchableContent(entity, config);
            
            if (searchableContent == null || searchableContent.trim().isEmpty()) {
                log.warn("No searchable content found for entity");
                return;
            }
            
            // Generate embeddings if not already done
            List<Double> embeddings = embeddingService.generateEmbedding(
                ai.fabric.dto.AIEmbeddingRequest.builder()
                    .text(searchableContent)
                    .build()
            ).getEmbedding();
            
            // Store in searchable entity
            storeSearchableEntity(entity, config, searchableContent, embeddings);
            
        } catch (Exception e) {
            log.error("Error indexing entity for search", e);
        }
    }
    
    /**
     * Analyze entity based on configuration
     */
    public void analyzeEntity(Object entity, AIEntityConfig config) {
        try {
            log.debug("Analyzing entity of type: {}", config.getEntityType());

            if (!isFeatureEnabled(config, "analysis")) {
                log.debug("Analysis feature disabled for entity type: {}", config.getEntityType());
                return;
            }

            // Extract content for analysis
            String content = extractSearchableContent(entity, config);
            
            if (content == null || content.trim().isEmpty()) {
                log.warn("No content found for analysis");
                return;
            }
            
            String entityType = config.getEntityType() != null ? config.getEntityType() : "entity";
            String prompt = promptRenderer.render(
                promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_ANALYZE_ENTITY_CONTENT).template(),
                Map.of(
                    PLACEHOLDER_ENTITY_TYPE, entityType,
                    PLACEHOLDER_CONTENT, content
                )
            );
            String analysis = aiCoreService.generateText(prompt);
            
            // Best-effort persistence: attach analysis to vector metadata when a vector exists.
            persistAnalysisToVector(entity, config, analysis);
            
        } catch (Exception e) {
            log.error("Error analyzing entity", e);
        }
    }
    
    /**
     * Remove entity from search index
     */
    @Transactional
    public void removeFromSearch(Object entity, AIEntityConfig config) {
        try {
            log.debug("Removing entity from search index of type: {}", config.getEntityType());
            
            // Get entity ID
            String entityId = getEntityId(entity);
            if (entityId == null) {
                log.warn("No entity ID found for removal");
                return;
            }

            boolean removed = vectorManagementService.removeVector(config.getEntityType(), entityId);
            if (!removed) {
                log.debug("Vector not found for removal: {}:{}", config.getEntityType(), entityId);
            }
            
        } catch (Exception e) {
            log.error("Error removing entity from search index", e);
        }
    }
    
    /**
     * Cleanup embeddings for entity
     */
    @Transactional
    public void cleanupEmbeddings(Object entity, AIEntityConfig config) {
        try {
            log.debug("Cleaning up embeddings for entity of type: {}", config.getEntityType());
            
            // Get entity ID
            String entityId = getEntityId(entity);
            if (entityId == null) {
                log.warn("No entity ID found for cleanup");
                return;
            }
            
            // Remove vector from vector database
            boolean vectorRemoved = vectorManagementService.removeVector(config.getEntityType(), entityId);
            if (vectorRemoved) {
                log.debug("Successfully removed vector from vector database for entity {} of type {}", entityId, config.getEntityType());
            } else {
                log.warn("Vector not found in vector database for entity {} of type {}", entityId, config.getEntityType());
            }
            
        } catch (Exception e) {
            log.error("Error cleaning up embeddings for entity", e);
        }
    }
    
    private String extractSearchableContent(Object entity, AIEntityConfig config) {
        try {
            // v2.0 annotation-driven extraction (preferred when present)
            if (entity != null && !annotationFieldScanner.getSearchableFields(entity.getClass()).isEmpty()) {
                return annotationFieldScanner.extractSearchableContent(entity);
            }

            List<String> contentParts = new ArrayList<>();
            
            if (config.getSearchableFields() != null) {
                for (AISearchableField field : config.getSearchableFields()) {
                    String value = getFieldValue(entity, field.getName());
                    if (value != null && !value.trim().isEmpty()) {
                        contentParts.add(value);
                    }
                }
            }
            
            return String.join(" ", contentParts);
            
        } catch (Exception e) {
            log.error("Error extracting searchable content", e);
            return "";
        }
    }
    
    private String extractEmbeddableContent(Object entity, AIEntityConfig config) {
        try {
            // v2.0 annotation-driven extraction (preferred when present)
            if (entity != null && !annotationFieldScanner.getSearchableFields(entity.getClass()).isEmpty()) {
                // Embeddable content in v2 is the searchable content (content intended to be embedded).
                return annotationFieldScanner.extractSearchableContent(entity);
            }

            List<String> contentParts = new ArrayList<>();
            
            if (config.getEmbeddableFields() != null) {
                for (AIEmbeddableField field : config.getEmbeddableFields()) {
                    String value = getFieldValue(entity, field.getName());
                    if (value != null && !value.trim().isEmpty()) {
                        contentParts.add(value);
                    }
                }
            }
            
            return String.join(" ", contentParts);
            
        } catch (Exception e) {
            log.error("Error extracting embeddable content", e);
            return "";
        }
    }
    
    private String getFieldValue(Object entity, String fieldName) {
        try {
            Field field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(entity);
            return value != null ? value.toString() : "";
        } catch (Exception e) {
            log.debug("Field not found or accessible: {}", fieldName);
            return "";
        }
    }
    
    public String resolveEntityId(Object entity) {
        return getEntityId(entity);
    }

    private String getEntityId(Object entity) {
        try {
            Field idField = entity.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            Object id = idField.get(entity);
            return id != null ? id.toString() : null;
        } catch (Exception e) {
            log.debug("ID field not found or accessible");
            return null;
        }
    }
    
    private void storeSearchableEntity(Object entity, AIEntityConfig config, String content, List<Double> embeddings) {
        try {
            String entityId = getEntityId(entity);
            if (entityId == null) {
                log.warn("No entity ID found for storing vector");
                return;
            }
            
            // Store vector in vector database
            Map<String, Object> metadata = extractMetadata(entity, config);
            String vectorId = vectorManagementService.storeVector(
                config.getEntityType(),
                entityId,
                content,
                embeddings,
                metadata
            );
            
            if (vectorId == null) {
                log.error("Failed to store vector in vector database for entity {} of type {}", entityId, config.getEntityType());
                return;
            }
            
        } catch (Exception e) {
            log.error("Error storing vector", e);
        }
    }
    
    private Map<String, Object> extractMetadata(Object entity, AIEntityConfig config) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        
        try {
            // v2.0 annotation-driven context metadata (preferred when present)
            if (entity != null && !annotationFieldScanner.getContextFields(entity.getClass()).isEmpty()) {
                metadata.putAll(annotationFieldScanner.extractContextMetadata(entity));
            }

            // Simple defensive check: if metadataFields is null, return empty metadata
            if (config == null || config.getMetadataFields() == null) {
                log.warn("Config or metadata fields are null, skipping metadata extraction");
                return metadata;
            }
            
            // Extract metadata from fields
            for (AIMetadataField field : config.getMetadataFields()) {
                try {
                    String value = getFieldValue(entity, field.getName());
                    if (value != null && !value.trim().isEmpty()) {
                        metadata.putIfAbsent(field.getName(), value);
                        log.debug("Extracted metadata field {}: {}", field.getName(), value);
                    }
                } catch (Exception fieldException) {
                    log.warn("Failed to extract metadata field {}: {}", field.getName(), fieldException.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Error extracting metadata", e);
            // Return empty metadata instead of throwing exception to prevent breaking the flow
        }
        
        return metadata;
    }
    
    private void persistAnalysisToVector(Object entity, AIEntityConfig config, String analysis) {
        String entityId = getEntityId(entity);
        if (entityId == null) {
            log.warn("No entity ID found for analysis persistence");
            return;
        }

        Optional<ai.fabric.dto.VectorRecord> existing = vectorManagementService.getVector(config.getEntityType(), entityId);
        if (existing.isEmpty()) {
            log.debug("No vector exists for {}:{}; skipping analysis persistence", config.getEntityType(), entityId);
            return;
        }

        Map<String, Object> metadata = extractMetadata(entity, config);
        metadata.put("aiAnalysis", analysis);

        String content = extractSearchableContent(entity, config);
        if (content == null || content.isBlank()) {
            content = existing.get().getContent();
        }

        List<Double> embedding = embeddingService.generateEmbedding(
            ai.fabric.dto.AIEmbeddingRequest.builder()
                .text(content != null ? content : "")
                .build()
        ).getEmbedding();

        vectorManagementService.updateVector(config.getEntityType(), entityId, content, embedding, metadata);
    }
    
    
    /**
     * Remove entity from AI index
     */
    @Transactional
    public void removeEntityFromIndex(String entityId, String entityType) {
        try {
            log.debug("Removing entity from AI index: {} of type {}", entityId, entityType);

            boolean removed = vectorManagementService.removeVector(entityType, entityId);
            if (!removed) {
                log.warn("Vector not found for removal: {} of type {}", entityId, entityType);
            }
            
        } catch (Exception e) {
            log.error("Error removing entity from AI index", e);
        }
    }
    
    /**
     * Validate AI entity configuration
     */
    private void validateConfiguration(AIEntityConfig config, String entityType, @jakarta.annotation.Nullable Class<?> entityClass) {
        if (config == null) {
            throw new IllegalArgumentException("AI configuration cannot be null for entity type: " + entityType);
        }
        
        if (config.getEntityType() == null || config.getEntityType().trim().isEmpty()) {
            throw new IllegalArgumentException("AI configuration entity type cannot be null or empty");
        }
        
        boolean hasAnnotationSearchableFields = entityClass != null
            && !annotationFieldScanner.getSearchableFields(entityClass).isEmpty();
        boolean hasAnnotationContextFields = entityClass != null
            && !annotationFieldScanner.getContextFields(entityClass).isEmpty();

        if ((config.getSearchableFields() == null || config.getSearchableFields().isEmpty()) && !hasAnnotationSearchableFields) {
            log.warn("No searchable fields configured for entity type: {}", entityType);
        }

        if ((config.getEmbeddableFields() == null || config.getEmbeddableFields().isEmpty()) && !hasAnnotationSearchableFields) {
            log.warn("No embeddable fields configured for entity type: {}", entityType);
        }
        
        if (config.getMetadataFields() == null && !hasAnnotationContextFields) {
            log.warn("No metadata fields configured for entity type: {} - metadata extraction will be skipped", entityType);
        }
    }
    
    /**
     * Process entity for AI capabilities
     */
    @Transactional
    public void processEntityForAI(Object entity, String entityType) {
        try {
            log.debug("Processing entity for AI of type: {}", entityType);
            log.debug("Configuration loader is: {}", configurationLoader != null ? "available" : "null");
            
            // Validate configuration loader
            if (configurationLoader == null) {
                log.error("Configuration loader is not available");
                throw new IllegalStateException("AI configuration loader is not available. Check Spring context configuration.");
            }
            
            // Get entity configuration from configuration loader
            AIEntityConfig config = configurationLoader.getEntityConfig(entityType);
            if (config == null) {
                log.error("No configuration found for entity type: {}", entityType);
                log.error("Available entity types: {}", configurationLoader.getSupportedEntityTypes());
                throw new IllegalArgumentException("No AI configuration found for entity type: " + entityType + 
                    ". Available types: " + configurationLoader.getSupportedEntityTypes());
            }
            
            // Debug: Check if this is the same instance as in ConfigurationTest
            log.debug("Config instance: {}, metadataFields: {}", 
                config.getClass().getSimpleName(), 
                config.getMetadataFields() != null ? config.getMetadataFields().size() : "null");
            log.debug("Config metadataFields instance: {}", 
                config.getMetadataFields() != null ? config.getMetadataFields().getClass().getSimpleName() : "null");
            
            // Validate configuration
            validateConfiguration(config, entityType, entity != null ? entity.getClass() : null);
            
            log.debug("Retrieved config for entity type: {}, metadata fields: {}",
                entityType, config.getMetadataFields() != null ? config.getMetadataFields().size() : "null");

            // Validate configuration completeness
            if (config.getMetadataFields() == null) {
                log.warn("Metadata fields are null for entity type: {}", entityType);
                log.warn("Config details - entityType: {}, searchableFields: {}, embeddableFields: {}",
                    config.getEntityType(),
                    config.getSearchableFields() != null ? config.getSearchableFields().size() : "null",
                    config.getEmbeddableFields() != null ? config.getEmbeddableFields().size() : "null");
                log.warn("Continuing with null metadata fields - this may cause issues in metadata extraction");
            }
            
            // Generate embeddings
            log.debug("About to call generateEmbeddings with config metadata fields: {}", 
                config.getMetadataFields() != null ? config.getMetadataFields().size() : "null");
            log.debug("Config object details: entityType={}, metadataFields={}, searchableFields={}", 
                config.getEntityType(),
                config.getMetadataFields() != null ? config.getMetadataFields().size() : "null",
                config.getSearchableFields() != null ? config.getSearchableFields().size() : "null");
            generateEmbeddings(entity, config);
            
            // Index for search
            log.debug("About to call indexForSearch with config metadata fields: {}", 
                config.getMetadataFields() != null ? config.getMetadataFields().size() : "null");
            indexForSearch(entity, config);
            
            // Analyze entity
            log.debug("About to call analyzeEntity with config metadata fields: {}", 
                config.getMetadataFields() != null ? config.getMetadataFields().size() : "null");
            analyzeEntity(entity, config);
            
            log.debug("Successfully processed entity for AI");
            
        } catch (Exception e) {
            log.error("Error processing entity for AI", e);
        }
    }

    private boolean isFeatureEnabled(AIEntityConfig config, String feature) {
        Objects.requireNonNull(feature, "feature");
        if (config == null) {
            return false;
        }
        List<String> features = config.getFeatures();
        if (features == null || features.isEmpty()) {
            // Backwards-compatible default: if a config doesn't specify features, treat them as enabled.
            return true;
        }
        for (String entry : features) {
            if (entry != null && entry.equalsIgnoreCase(feature)) {
                return true;
            }
        }
        return false;
    }
}
