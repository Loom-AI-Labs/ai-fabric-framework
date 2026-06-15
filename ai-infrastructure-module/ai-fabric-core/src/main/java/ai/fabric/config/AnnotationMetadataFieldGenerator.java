package ai.fabric.config;

import ai.fabric.dto.AIMetadataField;
import ai.fabric.processor.AnnotationFieldScanner;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds AI metadata schema entries from @AIContext annotations.
 *
 * <p>Produces {@link AIMetadataField} definitions (name/type/includeInSearch) from
 * {@link ai.fabric.annotation.AIContext} fields. Types come from the
 * annotation {@code dataType} hint when provided, otherwise inferred from the Java type.</p>
 *
 * <p>YAML remains the source of truth; this class is meant to provide a framework-default
 * schema that can be merged and overridden by YAML configuration.</p>
 */
@Slf4j
public class AnnotationMetadataFieldGenerator {

    private final AnnotationFieldScanner annotationFieldScanner;

    public AnnotationMetadataFieldGenerator(AnnotationFieldScanner annotationFieldScanner) {
        this.annotationFieldScanner = Objects.requireNonNull(annotationFieldScanner, "annotationFieldScanner");
    }

    public List<AIMetadataField> buildMetadataFields(Class<?> entityClass) {
        if (entityClass == null) {
            return List.of();
        }

        List<AnnotationFieldScanner.ContextFieldMetadata> contextFields =
            annotationFieldScanner.getContextFields(entityClass);
        if (contextFields == null || contextFields.isEmpty()) {
            return List.of();
        }

        List<AIMetadataField> fields = new ArrayList<>(contextFields.size());
        for (AnnotationFieldScanner.ContextFieldMetadata contextField : contextFields) {
            if (contextField == null) {
                continue;
            }
            String name = contextField.getContextKey();
            if (name == null || name.isBlank()) {
                continue;
            }
            String type = resolveType(contextField.getField().getType(), contextField.getDataType());
            fields.add(AIMetadataField.builder()
                .name(name)
                .type(type)
                .includeInSearch(true)
                .build());
        }

        return List.copyOf(fields);
    }

    static String resolveType(Class<?> javaType, String dataTypeHint) {
        String normalizedHint = normalizeHint(dataTypeHint);
        if (!"auto".equals(normalizedHint)) {
            return normalizedHint;
        }

        if (javaType == null) {
            return "string";
        }

        if (javaType.isEnum()) {
            return "enum";
        }

        if (javaType == boolean.class || javaType == Boolean.class) {
            return "boolean";
        }

        if (javaType == byte.class || javaType == short.class || javaType == int.class
            || javaType == long.class || javaType == float.class || javaType == double.class) {
            return "number";
        }

        if (Number.class.isAssignableFrom(javaType)
            || BigDecimal.class.isAssignableFrom(javaType)
            || BigInteger.class.isAssignableFrom(javaType)) {
            return "number";
        }

        if (javaType == LocalDate.class || javaType == LocalDateTime.class
            || javaType == Instant.class || javaType == OffsetDateTime.class
            || javaType == ZonedDateTime.class
            || java.util.Date.class.isAssignableFrom(javaType)) {
            return "date";
        }

        if (UUID.class.isAssignableFrom(javaType)) {
            return "id";
        }

        if (CharSequence.class.isAssignableFrom(javaType) || javaType == Character.class || javaType == char.class) {
            return "string";
        }

        if (Map.class.isAssignableFrom(javaType)) {
            return "json";
        }

        if (javaType.isArray()
            || java.lang.Iterable.class.isAssignableFrom(javaType)) {
            return "json";
        }

        // Default for complex/nested types
        return "json";
    }

    private static String normalizeHint(String hint) {
        if (hint == null || hint.isBlank()) {
            return "auto";
        }
        String value = hint.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "auto" -> "auto";
            case "string", "text" -> "string";
            case "number", "numeric", "int", "integer", "float", "double", "decimal" -> "number";
            case "bool", "boolean" -> "boolean";
            case "date", "datetime", "timestamp" -> "date";
            case "enum" -> "enum";
            case "id", "uuid" -> "id";
            case "json", "object" -> "json";
            default -> {
                log.debug("Unknown @AIContext dataType hint '{}'; falling back to 'string'", value);
                yield "string";
            }
        };
    }
}

