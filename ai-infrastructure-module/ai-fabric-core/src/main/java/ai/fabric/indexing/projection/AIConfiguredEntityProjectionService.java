package ai.fabric.indexing.projection;

import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIMetadataField;
import ai.fabric.dto.AISearchableField;
import ai.fabric.dto.PIIDetectionResult;
import ai.fabric.indexing.api.AIContextDataType;
import ai.fabric.indexing.api.AIContextDestination;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.AISearchDestination;
import ai.fabric.indexing.api.AISearchPreprocessing;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.indexing.model.AIContextValue;
import ai.fabric.indexing.model.AIIndexDocument;
import ai.fabric.privacy.pii.PIIDetectionService;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;

/**
 * Builds the canonical index document for trusted YAML-only entity ingress.
 */
public class AIConfiguredEntityProjectionService {

    private final ObjectProvider<PIIDetectionService> piiDetectionServiceProvider;
    private final ObjectMapper objectMapper;
    private final ObjectMapper canonicalObjectMapper;
    private final Clock clock;

    public AIConfiguredEntityProjectionService(
        ObjectProvider<PIIDetectionService> piiDetectionServiceProvider,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.piiDetectionServiceProvider = piiDetectionServiceProvider;
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.canonicalObjectMapper = objectMapper.copy()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.clock = Objects.requireNonNull(clock);
    }

    public AIIndexDocument project(
        AIEntityConfig config,
        String entityId,
        String suppliedContent,
        Map<String, Object> entity,
        Map<String, Object> suppliedMetadata,
        Map<String, Object> trustedVectorMetadata,
        Long sourceVersion,
        String correlationId,
        AIProcessOperation operation
    ) {
        String entityType = validateConfig(config);
        String id = requireText(entityId, "IDENTITY_MISSING");
        if (operation != AIProcessOperation.CREATE
            && operation != AIProcessOperation.UPDATE) {
            throw failure(entityType, id, "<operation>", "INDEXING", "UPSERT_OPERATION_REQUIRED");
        }

        int budget = projectionBudget(config);
        Map<String, Object> values = mergedValues(
            suppliedContent,
            entity,
            suppliedMetadata
        );
        List<SearchValue> searchValues = extractSearchValues(
            config,
            entityType,
            id,
            values
        );
        String semanticText = renderSearch(
            searchValues,
            AISearchDestination.SEMANTIC_SEARCH,
            budget,
            entityType,
            id
        );
        String ragText = renderSearch(
            searchValues,
            AISearchDestination.RAG_CONTEXT,
            budget,
            entityType,
            id
        );

        List<ContextValue> contextValues = extractContextValues(
            config,
            entityType,
            id,
            values
        );
        Map<String, Object> vectorMetadata = contextMap(
            contextValues,
            AIContextDestination.VECTOR_METADATA
        );
        if (trustedVectorMetadata != null) {
            trustedVectorMetadata.forEach((key, value) -> {
                if (StringUtils.hasText(key) && value != null) {
                    vectorMetadata.put(key.trim(), safeMetadataValue(value));
                }
            });
        }
        String hash = descriptorHash(config);
        vectorMetadata.put("_aifabricSchemaVersion", AIIndexDocument.CURRENT_SCHEMA_VERSION);
        vectorMetadata.put("_aifabricDescriptorHash", hash);
        vectorMetadata.put("_aifabricSourceOperation", operation.name());

        return new AIIndexDocument(
            AIIndexDocument.CURRENT_SCHEMA_VERSION,
            hash,
            entityType,
            id,
            AIIndexWorkType.UPSERT,
            operation,
            semanticText,
            ragText,
            vectorMetadata,
            llmContext(contextValues, budget, entityType, id),
            contextMap(contextValues, AIContextDestination.API_RESPONSE),
            sourceVersion,
            normalize(correlationId),
            Instant.now(clock)
        );
    }

    public AIIndexDocument projectDelete(
        AIEntityConfig config,
        String entityId,
        String correlationId
    ) {
        String entityType = validateConfig(config);
        return new AIIndexDocument(
            AIIndexDocument.CURRENT_SCHEMA_VERSION,
            descriptorHash(config),
            entityType,
            requireText(entityId, "IDENTITY_MISSING"),
            AIIndexWorkType.DELETE,
            AIProcessOperation.DELETE,
            null,
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            null,
            normalize(correlationId),
            Instant.now(clock)
        );
    }

    /**
     * Validates a YAML-only entity contract without requiring a source record.
     */
    public void validateConfiguration(AIEntityConfig config) {
        String entityType = validateConfig(config);
        projectionBudget(config);

        Set<String> searchNames = new HashSet<>();
        boolean semanticSearchDeclared = false;
        for (AISearchableField field : config.getSearchableFields()) {
            if (field == null || !StringUtils.hasText(field.getName())) {
                throw failure(
                    entityType,
                    null,
                    "<searchable>",
                    "INDEXING",
                    "SEARCHABLE_FIELD_NAME_REQUIRED"
                );
            }
            String name = field.getName().trim();
            if (!searchNames.add(name.toLowerCase(Locale.ROOT))) {
                throw failure(
                    entityType,
                    null,
                    name,
                    "INDEXING",
                    "DUPLICATE_SEARCHABLE_FIELD"
                );
            }
            Set<AISearchDestination> destinations = field.getDestinations();
            if (destinations == null || destinations.isEmpty()) {
                throw failure(
                    entityType,
                    null,
                    name,
                    "INDEXING",
                    "SEARCHABLE_DESTINATION_REQUIRED"
                );
            }
            semanticSearchDeclared |= destinations.contains(
                AISearchDestination.SEMANTIC_SEARCH
            );
            validatePriority(
                field.getPriority(),
                entityType,
                name,
                "INVALID_SEARCHABLE_PRIORITY"
            );
            if (field.getMaxLength() != null
                && (field.getMaxLength() == 0 || field.getMaxLength() < -1)) {
                throw failure(
                    entityType,
                    null,
                    name,
                    "INDEXING",
                    "INVALID_SEARCHABLE_MAX_LENGTH"
                );
            }
            if (field.getPreprocessing() == AISearchPreprocessing.SANITIZE) {
                requirePiiService(entityType, name);
            }
        }
        if (!semanticSearchDeclared) {
            throw failure(
                entityType,
                null,
                "<projection>",
                AISearchDestination.SEMANTIC_SEARCH.name(),
                "SEMANTIC_SEARCH_FIELD_REQUIRED"
            );
        }

        Set<String> contextNames = new HashSet<>();
        if (config.getMetadataFields() != null) {
            for (AIMetadataField field : config.getMetadataFields()) {
                validateContextConfiguration(entityType, field, contextNames);
            }
        }
        if (config.getAnalysis() != null
            && Boolean.TRUE.equals(config.getAnalysis().getEnabled())
            && (config.getAnalysis().getAfter() == null
                || config.getAnalysis().getAfter().isEmpty())) {
            throw failure(
                entityType,
                null,
                "<analysis>",
                "ANALYSIS",
                "ANALYSIS_OPERATIONS_REQUIRED"
            );
        }
    }

    private String validateConfig(AIEntityConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("entity config is required");
        }
        String entityType = requireText(config.getEntityType(), "ENTITY_TYPE_MISSING");
        if (config.getIndexing() == null
            || !Boolean.TRUE.equals(config.getIndexing().getEnabled())) {
            throw failure(
                entityType,
                null,
                "<entity>",
                "INDEXING",
                "INDEXING_NOT_EXPLICITLY_ENABLED"
            );
        }
        if (config.getSearchableFields() == null
            || config.getSearchableFields().isEmpty()) {
            throw failure(
                entityType,
                null,
                "<projection>",
                "SEMANTIC_SEARCH",
                "SEARCHABLE_FIELDS_REQUIRED"
            );
        }
        return entityType;
    }

    private void validateContextConfiguration(
        String entityType,
        AIMetadataField field,
        Set<String> names
    ) {
        if (field == null || !StringUtils.hasText(field.getName())) {
            throw failure(
                entityType,
                null,
                "<context>",
                "INDEXING",
                "CONTEXT_FIELD_NAME_REQUIRED"
            );
        }
        String name = field.getName().trim();
        if (!names.add(name.toLowerCase(Locale.ROOT))) {
            throw failure(
                entityType,
                null,
                name,
                "CONTEXT",
                "DUPLICATE_CONTEXT_FIELD"
            );
        }
        if (field.getDestinations() == null
            || field.getDestinations().isEmpty()) {
            throw failure(
                entityType,
                null,
                name,
                "INDEXING",
                "CONTEXT_DESTINATION_REQUIRED"
            );
        }
        validatePriority(
            field.getPriority(),
            entityType,
            name,
            "INVALID_CONTEXT_PRIORITY"
        );
        if (field.getDescription() != null
            && field.getDescription().length() > 500) {
            throw failure(
                entityType,
                null,
                name,
                "CONTEXT",
                "CONTEXT_DESCRIPTION_TOO_LONG"
            );
        }
        AIContextDataType dataType = field.getDataType() == null
            ? AIContextDataType.AUTO
            : field.getDataType();
        if (StringUtils.hasText(field.getFormat())) {
            validateConfiguredFormat(
                entityType,
                name,
                dataType,
                field.getFormat()
            );
        }
        if (Boolean.TRUE.equals(field.getSanitizePII())) {
            requirePiiService(entityType, name);
        }
    }

    private void validateConfiguredFormat(
        String entityType,
        String fieldName,
        AIContextDataType dataType,
        String format
    ) {
        try {
            if (dataType == AIContextDataType.DATE) {
                DateTimeFormatter.ofPattern(format);
            } else if (dataType == AIContextDataType.NUMBER) {
                new DecimalFormat(format);
            } else {
                throw failure(
                    entityType,
                    null,
                    fieldName,
                    "CONTEXT",
                    "CONTEXT_FORMAT_TYPE_MISMATCH"
                );
            }
        } catch (AIProjectionValidationException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw failure(
                entityType,
                null,
                fieldName,
                "CONTEXT",
                "INVALID_CONTEXT_FORMAT"
            );
        }
    }

    private void validatePriority(
        Integer configured,
        String entityType,
        String fieldName,
        String errorCode
    ) {
        int priority = configured == null ? 50 : configured;
        if (priority < 0 || priority > 100) {
            throw failure(
                entityType,
                null,
                fieldName,
                "INDEXING",
                errorCode
            );
        }
    }

    private void requirePiiService(String entityType, String fieldName) {
        if (piiDetectionServiceProvider == null
            || piiDetectionServiceProvider.getIfAvailable() == null) {
            throw failure(
                entityType,
                null,
                fieldName,
                "PII",
                "PII_SERVICE_UNAVAILABLE"
            );
        }
    }

    private int projectionBudget(AIEntityConfig config) {
        Integer configured = config.getIndexing().getMaxCharacters();
        int budget = configured == null
            ? AIEntityDescriptorRegistry.DEFAULT_PROJECTION_MAX_CHARACTERS
            : configured;
        if (budget < 1
            || budget > AIEntityDescriptorRegistry.DEFAULT_PROJECTION_MAX_CHARACTERS) {
            throw failure(
                config.getEntityType(),
                null,
                "<projection>",
                "INDEXING",
                "INVALID_PROJECTION_BUDGET"
            );
        }
        return budget;
    }

    private List<SearchValue> extractSearchValues(
        AIEntityConfig config,
        String entityType,
        String entityId,
        Map<String, Object> values
    ) {
        List<SearchValue> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int order = 0;
        for (AISearchableField field : config.getSearchableFields()) {
            if (field == null || !StringUtils.hasText(field.getName())) {
                throw failure(
                    entityType,
                    entityId,
                    "<searchable>",
                    "INDEXING",
                    "SEARCHABLE_FIELD_NAME_REQUIRED"
                );
            }
            String fieldName = field.getName().trim();
            if (!names.add(fieldName.toLowerCase(Locale.ROOT))) {
                throw failure(
                    entityType,
                    entityId,
                    fieldName,
                    "INDEXING",
                    "DUPLICATE_SEARCHABLE_FIELD"
                );
            }
            Set<AISearchDestination> destinations = field.getDestinations();
            if (destinations == null || destinations.isEmpty()) {
                throw failure(
                    entityType,
                    entityId,
                    field.getName(),
                    "INDEXING",
                    "SEARCHABLE_DESTINATION_REQUIRED"
                );
            }
            int priority = field.getPriority() == null ? 50 : field.getPriority();
            if (priority < 0 || priority > 100) {
                throw failure(
                    entityType,
                    entityId,
                    field.getName(),
                    "INDEXING",
                    "INVALID_SEARCHABLE_PRIORITY"
                );
            }
            Object raw = read(values, fieldName);
            String value = raw == null ? "" : String.valueOf(raw);
            if (!StringUtils.hasText(value)) {
                if (Boolean.TRUE.equals(field.getRequired())) {
                    throw failure(
                        entityType,
                        entityId,
                        fieldName,
                        destinations.toString(),
                        "REQUIRED_FIELD_MISSING"
                    );
                }
                order++;
                continue;
            }
            value = preprocess(
                value,
                field.getPreprocessing() == null
                    ? AISearchPreprocessing.NORMALIZE
                    : field.getPreprocessing(),
                entityType,
                entityId,
                fieldName
            );
            if (!StringUtils.hasText(value)) {
                if (Boolean.TRUE.equals(field.getRequired())) {
                    throw failure(
                        entityType,
                        entityId,
                        fieldName,
                        destinations.toString(),
                        "REQUIRED_FIELD_EMPTY_AFTER_PREPROCESSING"
                    );
                }
                order++;
                continue;
            }
            int maxLength = field.getMaxLength() == null ? -1 : field.getMaxLength();
            if (maxLength == 0 || maxLength < -1) {
                throw failure(
                    entityType,
                    entityId,
                    fieldName,
                    "INDEXING",
                    "INVALID_SEARCHABLE_MAX_LENGTH"
                );
            }
            if (maxLength > 0 && value.length() > maxLength) {
                value = value.substring(0, maxLength);
            }
            result.add(new SearchValue(
                fieldName,
                value,
                destinations,
                priority,
                Boolean.TRUE.equals(field.getRequired()),
                order++
            ));
        }
        boolean hasSemanticField = result.stream().anyMatch(
            value -> value.destinations().contains(AISearchDestination.SEMANTIC_SEARCH)
        );
        if (!hasSemanticField) {
            throw failure(
                entityType,
                entityId,
                "<projection>",
                AISearchDestination.SEMANTIC_SEARCH.name(),
                "SEMANTIC_SEARCH_PROJECTION_EMPTY"
            );
        }
        result.sort(
            Comparator.comparingInt(SearchValue::priority)
                .reversed()
                .thenComparingInt(SearchValue::order)
        );
        return result;
    }

    private String renderSearch(
        List<SearchValue> values,
        AISearchDestination destination,
        int budget,
        String entityType,
        String entityId
    ) {
        List<SearchValue> selected = values.stream()
            .filter(value -> value.destinations().contains(destination))
            .toList();
        int requiredCount = (int) selected.stream()
            .filter(SearchValue::required)
            .count();
        int requiredTextCharacters = selected.stream()
            .filter(SearchValue::required)
            .mapToInt(value -> fragment(value).length())
            .sum();
        int requiredCharacters = requiredTextCharacters
            + Math.max(0, requiredCount - 1);
        if (requiredCharacters > budget) {
            throw failure(
                entityType,
                entityId,
                "<projection>",
                destination.name(),
                "REQUIRED_FIELDS_EXCEED_PROJECTION_BUDGET"
            );
        }

        StringBuilder output = new StringBuilder();
        int requiredTextRemaining = requiredTextCharacters;
        int requiredCountRemaining = requiredCount;
        for (SearchValue value : selected) {
            String fragment = fragment(value);
            int separator = output.isEmpty() ? 0 : 1;
            if (value.required()) {
                append(output, fragment);
                requiredTextRemaining -= fragment.length();
                requiredCountRemaining--;
                continue;
            }
            int requiredRemaining = requiredTextRemaining
                + Math.max(0, requiredCountRemaining - 1)
                + (requiredCountRemaining > 0 ? 1 : 0);
            int available = budget - output.length() - requiredRemaining;
            if (available <= separator) {
                continue;
            }
            int contentBudget = available - separator;
            if (fragment.length() <= contentBudget) {
                append(output, fragment);
            } else if (contentBudget > value.name().length() + 2) {
                append(output, fragment.substring(0, contentBudget));
            }
        }
        if (destination == AISearchDestination.SEMANTIC_SEARCH
            && output.isEmpty()) {
            throw failure(
                entityType,
                entityId,
                "<projection>",
                destination.name(),
                "SEMANTIC_SEARCH_PROJECTION_EMPTY"
            );
        }
        return output.toString();
    }

    private List<ContextValue> extractContextValues(
        AIEntityConfig config,
        String entityType,
        String entityId,
        Map<String, Object> values
    ) {
        if (config.getMetadataFields() == null) {
            return List.of();
        }
        List<ContextValue> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int order = 0;
        for (AIMetadataField field : config.getMetadataFields()) {
            if (field == null || !StringUtils.hasText(field.getName())) {
                throw failure(
                    entityType,
                    entityId,
                    "<context>",
                    "INDEXING",
                    "CONTEXT_FIELD_NAME_REQUIRED"
                );
            }
            String fieldName = field.getName().trim();
            if (!names.add(fieldName.toLowerCase(Locale.ROOT))) {
                throw failure(
                    entityType,
                    entityId,
                    fieldName,
                    "CONTEXT",
                    "DUPLICATE_CONTEXT_FIELD"
                );
            }
            Set<AIContextDestination> destinations = field.getDestinations();
            if (destinations == null || destinations.isEmpty()) {
                throw failure(
                    entityType,
                    entityId,
                    field.getName(),
                    "INDEXING",
                    "CONTEXT_DESTINATION_REQUIRED"
                );
            }
            int priority = field.getPriority() == null ? 50 : field.getPriority();
            if (priority < 0 || priority > 100) {
                throw failure(
                    entityType,
                    entityId,
                    field.getName(),
                    "INDEXING",
                    "INVALID_CONTEXT_PRIORITY"
                );
            }
            if (field.getDescription() != null
                && field.getDescription().length() > 500) {
                throw failure(
                    entityType,
                    entityId,
                    fieldName,
                    "CONTEXT",
                    "CONTEXT_DESCRIPTION_TOO_LONG"
                );
            }
            Object raw = read(values, fieldName);
            if (isMissing(raw)) {
                if (Boolean.TRUE.equals(field.getRequired())) {
                    throw failure(
                        entityType,
                        entityId,
                        fieldName,
                        destinations.toString(),
                        "REQUIRED_CONTEXT_MISSING"
                    );
                }
                order++;
                continue;
            }
            AIContextDataType dataType = field.getDataType() == null
                ? AIContextDataType.AUTO
                : field.getDataType();
            validateDataType(
                raw,
                dataType,
                entityType,
                entityId,
                fieldName
            );
            Object formatted = formatValue(
                raw,
                dataType,
                field.getFormat(),
                entityType,
                entityId,
                fieldName
            );
            Object value = Boolean.TRUE.equals(field.getSanitizePII())
                ? sanitize(
                    String.valueOf(formatted),
                    entityType,
                    entityId,
                    fieldName
                )
                : safeMetadataValue(formatted);
            result.add(new ContextValue(
                fieldName,
                value,
                dataType,
                normalize(field.getDescription()),
                destinations,
                priority,
                Boolean.TRUE.equals(field.getRequired()),
                order++
            ));
        }
        result.sort(
            Comparator.comparingInt(ContextValue::priority)
                .reversed()
                .thenComparingInt(ContextValue::order)
        );
        return result;
    }

    private Map<String, Object> contextMap(
        List<ContextValue> values,
        AIContextDestination destination
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.stream()
            .filter(value -> value.destinations().contains(destination))
            .forEach(value -> result.put(value.name(), value.value()));
        return result;
    }

    private Map<String, AIContextValue> llmContext(
        List<ContextValue> values,
        int budget,
        String entityType,
        String entityId
    ) {
        Map<String, AIContextValue> result = new LinkedHashMap<>();
        int used = 0;
        for (ContextValue value : values) {
            if (!value.destinations().contains(AIContextDestination.LLM_CONTEXT)) {
                continue;
            }
            AIContextValue contextValue = new AIContextValue(
                value.value(),
                value.dataType(),
                value.description()
            );
            int length;
            try {
                length = value.name().length()
                    + objectMapper.writeValueAsString(contextValue).length()
                    + 1;
            } catch (Exception exception) {
                throw failure(
                    entityType,
                    entityId,
                    value.name(),
                    AIContextDestination.LLM_CONTEXT.name(),
                    "CONTEXT_SERIALIZATION_FAILED"
                );
            }
            if (used + length > budget) {
                if (value.required()) {
                    throw failure(
                        entityType,
                        entityId,
                        value.name(),
                        AIContextDestination.LLM_CONTEXT.name(),
                        "REQUIRED_CONTEXT_EXCEEDS_PROJECTION_BUDGET"
                    );
                }
                continue;
            }
            result.put(value.name(), contextValue);
            used += length;
        }
        return result;
    }

    private Map<String, Object> mergedValues(
        String content,
        Map<String, Object> entity,
        Map<String, Object> metadata
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        putCaseInsensitive(values, entity);
        putCaseInsensitive(values, metadata);
        if (StringUtils.hasText(content)) {
            values.put("content", content.trim());
        }
        return values;
    }

    private void putCaseInsensitive(
        Map<String, Object> target,
        Map<String, Object> source
    ) {
        if (source == null) {
            return;
        }
        source.forEach((key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
                target.put(key.trim().toLowerCase(Locale.ROOT), value);
            }
        });
    }

    private Object read(Map<String, Object> values, String path) {
        String normalizedPath = path.trim().toLowerCase(Locale.ROOT);
        if (values.containsKey(normalizedPath)) {
            return values.get(normalizedPath);
        }
        Object current = values;
        for (String segment : normalizedPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .filter(entry -> segment.equalsIgnoreCase(
                    entry.getKey().toString()
                ))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        }
        return current;
    }

    private String preprocess(
        String value,
        AISearchPreprocessing preprocessing,
        String entityType,
        String entityId,
        String fieldName
    ) {
        return switch (preprocessing) {
            case NONE -> value;
            case NORMALIZE -> normalizeWhitespace(value);
            case CLEAN -> normalizeWhitespace(value.replaceAll("\\p{Cc}", " "));
            case SANITIZE -> normalizeWhitespace(
                sanitize(value, entityType, entityId, fieldName)
                    .replaceAll("\\p{Cc}", " ")
            );
        };
    }

    private String sanitize(
        String value,
        String entityType,
        String entityId,
        String fieldName
    ) {
        PIIDetectionService service = piiDetectionServiceProvider == null
            ? null
            : piiDetectionServiceProvider.getIfAvailable();
        if (service == null) {
            throw failure(
                entityType,
                entityId,
                fieldName,
                "PII",
                "PII_SERVICE_UNAVAILABLE"
            );
        }
        try {
            PIIDetectionResult result = service.detectAndProcess(value);
            if (result == null
                || !StringUtils.hasText(result.getProcessedQuery())) {
                throw failure(
                    entityType,
                    entityId,
                    fieldName,
                    "PII",
                    "PII_PROCESSING_EMPTY"
                );
            }
            if (result.isPiiDetected()
                && value.equals(result.getProcessedQuery())) {
                throw failure(
                    entityType,
                    entityId,
                    fieldName,
                    "PII",
                    "PII_NOT_REDACTED"
                );
            }
            return result.getProcessedQuery();
        } catch (AIProjectionValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(
                entityType,
                entityId,
                fieldName,
                "PII",
                "PII_PROCESSING_FAILED"
            );
        }
    }

    private void validateDataType(
        Object value,
        AIContextDataType dataType,
        String entityType,
        String entityId,
        String fieldName
    ) {
        boolean valid = switch (dataType) {
            case AUTO, JSON -> true;
            case STRING, ID -> value instanceof CharSequence
                || value instanceof Number
                || value instanceof java.util.UUID;
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case DATE -> value instanceof java.time.temporal.TemporalAccessor
                || value instanceof java.util.Date
                || value instanceof CharSequence;
            case ENUM -> value instanceof Enum<?> || value instanceof CharSequence;
        };
        if (!valid) {
            throw failure(
                entityType,
                entityId,
                fieldName,
                "CONTEXT",
                "CONTEXT_DATA_TYPE_MISMATCH"
            );
        }
    }

    private Object formatValue(
        Object value,
        AIContextDataType dataType,
        String format,
        String entityType,
        String entityId,
        String fieldName
    ) {
        if (!StringUtils.hasText(format)) {
            return switch (dataType) {
                case STRING, ID -> String.valueOf(value);
                case ENUM -> value instanceof Enum<?> enumValue
                    ? enumValue.name()
                    : String.valueOf(value);
                case DATE, NUMBER, BOOLEAN, AUTO, JSON ->
                    AIProjectedValueNormalizer.normalize(value, objectMapper);
            };
        }
        try {
            if (value instanceof TemporalAccessor temporalAccessor) {
                return DateTimeFormatter.ofPattern(format)
                    .withZone(ZoneOffset.UTC)
                    .format(temporalAccessor);
            }
            if (value instanceof Date date) {
                SimpleDateFormat formatter =
                    new SimpleDateFormat(format, Locale.ROOT);
                formatter.setTimeZone(TimeZone.getTimeZone(ZoneOffset.UTC));
                return formatter.format(date);
            }
            if (value instanceof Number number) {
                return new DecimalFormat(format).format(number);
            }
        } catch (IllegalArgumentException exception) {
            throw failure(
                entityType,
                entityId,
                fieldName,
                "CONTEXT",
                "INVALID_CONTEXT_FORMAT"
            );
        }
        if (dataType == AIContextDataType.DATE && value instanceof CharSequence) {
            return value;
        }
        throw failure(
            entityType,
            entityId,
            fieldName,
            "CONTEXT",
            "CONTEXT_FORMAT_TYPE_MISMATCH"
        );
    }

    private boolean isMissing(Object value) {
        return value == null
            || (value instanceof CharSequence sequence
                && !StringUtils.hasText(sequence));
    }

    private Object safeMetadataValue(Object value) {
        return AIProjectedValueNormalizer.normalize(value, objectMapper);
    }

    private String descriptorHash(AIEntityConfig config) {
        try {
            byte[] canonical = canonicalObjectMapper.writeValueAsBytes(config);
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical)
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                "Unable to hash configured entity projection",
                exception
            );
        }
    }

    private String requireText(String value, String code) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(code);
        }
        return value.trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeWhitespace(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    private String fragment(SearchValue value) {
        return value.name() + ": " + value.value();
    }

    private void append(StringBuilder output, String value) {
        if (!output.isEmpty()) {
            output.append('\n');
        }
        output.append(value);
    }

    private AIProjectionValidationException failure(
        String entityType,
        String entityId,
        String field,
        String destination,
        String code
    ) {
        return new AIProjectionValidationException(
            entityType,
            entityId,
            field,
            destination,
            code
        );
    }

    private record SearchValue(
        String name,
        String value,
        Set<AISearchDestination> destinations,
        int priority,
        boolean required,
        int order
    ) {
    }

    private record ContextValue(
        String name,
        Object value,
        AIContextDataType dataType,
        String description,
        Set<AIContextDestination> destinations,
        int priority,
        boolean required,
        int order
    ) {
    }
}
