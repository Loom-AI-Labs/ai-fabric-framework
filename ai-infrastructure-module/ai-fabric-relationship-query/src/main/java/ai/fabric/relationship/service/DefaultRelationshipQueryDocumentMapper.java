package ai.fabric.relationship.service;

import ai.fabric.annotation.AICapable;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIMetadataField;
import ai.fabric.dto.AISearchableField;
import ai.fabric.dto.RAGResponse;
import ai.fabric.indexing.api.AIContextDestination;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.AISearchDestination;
import ai.fabric.indexing.model.AIIndexDocument;
import ai.fabric.indexing.projection.AIEntityProjectionService;
import org.springframework.core.annotation.AnnotatedElementUtils;
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
import java.util.Optional;

/**
 * Maps relationship-query results through the approved RAG and response views.
 */
public class DefaultRelationshipQueryDocumentMapper
    implements RelationshipQueryDocumentMapper {

    @Nullable
    private final AIEntityProjectionService projectionService;

    @Nullable
    private final AIEntityConfigurationLoader configurationLoader;

    public DefaultRelationshipQueryDocumentMapper(
        @Nullable AIEntityProjectionService projectionService,
        @Nullable AIEntityConfigurationLoader configurationLoader
    ) {
        this.projectionService = projectionService;
        this.configurationLoader = configurationLoader;
    }

    @Override
    public Optional<RAGResponse.RAGDocument> map(
        String entityType,
        Object entity,
        String entityId
    ) {
        if (entity == null || !StringUtils.hasText(entityId)) {
            return Optional.empty();
        }

        if (AnnotatedElementUtils.findMergedAnnotation(
            entity.getClass(),
            AICapable.class
        ) != null) {
            if (projectionService == null) {
                throw new IllegalStateException(
                    "AIEntityProjectionService is required for @AICapable results"
                );
            }
            AIIndexDocument projection = projectionService.project(
                entity,
                AIProcessOperation.UPDATE,
                ""
            );
            return document(
                entityId,
                projection.ragContextText(),
                projection.responseMetadata()
            );
        }

        AIEntityConfig config = resolveConfig(entityType).orElse(null);
        if (config == null) {
            return Optional.empty();
        }
        return document(
            entityId,
            extractRagContent(entity, config),
            extractResponseMetadata(entity, config)
        );
    }

    private Optional<RAGResponse.RAGDocument> document(
        String entityId,
        String content,
        Map<String, Object> metadata
    ) {
        if (!StringUtils.hasText(content)
            && (metadata == null || metadata.isEmpty())) {
            return Optional.empty();
        }
        return Optional.of(RAGResponse.RAGDocument.builder()
            .id(entityId)
            .content(StringUtils.hasText(content) ? content : null)
            .metadata(metadata == null || metadata.isEmpty() ? null : metadata)
            .build());
    }

    private Optional<AIEntityConfig> resolveConfig(String entityType) {
        if (configurationLoader == null || !StringUtils.hasText(entityType)) {
            return Optional.empty();
        }
        return Optional.ofNullable(
            configurationLoader.getEntityConfig(entityType.trim())
        );
    }

    private String extractRagContent(Object entity, AIEntityConfig config) {
        if (CollectionUtils.isEmpty(config.getSearchableFields())) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        config.getSearchableFields().stream()
            .filter(java.util.Objects::nonNull)
            .filter(field -> field.getDestinations() != null)
            .filter(field -> field.getDestinations().contains(
                AISearchDestination.RAG_CONTEXT
            ))
            .sorted(java.util.Comparator.comparing(
                AISearchableField::getPriority,
                java.util.Comparator.nullsLast(
                    java.util.Comparator.reverseOrder()
                )
            ))
            .forEach(field -> appendValue(parts, entity, field));
        return String.join("\n", parts).trim();
    }

    private void appendValue(
        List<String> parts,
        Object entity,
        AISearchableField field
    ) {
        if (!StringUtils.hasText(field.getName())) {
            return;
        }
        Optional<Object> value = readPropertyPath(entity, field.getName());
        if (value.isEmpty()) {
            if (Boolean.TRUE.equals(field.getRequired())) {
                throw new IllegalStateException(
                    "Required RAG field is missing: " + field.getName()
                );
            }
            return;
        }
        String rendered = String.valueOf(value.get()).trim();
        if (StringUtils.hasText(rendered)) {
            parts.add(field.getName().trim() + ": " + rendered);
        }
    }

    private Map<String, Object> extractResponseMetadata(
        Object entity,
        AIEntityConfig config
    ) {
        if (CollectionUtils.isEmpty(config.getMetadataFields())) {
            return Map.of();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        config.getMetadataFields().stream()
            .filter(java.util.Objects::nonNull)
            .filter(field -> field.getDestinations() != null)
            .filter(field -> field.getDestinations().contains(
                AIContextDestination.API_RESPONSE
            ))
            .sorted(java.util.Comparator.comparing(
                AIMetadataField::getPriority,
                java.util.Comparator.nullsLast(
                    java.util.Comparator.reverseOrder()
                )
            ))
            .forEach(field -> appendMetadata(metadata, entity, field));
        return metadata;
    }

    private void appendMetadata(
        Map<String, Object> metadata,
        Object entity,
        AIMetadataField field
    ) {
        if (!StringUtils.hasText(field.getName())) {
            return;
        }
        Optional<Object> value = readPropertyPath(entity, field.getName());
        if (value.isEmpty()) {
            if (Boolean.TRUE.equals(field.getRequired())) {
                throw new IllegalStateException(
                    "Required response field is missing: " + field.getName()
                );
            }
            return;
        }
        Object raw = value.get();
        metadata.put(
            metadataKey(field.getName()),
            raw instanceof String || raw instanceof Number || raw instanceof Boolean
                ? raw
                : raw.toString()
        );
    }

    private String metadataKey(String configuredName) {
        int dot = configuredName.lastIndexOf('.');
        return dot <= 0 ? configuredName : configuredName.substring(0, dot);
    }

    private Optional<Object> readPropertyPath(Object root, String path) {
        if (root == null || !StringUtils.hasText(path)) {
            return Optional.empty();
        }
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (current == null || !StringUtils.hasText(segment)) {
                return Optional.empty();
            }
            Optional<Object> next = readProperty(current, segment.trim());
            if (next.isEmpty()) {
                return Optional.empty();
            }
            current = next.get();
        }
        return Optional.ofNullable(current);
    }

    private Optional<Object> readProperty(Object target, String property) {
        Class<?> type = target.getClass();
        String suffix = property.substring(0, 1).toUpperCase(Locale.ROOT)
            + property.substring(1);
        for (String getter : List.of("get" + suffix, "is" + suffix, property)) {
            try {
                Method method = type.getMethod(getter);
                return Optional.ofNullable(method.invoke(target));
            } catch (ReflectiveOperationException ignored) {
                // Try the next supported accessor form.
            }
        }
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(property);
                if (!field.trySetAccessible()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(field.get(target));
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (ReflectiveOperationException exception) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
