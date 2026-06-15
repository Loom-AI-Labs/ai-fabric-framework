package ai.fabric.relationship.service;

import ai.fabric.annotation.AICapable;
import ai.fabric.relationship.dto.RelationshipDirection;
import ai.fabric.relationship.model.EntityMapping;
import ai.fabric.relationship.model.RelationshipMapping;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Central registry for mapping logical entity types (e.g. {@code document}) to their backing
 * JPA entity classes as well as the relationship fields that connect those entities.
 */
@Slf4j
@Service
public class EntityRelationshipMapper {

    private final ConcurrentMap<String, EntityMapping> entityMappings = new ConcurrentHashMap<>();
    private final ConcurrentMap<RelationshipKey, RelationshipMapping> relationshipMappings = new ConcurrentHashMap<>();

    /**
     * Registers a mapping by inspecting the {@link AICapable} annotation if present.
     */
    public EntityMapping registerEntityType(Class<?> entityClass) {
        Objects.requireNonNull(entityClass, "entityClass is required");
        AICapable annotation = entityClass.getAnnotation(AICapable.class);
        String entityType = annotation != null && !annotation.entityType().isBlank()
            ? annotation.entityType()
            : inferEntityTypeName(entityClass.getSimpleName());
        return registerEntityType(entityType, entityClass);
    }

    /**
     * Registers a mapping between an entity type and a concrete JPA entity class.
     */
    public EntityMapping registerEntityType(String entityType, Class<?> entityClass) {
        Objects.requireNonNull(entityClass, "entityClass is required");
        return registerEntityType(entityType, entityClass.getName(), entityClass);
    }

    /**
     * Registers a mapping between an entity type and a fully qualified class name.
     * This overload can be used when the actual {@link Class} is not available on the classpath.
     */
    public EntityMapping registerEntityType(String entityType, String className) {
        return registerEntityType(entityType, className, null);
    }

    private EntityMapping registerEntityType(String entityType, String className, Class<?> entityClass) {
        String normalizedType = normalizeEntityType(
            entityType != null && !entityType.isBlank()
                ? entityType
                : inferEntityTypeName(extractSimpleName(className))
        );

        EntityMapping mapping = new EntityMapping(normalizedType, className, entityClass);
        entityMappings.compute(normalizedType, (key, existing) -> {
            if (existing != null && !existing.className().equals(mapping.className())) {
                throw new IllegalStateException(
                    "Entity type '%s' already mapped to '%s'".formatted(entityType, existing.className())
                );
            }
            if (existing == null && log.isDebugEnabled()) {
                log.debug("Registered entity type '{}' -> {}", normalizedType, className);
            }
            return existing != null ? existing : mapping;
        });
        return entityMappings.get(normalizedType);
    }

    public boolean hasEntityType(String entityType) {
        return entityMappings.containsKey(normalizeEntityType(entityType));
    }

    public EntityMapping getEntityMapping(String entityType) {
        return Optional.ofNullable(entityMappings.get(normalizeEntityType(entityType)))
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown entity type '%s'. Registered types: %s"
                    .formatted(entityType, entityMappings.keySet())
            ));
    }

    public Class<?> getEntityClass(String entityType) {
        EntityMapping mapping = getEntityMapping(entityType);
        if (mapping.entityClass() != null) {
            return mapping.entityClass();
        }
        try {
            return Class.forName(mapping.className());
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException(
                "Unable to load class '%s' for entity type '%s'".formatted(mapping.className(), entityType),
                ex
            );
        }
    }

    public String getEntityClassName(String entityType) {
        return getEntityMapping(entityType).className();
    }

    public Map<String, EntityMapping> getAllEntityMappings() {
        Map<String, EntityMapping> copy = new LinkedHashMap<>();
        entityMappings.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> copy.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Registers a relationship between two entity types.
     */
    public RelationshipMapping registerRelationship(String fromEntityType,
                                                     String toEntityType,
                                                     String fieldName) {
        return registerRelationship(fromEntityType, toEntityType, fieldName, RelationshipDirection.FORWARD, false);
    }

    public RelationshipMapping registerRelationship(String fromEntityType,
                                                     String toEntityType,
                                                     String fieldName,
                                                     RelationshipDirection direction,
                                                     boolean optional) {
        Objects.requireNonNull(direction, "direction is required");
        ensureEntityRegistered(fromEntityType);
        ensureEntityRegistered(toEntityType);

        RelationshipMapping mapping = new RelationshipMapping(
            normalizeEntityType(fromEntityType),
            normalizeEntityType(toEntityType),
            fieldName.trim(),
            direction,
            optional
        );

        RelationshipKey key = RelationshipKey.of(fromEntityType, mapping.fieldName());
        relationshipMappings.compute(key, (k, existing) -> {
            if (existing != null) {
                if (existing.toEntityType().equals(mapping.toEntityType())) {
                    return existing;
                }

                EntityMapping existingTarget = entityMappings.get(existing.toEntityType());
                EntityMapping requestedTarget = entityMappings.get(mapping.toEntityType());
                boolean sameBackingClass = existingTarget != null
                    && requestedTarget != null
                    && existingTarget.className().equals(requestedTarget.className());

                if (!sameBackingClass) {
                    throw new IllegalStateException(
                        "Relationship %s.%s already mapped to '%s' (cannot remap to '%s')"
                            .formatted(fromEntityType, mapping.fieldName(), existing.toEntityType(), toEntityType)
                    );
                }

                log.debug(
                    "Remapping relationship {}.{} from {} to {} (same backing class: {})",
                    fromEntityType,
                    mapping.fieldName(),
                    existing.toEntityType(),
                    mapping.toEntityType(),
                    existingTarget.className()
                );
                return mapping;
            }
            if (existing == null && log.isDebugEnabled()) {
                log.debug("Registered relationship {} -> {} via {}", fromEntityType, toEntityType, fieldName);
            }
            return existing != null ? existing : mapping;
        });
        return relationshipMappings.get(key);
    }

    public RelationshipMapping getRelationshipMapping(String fromEntityType, String relationshipFieldName) {
        RelationshipKey key = RelationshipKey.of(fromEntityType, relationshipFieldName);
        return Optional.ofNullable(relationshipMappings.get(key))
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown relationship '%s.%s'".formatted(fromEntityType, relationshipFieldName)
            ));
    }

    public String getRelationshipFieldName(String fromEntityType, String relationshipFieldName) {
        return getRelationshipMapping(fromEntityType, relationshipFieldName).fieldName();
    }

    public List<RelationshipMapping> getAllRelationshipMappings() {
        List<RelationshipMapping> list = new ArrayList<>(relationshipMappings.values());
        list.sort((a, b) -> {
            int cmp = a.fromEntityType().compareTo(b.fromEntityType());
            if (cmp != 0) {
                return cmp;
            }
            return a.toEntityType().compareTo(b.toEntityType());
        });
        return Collections.unmodifiableList(list);
    }

    private void ensureEntityRegistered(String entityType) {
        String normalized = normalizeEntityType(entityType);
        if (!entityMappings.containsKey(normalized)) {
            throw new IllegalArgumentException(
                "Entity type '%s' must be registered before defining relationships. Registered types: %s"
                    .formatted(entityType, entityMappings.keySet())
            );
        }
    }

    private String normalizeEntityType(String entityType) {
        Objects.requireNonNull(entityType, "entityType is required");
        String sanitized = entityType.trim();
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("entityType cannot be blank");
        }
        return sanitized.toLowerCase(Locale.ROOT);
    }

    private String inferEntityTypeName(String simpleName) {
        if (simpleName == null || simpleName.isBlank()) {
            throw new IllegalArgumentException("Unable to infer entity type from class name");
        }
        StringBuilder builder = new StringBuilder();
        char[] chars = simpleName.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char current = chars[i];
            if (Character.isUpperCase(current) && i != 0) {
                builder.append('-');
            }
            builder.append(Character.toLowerCase(current));
        }
        return builder.toString();
    }

    private String extractSimpleName(String className) {
        int idx = className.lastIndexOf('.');
        return idx >= 0 ? className.substring(idx + 1) : className;
    }

    private record RelationshipKey(String from, String relationshipFieldName) {
        static RelationshipKey of(String from, String relationshipFieldName) {
            return new RelationshipKey(
                from == null ? null : from.trim().toLowerCase(Locale.ROOT),
                relationshipFieldName == null ? null : relationshipFieldName.trim().toLowerCase(Locale.ROOT)
            );
        }
    }
}
