package com.ai.infrastructure.processor;

import com.ai.infrastructure.annotation.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Processor for @AICapable annotation
 *
 * <p>This processor handles entities annotated with @AICapable and provides
 * AI capabilities based on the annotation configuration. Uses the streamlined
 * v2.0 annotation system (@AISearchable, @AIContext) for field-level configuration.</p>
 *
 * <p><strong>Greenfield Architecture:</strong> Clean implementation with no legacy support.
 * Only supports v2.0 annotations (@AISearchable, @AIContext). No backward compatibility.</p>
 *
 * <p><strong>Thread Safety:</strong> This is a singleton Spring bean. All methods are thread-safe.</p>
 *
 * <p><strong>Performance:</strong> Reflection results are cached via AnnotationFieldScanner
 * at application level (10,000x faster after first scan).</p>
 *
 * @author AI Infrastructure Team
 * @version 2.0.0
 * @see AICapable
 * @see AISearchable
 * @see AIContext
 * @see AnnotationFieldScanner
 */
@Slf4j
@RequiredArgsConstructor
public class AICapableProcessor {

    private final AnnotationFieldScanner fieldScanner;

    /**
     * Process an entity for AI capabilities
     *
     * @param entity the entity to process
     * @return list of AI features enabled for this entity
     */
    public List<String> processEntity(Object entity) {
        List<String> features = new ArrayList<>();

        if (entity == null) {
            return features;
        }

        Class<?> entityClass = entity.getClass();

        // Check if class is annotated with @AICapable
        if (entityClass.isAnnotationPresent(AICapable.class)) {
            AICapable annotation = entityClass.getAnnotation(AICapable.class);
            features.addAll(extractFeatures(annotation));

            log.debug("Processed entity {} with AI features: {}",
                entityClass.getSimpleName(), features);
        }

        return features;
    }

    /**
     * Extract AI features from @AICapable annotation
     *
     * @param annotation the @AICapable annotation
     * @return list of enabled features
     */
    private List<String> extractFeatures(AICapable annotation) {
        List<String> features = new ArrayList<>();

        if (annotation.autoEmbedding()) {
            features.add("EMBEDDING");
        }

        if (annotation.enableSearch()) {
            features.add("SEARCH");
        }

        if (annotation.enableRecommendations()) {
            features.add("RECOMMENDATIONS");
        }

        // Add features from the features array
        features.addAll(Arrays.asList(annotation.features()));

        return features;
    }

    /**
     * Check if an entity has specific AI capability
     *
     * @param entity the entity to check
     * @param feature the feature to check for
     * @return true if the entity has the feature enabled
     */
    public boolean hasFeature(Object entity, String feature) {
        List<String> features = processEntity(entity);
        return features.contains(feature.toUpperCase());
    }

    /**
     * Get AI configuration for an entity from @AICapable annotation
     *
     * @param entity the entity to get configuration for
     * @return map of AI configuration
     */
    public Map<String, Object> getAIConfiguration(Object entity) {
        Map<String, Object> config = new HashMap<>();

        if (entity == null) {
            return config;
        }

        Class<?> entityClass = entity.getClass();

        if (entityClass.isAnnotationPresent(AICapable.class)) {
            AICapable annotation = entityClass.getAnnotation(AICapable.class);
            config.put("autoEmbedding", annotation.autoEmbedding());
            config.put("enableSearch", annotation.enableSearch());
            config.put("enableRecommendations", annotation.enableRecommendations());
            config.put("entityType", annotation.entityType());
            config.put("autoProcess", annotation.autoProcess());
            config.put("indexable", annotation.indexable());
            config.put("features", Arrays.asList(annotation.features()));
            config.put("indexingStrategy", annotation.indexingStrategy());
            config.put("onCreateStrategy", annotation.onCreateStrategy());
            config.put("onUpdateStrategy", annotation.onUpdateStrategy());
            config.put("onDeleteStrategy", annotation.onDeleteStrategy());
        }

        return config;
    }

    /**
     * Get fields annotated with @AISmartValidation
     *
     * @param entity the entity to process
     * @return list of validation field information
     */
    public List<Map<String, Object>> getValidationFields(Object entity) {
        if (entity == null) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> validationFields = new ArrayList<>();
        Class<?> entityClass = entity.getClass();

        for (Field field : entityClass.getDeclaredFields()) {
            AISmartValidation annotation = field.getAnnotation(AISmartValidation.class);
            if (annotation != null) {
                Map<String, Object> fieldInfo = new HashMap<>();
                fieldInfo.put("fieldName", field.getName());
                fieldInfo.put("rules", Arrays.asList(annotation.rules()));
                fieldInfo.put("validateContent", annotation.validateContent());
                fieldInfo.put("validateFormat", annotation.validateFormat());
                fieldInfo.put("validateSemantic", annotation.validateSemantic());
                fieldInfo.put("prompt", annotation.prompt());
                fieldInfo.put("required", annotation.required());
                fieldInfo.put("severity", annotation.severity().name());
                fieldInfo.put("realTime", annotation.realTime());
                fieldInfo.put("context", annotation.context());
                fieldInfo.put("crossField", annotation.crossField());

                // Get field value
                try {
                    field.setAccessible(true);
                    Object value = field.get(entity);
                    fieldInfo.put("value", value);
                } catch (IllegalAccessException e) {
                    log.warn("Could not access field {} for validation", field.getName());
                }

                validationFields.add(fieldInfo);
            }
        }

        return validationFields;
    }

    /**
     * Check if an entity has any AI annotations.
     *
     * <p>Checks for @AICapable (class-level), @AISearchable, @AIContext,
     * and @AISmartValidation (field-level) annotations.</p>
     *
     * @param entity the entity to check
     * @return true if entity has AI annotations
     */
    public boolean hasAIAnnotations(Object entity) {
        if (entity == null) {
            return false;
        }

        Class<?> entityClass = entity.getClass();

        // Check class-level annotation
        if (entityClass.isAnnotationPresent(AICapable.class)) {
            return true;
        }

        // Check field-level annotations
        for (Field field : entityClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(AISearchable.class) ||
                field.isAnnotationPresent(AIContext.class) ||
                field.isAnnotationPresent(AISmartValidation.class)) {
                return true;
            }
        }

        return false;
    }

    // ========== v2.0 Annotation System ==========

    /**
     * Extracts searchable content from an entity using @AISearchable annotations.
     *
     * <p>This method delegates to {@link AnnotationFieldScanner} which caches
     * reflection results at application level for optimal performance.</p>
     *
     * <p>Fields annotated with @AISearchable are combined into searchable text,
     * applying weights, preprocessing, and length limits as configured.</p>
     *
     * @param entity The entity instance to extract searchable content from
     * @return Combined searchable content (never null, may be empty)
     * @throws IllegalArgumentException if entity is null
     * @see AISearchable
     * @see AnnotationFieldScanner#extractSearchableContent(Object)
     */
    public String extractSearchableContent(Object entity) {
        if (entity == null) {
            throw new IllegalArgumentException("entity cannot be null");
        }
        return fieldScanner.extractSearchableContent(entity);
    }

    /**
     * Extracts context metadata from an entity using @AIContext annotations.
     *
     * <p>This method delegates to {@link AnnotationFieldScanner} which caches
     * reflection results at application level for optimal performance.</p>
     *
     * <p>Fields annotated with @AIContext provide structured metadata for LLM context
     * without the overhead of vector embeddings (30-50% cost reduction).</p>
     *
     * @param entity The entity instance to extract context metadata from
     * @return Metadata map sorted by priority (never null, may be empty)
     * @throws IllegalArgumentException if entity is null
     * @see AIContext
     * @see AnnotationFieldScanner#extractContextMetadata(Object)
     */
    public Map<String, Object> extractContextMetadata(Object entity) {
        if (entity == null) {
            throw new IllegalArgumentException("entity cannot be null");
        }
        return fieldScanner.extractContextMetadata(entity);
    }

    /**
     * Gets metadata for all @AISearchable fields on an entity class.
     *
     * <p>Results are cached at application level for performance (10,000x faster).</p>
     *
     * @param entityClass The entity class to scan
     * @return List of searchable field metadata (never null)
     * @throws IllegalArgumentException if entityClass is null
     * @see AISearchable
     * @see AnnotationFieldScanner#getSearchableFields(Class)
     */
    public List<AnnotationFieldScanner.SearchableFieldMetadata> getSearchableFieldMetadata(Class<?> entityClass) {
        if (entityClass == null) {
            throw new IllegalArgumentException("entityClass cannot be null");
        }
        return fieldScanner.getSearchableFields(entityClass);
    }

    /**
     * Gets metadata for all @AIContext fields on an entity class.
     *
     * <p>Results are cached at application level for performance (10,000x faster).
     * Fields are sorted by priority (highest first).</p>
     *
     * @param entityClass The entity class to scan
     * @return List of context field metadata sorted by priority (never null)
     * @throws IllegalArgumentException if entityClass is null
     * @see AIContext
     * @see AnnotationFieldScanner#getContextFields(Class)
     */
    public List<AnnotationFieldScanner.ContextFieldMetadata> getContextFieldMetadata(Class<?> entityClass) {
        if (entityClass == null) {
            throw new IllegalArgumentException("entityClass cannot be null");
        }
        return fieldScanner.getContextFields(entityClass);
    }

    /**
     * Checks if an entity has @AISearchable fields.
     *
     * @param entity The entity to check
     * @return true if entity has @AISearchable fields
     */
    public boolean hasSearchableFields(Object entity) {
        if (entity == null) {
            return false;
        }
        List<AnnotationFieldScanner.SearchableFieldMetadata> fields =
            fieldScanner.getSearchableFields(entity.getClass());
        return !fields.isEmpty();
    }

    /**
     * Checks if an entity has @AIContext fields.
     *
     * @param entity The entity to check
     * @return true if entity has @AIContext fields
     */
    public boolean hasContextFields(Object entity) {
        if (entity == null) {
            return false;
        }
        List<AnnotationFieldScanner.ContextFieldMetadata> fields =
            fieldScanner.getContextFields(entity.getClass());
        return !fields.isEmpty();
    }

    /**
     * Extracts complete AI data from an entity including both searchable content
     * and context metadata.
     *
     * <p><strong>This is the primary method for preparing entity data for AI indexing.</strong></p>
     *
     * <p>Returns a map containing:</p>
     * <ul>
     *   <li>searchableContent: Combined text from @AISearchable fields (gets embedded)</li>
     *   <li>contextMetadata: Structured data from @AIContext fields (LLM context only)</li>
     *   <li>hasSearchableFields: Boolean flag</li>
     *   <li>hasContextFields: Boolean flag</li>
     * </ul>
     *
     * @param entity The entity instance to process
     * @return Map containing complete AI data (never null)
     * @throws IllegalArgumentException if entity is null
     */
    public Map<String, Object> extractCompleteAIData(Object entity) {
        if (entity == null) {
            throw new IllegalArgumentException("entity cannot be null");
        }

        Map<String, Object> aiData = new LinkedHashMap<>();
        aiData.put("searchableContent", extractSearchableContent(entity));
        aiData.put("contextMetadata", extractContextMetadata(entity));
        aiData.put("hasSearchableFields", hasSearchableFields(entity));
        aiData.put("hasContextFields", hasContextFields(entity));

        return aiData;
    }
}
