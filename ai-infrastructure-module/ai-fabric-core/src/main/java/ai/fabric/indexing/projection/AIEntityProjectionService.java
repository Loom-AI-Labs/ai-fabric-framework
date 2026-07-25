package ai.fabric.indexing.projection;

import ai.fabric.dto.PIIDetectionResult;
import ai.fabric.indexing.api.AIContextDataType;
import ai.fabric.indexing.api.AIContextDestination;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.AISearchDestination;
import ai.fabric.indexing.api.AISearchPreprocessing;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.indexing.model.AIContextFieldDescriptor;
import ai.fabric.indexing.model.AIContextValue;
import ai.fabric.indexing.model.AIEntityDescriptor;
import ai.fabric.indexing.model.AIIndexDocument;
import ai.fabric.indexing.model.AISearchFieldDescriptor;
import ai.fabric.privacy.pii.PIIDetectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;

/**
 * Builds safe, immutable index documents from compiled entity descriptors.
 */
public class AIEntityProjectionService {

    private final AIEntityDescriptorRegistry descriptorRegistry;
    private final ObjectProvider<PIIDetectionService> piiDetectionServiceProvider;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AIEntityProjectionService(
        AIEntityDescriptorRegistry descriptorRegistry,
        ObjectProvider<PIIDetectionService> piiDetectionServiceProvider,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.descriptorRegistry = Objects.requireNonNull(descriptorRegistry);
        this.piiDetectionServiceProvider = piiDetectionServiceProvider;
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    public AIIndexDocument project(
        Object entity,
        AIProcessOperation operation,
        String correlationId
    ) {
        Objects.requireNonNull(entity, "entity is required");
        Objects.requireNonNull(operation, "operation is required");
        if (operation == AIProcessOperation.DELETE) {
            return projectDelete(entity, operation, correlationId);
        }

        AIEntityDescriptor descriptor = descriptorRegistry.resolve(entity);
        requireIndexingEnabled(descriptor);
        String entityId = resolveIdentity(descriptor, entity);

        Map<String, String> processedSearchValues = extractSearchValues(
            descriptor,
            entity,
            entityId
        );
        String semanticSearchText = renderSearchProjection(
            descriptor,
            processedSearchValues,
            AISearchDestination.SEMANTIC_SEARCH,
            entityId
        );
        String ragContextText = renderSearchProjection(
            descriptor,
            processedSearchValues,
            AISearchDestination.RAG_CONTEXT,
            entityId
        );

        List<ExtractedContext> contextValues = extractContextValues(
            descriptor,
            entity,
            entityId
        );
        Map<String, Object> vectorMetadata = contextMap(
            contextValues,
            AIContextDestination.VECTOR_METADATA
        );
        vectorMetadata.put("_aifabricSchemaVersion", AIIndexDocument.CURRENT_SCHEMA_VERSION);
        vectorMetadata.put("_aifabricDescriptorHash", descriptor.projectionHash());
        vectorMetadata.put("_aifabricSourceOperation", operation.name());

        Map<String, AIContextValue> llmContext = llmContextMap(
            descriptor,
            contextValues,
            entityId
        );
        Map<String, Object> responseMetadata = contextMap(
            contextValues,
            AIContextDestination.API_RESPONSE
        );
        Long sourceVersion = sourceVersion(vectorMetadata);

        return new AIIndexDocument(
            AIIndexDocument.CURRENT_SCHEMA_VERSION,
            descriptor.projectionHash(),
            descriptor.entityType(),
            entityId,
            AIIndexWorkType.UPSERT,
            operation,
            semanticSearchText,
            ragContextText,
            vectorMetadata,
            llmContext,
            responseMetadata,
            sourceVersion,
            normalizeCorrelationId(correlationId),
            Instant.now(clock)
        );
    }

    public AIIndexDocument projectDelete(
        Object entity,
        AIProcessOperation operation,
        String correlationId
    ) {
        Objects.requireNonNull(entity, "entity is required");
        AIEntityDescriptor descriptor = descriptorRegistry.resolve(entity);
        requireIndexingEnabled(descriptor);
        return deleteDocument(
            descriptor,
            resolveIdentity(descriptor, entity),
            operation,
            correlationId
        );
    }

    public AIIndexDocument projectDelete(
        Class<?> entityClass,
        String entityId,
        AIProcessOperation operation,
        String correlationId
    ) {
        AIEntityDescriptor descriptor = descriptorRegistry.resolve(entityClass);
        requireIndexingEnabled(descriptor);
        return deleteDocument(
            descriptor,
            requireIdentity(entityId, descriptor.entityType()),
            operation,
            correlationId
        );
    }

    private AIIndexDocument deleteDocument(
        AIEntityDescriptor descriptor,
        String entityId,
        AIProcessOperation operation,
        String correlationId
    ) {
        if (operation != AIProcessOperation.DELETE) {
            throw new IllegalArgumentException("Delete projection requires DELETE operation");
        }
        return new AIIndexDocument(
            AIIndexDocument.CURRENT_SCHEMA_VERSION,
            descriptor.projectionHash(),
            descriptor.entityType(),
            entityId,
            AIIndexWorkType.DELETE,
            AIProcessOperation.DELETE,
            null,
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            null,
            normalizeCorrelationId(correlationId),
            Instant.now(clock)
        );
    }

    private Map<String, String> extractSearchValues(
        AIEntityDescriptor descriptor,
        Object entity,
        String entityId
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        for (AISearchFieldDescriptor field : descriptor.searchableFields()) {
            Object raw = field.accessor().read(entity);
            String value = raw == null ? null : String.valueOf(raw);
            if (!StringUtils.hasText(value)) {
                if (field.required()) {
                    throw projectionFailure(
                        descriptor,
                        entityId,
                        field.name(),
                        field.destinations().toString(),
                        "REQUIRED_FIELD_MISSING"
                    );
                }
                continue;
            }
            String processed = preprocess(value, field.preprocessing(), descriptor, entityId, field.name());
            if (field.maxLength() > 0 && processed.length() > field.maxLength()) {
                processed = processed.substring(0, field.maxLength());
            }
            if (!StringUtils.hasText(processed)) {
                if (field.required()) {
                    throw projectionFailure(
                        descriptor,
                        entityId,
                        field.name(),
                        field.destinations().toString(),
                        "REQUIRED_FIELD_EMPTY_AFTER_PROCESSING"
                    );
                }
                continue;
            }
            values.put(field.name(), processed);
        }
        return values;
    }

    private String renderSearchProjection(
        AIEntityDescriptor descriptor,
        Map<String, String> values,
        AISearchDestination destination,
        String entityId
    ) {
        List<SearchFragment> fragments = descriptor.searchableFields().stream()
            .filter(field -> field.destinations().contains(destination))
            .filter(field -> values.containsKey(field.name()))
            .map(field -> new SearchFragment(
                field,
                field.name() + ": " + values.get(field.name())
            ))
            .toList();

        int requiredCount = (int) fragments.stream()
            .filter(fragment -> fragment.field.required())
            .count();
        int requiredTextCharacters = fragments.stream()
            .filter(fragment -> fragment.field.required())
            .mapToInt(fragment -> fragment.text.length())
            .sum();
        int requiredCharacters = requiredTextCharacters
            + Math.max(0, requiredCount - 1);
        if (requiredCharacters > descriptor.projectionMaxCharacters()) {
            throw projectionFailure(
                descriptor,
                entityId,
                "<projection>",
                destination.name(),
                "REQUIRED_FIELDS_EXCEED_PROJECTION_BUDGET"
            );
        }

        StringBuilder output = new StringBuilder();
        int requiredTextRemaining = requiredTextCharacters;
        int requiredCountRemaining = requiredCount;
        for (SearchFragment fragment : fragments) {
            int separator = output.isEmpty() ? 0 : 1;
            int needed = separator + fragment.text.length();
            if (fragment.field.required()) {
                appendFragment(output, fragment.text);
                requiredTextRemaining -= fragment.text.length();
                requiredCountRemaining--;
                continue;
            }

            int requiredRemaining = requiredTextRemaining
                + Math.max(0, requiredCountRemaining - 1)
                + (requiredCountRemaining > 0 ? 1 : 0);
            int available = descriptor.projectionMaxCharacters()
                - output.length()
                - requiredRemaining;
            if (available <= separator) {
                continue;
            }
            if (needed <= available) {
                appendFragment(output, fragment.text);
            } else {
                int contentBudget = available - separator;
                if (contentBudget >= fragment.field.name().length() + 3) {
                    appendFragment(output, fragment.text.substring(0, contentBudget));
                }
            }
        }

        if (destination == AISearchDestination.SEMANTIC_SEARCH && output.isEmpty()) {
            throw projectionFailure(
                descriptor,
                entityId,
                "<projection>",
                destination.name(),
                "SEMANTIC_SEARCH_PROJECTION_EMPTY"
            );
        }
        return output.toString();
    }

    private List<ExtractedContext> extractContextValues(
        AIEntityDescriptor descriptor,
        Object entity,
        String entityId
    ) {
        List<ExtractedContext> values = new ArrayList<>();
        for (AIContextFieldDescriptor field : descriptor.contextFields()) {
            Object raw = field.accessor().read(entity);
            if (raw == null) {
                if (field.required()) {
                    throw projectionFailure(
                        descriptor,
                        entityId,
                        field.key(),
                        field.destinations().toString(),
                        "REQUIRED_CONTEXT_MISSING"
                    );
                }
                continue;
            }
            Object formatted = formatContextValue(raw, field);
            if (field.sanitizePII()) {
                formatted = sanitize(
                    String.valueOf(formatted),
                    descriptor,
                    entityId,
                    field.key()
                );
            }
            values.add(new ExtractedContext(field, formatted));
        }
        return values;
    }

    private Map<String, Object> contextMap(
        List<ExtractedContext> values,
        AIContextDestination destination
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.stream()
            .filter(value -> value.field.destinations().contains(destination))
            .forEach(value -> result.put(value.field.key(), value.value));
        return result;
    }

    private Map<String, AIContextValue> llmContextMap(
        AIEntityDescriptor descriptor,
        List<ExtractedContext> values,
        String entityId
    ) {
        Map<String, AIContextValue> result = new LinkedHashMap<>();
        int used = 0;
        for (ExtractedContext value : values) {
            if (!value.field.destinations().contains(AIContextDestination.LLM_CONTEXT)) {
                continue;
            }
            AIContextValue contextValue = new AIContextValue(
                value.value,
                value.field.dataType(),
                value.field.description()
            );
            int estimated = estimateContextCharacters(value.field.key(), contextValue);
            if (used + estimated > descriptor.projectionMaxCharacters()) {
                if (value.field.required()) {
                    throw projectionFailure(
                        descriptor,
                        entityId,
                        value.field.key(),
                        AIContextDestination.LLM_CONTEXT.name(),
                        "REQUIRED_CONTEXT_EXCEEDS_PROJECTION_BUDGET"
                    );
                }
                continue;
            }
            result.put(value.field.key(), contextValue);
            used += estimated;
        }
        return result;
    }

    private int estimateContextCharacters(String key, AIContextValue value) {
        try {
            return key.length() + objectMapper.writeValueAsString(value).length() + 1;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to estimate projected context size", exception);
        }
    }

    private String preprocess(
        String value,
        AISearchPreprocessing preprocessing,
        AIEntityDescriptor descriptor,
        String entityId,
        String fieldName
    ) {
        return switch (preprocessing) {
            case NONE -> value;
            case NORMALIZE -> normalize(value);
            case CLEAN -> clean(value);
            case SANITIZE -> clean(sanitize(value, descriptor, entityId, fieldName));
        };
    }

    private String sanitize(
        String value,
        AIEntityDescriptor descriptor,
        String entityId,
        String fieldName
    ) {
        PIIDetectionService service = piiDetectionServiceProvider == null
            ? null
            : piiDetectionServiceProvider.getIfAvailable();
        if (service == null) {
            throw projectionFailure(
                descriptor,
                entityId,
                fieldName,
                "PII",
                "PII_SERVICE_UNAVAILABLE"
            );
        }
        try {
            PIIDetectionResult result = service.detectAndProcess(value);
            if (result == null || !StringUtils.hasText(result.getProcessedQuery())) {
                throw projectionFailure(
                    descriptor,
                    entityId,
                    fieldName,
                    "PII",
                    "PII_PROCESSING_EMPTY"
                );
            }
            if (result.isPiiDetected() && value.equals(result.getProcessedQuery())) {
                throw projectionFailure(
                    descriptor,
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
            throw projectionFailure(
                descriptor,
                entityId,
                fieldName,
                "PII",
                "PII_PROCESSING_FAILED"
            );
        }
    }

    private Object formatContextValue(Object value, AIContextFieldDescriptor field) {
        if (StringUtils.hasText(field.format())) {
            if (value instanceof Number number) {
                return new DecimalFormat(field.format()).format(number);
            }
            if (value instanceof Date date) {
                SimpleDateFormat formatter = new SimpleDateFormat(field.format());
                formatter.setTimeZone(TimeZone.getTimeZone(ZoneOffset.UTC));
                return formatter.format(date);
            }
            if (value instanceof TemporalAccessor temporal) {
                return DateTimeFormatter.ofPattern(field.format())
                    .withZone(ZoneOffset.UTC)
                    .format(temporal);
            }
        }
        return switch (field.dataType()) {
            case STRING, ID -> String.valueOf(value);
            case ENUM -> value instanceof Enum<?> enumValue
                ? enumValue.name()
                : String.valueOf(value);
            case DATE -> AIProjectedValueNormalizer.normalize(value, objectMapper);
            case NUMBER, BOOLEAN, AUTO, JSON ->
                AIProjectedValueNormalizer.normalize(value, objectMapper);
        };
    }

    private Long sourceVersion(Map<String, Object> metadata) {
        Object value = metadata.get("version");
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof CharSequence sequence) {
            try {
                return Long.parseLong(sequence.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String resolveIdentity(AIEntityDescriptor descriptor, Object entity) {
        Object identity = descriptor.identityResolver().resolveIdentity(entity);
        return requireIdentity(identity == null ? null : identity.toString(), descriptor.entityType());
    }

    private String requireIdentity(String identity, String entityType) {
        if (!StringUtils.hasText(identity)) {
            throw new AIProjectionValidationException(
                entityType,
                null,
                "<identity>",
                "IDENTITY",
                "IDENTITY_MISSING"
            );
        }
        return identity.trim();
    }

    private void requireIndexingEnabled(AIEntityDescriptor descriptor) {
        if (!descriptor.indexingEnabled()) {
            throw new AIProjectionValidationException(
                descriptor.entityType(),
                null,
                "<entity>",
                "INDEXING",
                "INDEXING_DISABLED"
            );
        }
    }

    private String normalizeCorrelationId(String correlationId) {
        return correlationId == null ? "" : correlationId.trim();
    }

    private String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    private String clean(String value) {
        return normalize(value.replaceAll("\\p{Cc}", " "));
    }

    private void appendFragment(StringBuilder output, String fragment) {
        if (!output.isEmpty()) {
            output.append('\n');
        }
        output.append(fragment);
    }

    private AIProjectionValidationException projectionFailure(
        AIEntityDescriptor descriptor,
        String entityId,
        String fieldName,
        String destination,
        String errorCode
    ) {
        return new AIProjectionValidationException(
            descriptor.entityType(),
            entityId,
            fieldName,
            destination,
            errorCode
        );
    }

    private record SearchFragment(
        AISearchFieldDescriptor field,
        String text
    ) {
    }

    private record ExtractedContext(
        AIContextFieldDescriptor field,
        Object value
    ) {
    }
}
