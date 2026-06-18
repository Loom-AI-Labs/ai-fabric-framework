package ai.fabric.relationship.service;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIMetadataField;
import ai.fabric.dto.AISearchableField;
import ai.fabric.dto.RAGResponse;
import ai.fabric.processor.AnnotationFieldScanner;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Default mapper that supports both v2 annotations (@AISearchable/@AIContext) and
 * YAML entity config (ai-entity-config.yml).
 *
 * <p>Strict behavior: when neither annotations nor YAML produce any content/metadata,
 * this mapper returns {@link Optional#empty()} and callers can fall back to ID-only responses.</p>
 */
public class DefaultRelationshipQueryDocumentMapper implements RelationshipQueryDocumentMapper {

    @Nullable
    private final AnnotationFieldScanner annotationFieldScanner;

    @Nullable
    private final AIEntityConfigurationLoader configurationLoader;

    public DefaultRelationshipQueryDocumentMapper(@Nullable AnnotationFieldScanner annotationFieldScanner,
                                                  @Nullable AIEntityConfigurationLoader configurationLoader) {
        this.annotationFieldScanner = annotationFieldScanner;
        this.configurationLoader = configurationLoader;
    }

    @Override
    public Optional<RAGResponse.RAGDocument> map(String entityType, Object entity, String entityId) {
        if (entity == null || !StringUtils.hasText(entityId)) {
            return Optional.empty();
        }

        String content = "";
        Map<String, Object> metadata = new LinkedHashMap<>();

        if (annotationFieldScanner != null) {
            try {
                content = annotationFieldScanner.extractSearchableContent(entity);
            } catch (RuntimeException ignored) {
            }
            metadata.putAll(extractContextMetadataForResponse(entity));
        }

        Optional<AIEntityConfig> config = resolveConfig(entityType);
        if (config.isPresent()) {
            AIEntityConfig entityConfig = config.get();
            if (!StringUtils.hasText(content)) {
                content = extractSearchableContentFromConfig(entity, entityConfig);
            }
            metadata.putAll(extractMetadataFromConfig(entity, entityConfig));
        }

        if (!StringUtils.hasText(content) && metadata.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(RAGResponse.RAGDocument.builder()
            .id(entityId)
            .content(StringUtils.hasText(content) ? content : null)
            .metadata(!metadata.isEmpty() ? metadata : null)
            .build());
    }

    private Map<String, Object> extractContextMetadataForResponse(Object entity) {
        if (annotationFieldScanner == null) {
            return Map.of();
        }

        List<AnnotationFieldScanner.ContextFieldMetadata> contextFields =
            annotationFieldScanner.getContextFields(entity.getClass());
        if (contextFields.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        for (AnnotationFieldScanner.ContextFieldMetadata field : contextFields) {
            if (field == null || !field.isIncludeInResponse()) {
                continue;
            }
            try {
                Object raw = field.getField().get(entity);
                if (raw != null) {
                    metadata.put(field.getContextKey(), raw);
                }
            } catch (Exception ignored) {
            }
        }

        return metadata;
    }

    private Optional<AIEntityConfig> resolveConfig(String entityType) {
        if (configurationLoader == null || !StringUtils.hasText(entityType)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(configurationLoader.getEntityConfig(entityType));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private String extractSearchableContentFromConfig(Object entity, AIEntityConfig config) {
        if (config == null || CollectionUtils.isEmpty(config.getSearchableFields())) {
            return "";
        }

        List<String> parts = new ArrayList<>();
        for (AISearchableField field : config.getSearchableFields()) {
            if (field == null || !field.isIncludeInRAG() || !StringUtils.hasText(field.getName())) {
                continue;
            }
            Optional<Object> value = readPropertyPath(entity, field.getName());
            if (value.isEmpty()) {
                continue;
            }
            String rendered = renderValue(value.get());
            if (StringUtils.hasText(rendered)) {
                parts.add(rendered);
            }
        }
        return String.join(" ", parts).trim();
    }

    private Map<String, Object> extractMetadataFromConfig(Object entity, AIEntityConfig config) {
        if (config == null || CollectionUtils.isEmpty(config.getMetadataFields())) {
            return Map.of();
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        for (AIMetadataField field : config.getMetadataFields()) {
            if (field == null || !StringUtils.hasText(field.getName())) {
                continue;
            }
            Optional<Object> value = readPropertyPath(entity, field.getName());
            if (value.isEmpty()) {
                continue;
            }
            metadata.put(metadataKey(field.getName()), simplifyMetadataValue(value.get()));
        }
        return metadata;
    }

    private String metadataKey(String configuredName) {
        int dot = configuredName.lastIndexOf('.');
        if (dot <= 0) {
            return configuredName;
        }
        return configuredName.substring(0, dot);
    }

    private Object simplifyMetadataValue(Object value) {
        if (isJsonFriendlyScalar(value)) {
            return value;
        }
        return value.toString();
    }

    private boolean isJsonFriendlyScalar(Object value) {
        return value instanceof String
            || value instanceof Number
            || value instanceof Boolean;
    }

    private String renderValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String str) {
            return str;
        }
        return value.toString();
    }

    private Optional<Object> readPropertyPath(Object root, String path) {
        if (root == null || !StringUtils.hasText(path)) {
            return Optional.empty();
        }

        Object current = root;
        String[] segments = path.split("\\.");
        for (String segment : segments) {
            if (current == null) {
                return Optional.empty();
            }
            String name = segment.trim();
            if (!StringUtils.hasText(name)) {
                return Optional.empty();
            }
            Optional<Object> next = readProperty(current, name);
            if (next.isEmpty()) {
                return Optional.empty();
            }
            current = next.get();
        }
        return Optional.ofNullable(current);
    }

    private Optional<Object> readProperty(Object target, String property) {
        Class<?> type = target.getClass();
        String getterSuffix = property.substring(0, 1).toUpperCase(Locale.ROOT) + property.substring(1);
        List<String> candidateGetters = List.of("get" + getterSuffix, "is" + getterSuffix, property);

        for (String methodName : candidateGetters) {
            try {
                Method method = type.getMethod(methodName);
                if (method.getParameterCount() == 0) {
                    return Optional.ofNullable(method.invoke(target));
                }
            } catch (Exception ignored) {
            }
        }

        Optional<Field> field = findField(type, property);
        if (field.isPresent()) {
            try {
                Field resolved = field.get();
                resolved.setAccessible(true);
                return Optional.ofNullable(resolved.get(target));
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }

        return Optional.empty();
    }

    private Optional<Field> findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return Optional.of(current.getDeclaredField(name));
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return Optional.empty();
    }
}
