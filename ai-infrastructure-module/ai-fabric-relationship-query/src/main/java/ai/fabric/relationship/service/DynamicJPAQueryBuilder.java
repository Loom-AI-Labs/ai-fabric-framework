package ai.fabric.relationship.service;

import ai.fabric.relationship.dto.FilterCondition;
import ai.fabric.relationship.dto.FilterOperator;
import ai.fabric.relationship.dto.JpqlQuery;
import ai.fabric.relationship.dto.RelationshipPath;
import ai.fabric.relationship.dto.RelationshipQueryPlan;
import ai.fabric.relationship.model.EntityMapping;
import ai.fabric.relationship.service.EntityRelationshipMapper;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Converts {@link RelationshipQueryPlan} objects into executable JPQL queries.
 */
public class DynamicJPAQueryBuilder {

    private final EntityRelationshipMapper relationshipMapper;
    private final ConcurrentMap<String, Class<?>> expectedTypeCache = new ConcurrentHashMap<>();
    private static final Class<?> NO_TYPE = Void.class;
    public DynamicJPAQueryBuilder(EntityRelationshipMapper relationshipMapper) {
        this.relationshipMapper = Objects.requireNonNull(relationshipMapper);
    }

    public JpqlQuery buildQuery(RelationshipQueryPlan plan) {
        EntityMapping rootMapping = relationshipMapper.getEntityMapping(plan.getPrimaryEntityType());
        String rootAlias = "root";
        AliasRegistry aliases = new AliasRegistry();
        aliases.register(plan.getPrimaryEntityType(), rootAlias);

        StringBuilder builder = new StringBuilder()
            .append("SELECT DISTINCT ").append(rootAlias)
            .append(" FROM ").append(rootMapping.simpleClassName()).append(" ").append(rootAlias);

        List<String> joins = new ArrayList<>();
        List<String> predicates = new ArrayList<>();
        Map<String, Object> parameters = new LinkedHashMap<>();
        AtomicInteger paramSequence = new AtomicInteger(0);

        appendRelationshipJoins(plan.getRelationshipPaths(), aliases, joins, predicates, parameters, paramSequence);
        appendFilters(plan, aliases, predicates, parameters, paramSequence);

        joins.forEach(builder::append);
        if (!predicates.isEmpty()) {
            builder.append(" WHERE ")
                .append(String.join(" AND ", predicates));
        }

        return JpqlQuery.builder()
            .jpql(builder.toString())
            .parameters(parameters)
            .limit(plan.getLimit())
            .rootAlias(rootAlias)
            .build();
    }

    private void appendRelationshipJoins(List<RelationshipPath> paths,
                                         AliasRegistry aliases,
                                         List<String> joins,
                                         List<String> predicates,
                                         Map<String, Object> parameters,
                                         AtomicInteger paramSequence) {
        if (CollectionUtils.isEmpty(paths)) {
            return;
        }
        preRegisterAliases(paths, aliases);
        for (RelationshipPath path : paths) {
            if (path == null || !StringUtils.hasText(path.getRelationshipType())) {
                continue;
            }
            String fromAlias = aliases.aliasFor(path.getFromEntityType());
            if (!StringUtils.hasText(fromAlias)) {
                fromAlias = aliases.register(path.getFromEntityType());
            }
            String toAlias = aliases.aliasFor(path.getToEntityType());
            if (!StringUtils.hasText(toAlias)) {
                toAlias = aliases.register(path.getToEntityType());
            }
            String joinKeyword = path.isOptional() ? " LEFT JOIN FETCH " : " JOIN FETCH ";
            joins.add(joinKeyword + fromAlias + "." + path.getRelationshipType() + " " + toAlias);
            if (!CollectionUtils.isEmpty(path.getConditions())) {
                String targetAlias = toAlias;
                path.getConditions().forEach(condition ->
                    predicates.add(buildPredicate(condition, targetAlias, aliases, parameters, paramSequence)));
            }
        }
    }

    private void preRegisterAliases(List<RelationshipPath> paths, AliasRegistry aliases) {
        for (RelationshipPath path : paths) {
            if (path == null) {
                continue;
            }
            if (StringUtils.hasText(path.getFromEntityType()) && aliases.aliasFor(path.getFromEntityType()) == null) {
                aliases.register(path.getFromEntityType());
            }
            if (!StringUtils.hasText(path.getToEntityType())) {
                continue;
            }
            if (StringUtils.hasText(path.getAlias())) {
                aliases.register(path.getToEntityType(), path.getAlias());
            } else if (aliases.aliasFor(path.getToEntityType()) == null) {
                aliases.register(path.getToEntityType());
            }
        }
    }

    private void appendFilters(RelationshipQueryPlan plan,
                               AliasRegistry aliases,
                               List<String> predicates,
                               Map<String, Object> parameters,
                               AtomicInteger paramSequence) {
        if (!CollectionUtils.isEmpty(plan.getDirectFilters())) {
            plan.getDirectFilters().forEach((entity, filters) ->
                appendFilterGroup(entity, filters, aliases, predicates, parameters, paramSequence));
        }
        if (!CollectionUtils.isEmpty(plan.getRelationshipFilters())) {
            plan.getRelationshipFilters().forEach((entity, filters) ->
                appendFilterGroup(entity, filters, aliases, predicates, parameters, paramSequence));
        }
    }

    private void appendFilterGroup(String entity,
                                   List<FilterCondition> filters,
                                   AliasRegistry aliases,
                                   List<String> predicates,
                                   Map<String, Object> parameters,
                                   AtomicInteger paramSequence) {
        if (CollectionUtils.isEmpty(filters)) {
            return;
        }
        String alias = aliases.aliasFor(entity);
        if (alias == null) {
            alias = aliases.register(entity);
        }
        for (FilterCondition condition : filters) {
            predicates.add(buildPredicate(condition, alias, aliases, parameters, paramSequence));
        }
    }

    private String buildPredicate(FilterCondition condition,
                                  String alias,
                                  AliasRegistry aliases,
                                  Map<String, Object> parameters,
                                  AtomicInteger paramSequence) {
        String fieldName = resolveFieldName(condition, alias, aliases);
        String parameterName = "p" + paramSequence.incrementAndGet();
        Object value = condition.getValue();
        FilterOperator operator = condition.getOperator() != null
            ? condition.getOperator()
            : FilterOperator.EQUALS;
        Optional<String> referencedField = resolveFieldReference(value, aliases);
        if (referencedField.isEmpty()) {
            value = coerceParameterValue(value, condition);
        }

        return switch (operator) {
            case EQUALS -> {
                if (referencedField.isPresent()) {
                    yield "%s = %s".formatted(fieldName, referencedField.get());
                }
                parameters.put(parameterName, value);
                yield "%s = :%s".formatted(fieldName, parameterName);
            }
            case NOT_EQUALS -> {
                if (referencedField.isPresent()) {
                    yield "%s <> %s".formatted(fieldName, referencedField.get());
                }
                parameters.put(parameterName, value);
                yield "%s <> :%s".formatted(fieldName, parameterName);
            }
            case GREATER_THAN -> {
                parameters.put(parameterName, value);
                yield "%s > :%s".formatted(fieldName, parameterName);
            }
            case GREATER_THAN_OR_EQUAL -> {
                parameters.put(parameterName, value);
                yield "%s >= :%s".formatted(fieldName, parameterName);
            }
            case LESS_THAN -> {
                parameters.put(parameterName, value);
                yield "%s < :%s".formatted(fieldName, parameterName);
            }
            case LESS_THAN_OR_EQUAL -> {
                parameters.put(parameterName, value);
                yield "%s <= :%s".formatted(fieldName, parameterName);
            }
            case LIKE -> {
                if (value instanceof String str) {
                    parameters.put(parameterName, ensureLikePattern(str.toLowerCase(Locale.ROOT)));
                    yield "LOWER(%s) LIKE :%s".formatted(fieldName, parameterName);
                }
                parameters.put(parameterName, value);
                yield "%s LIKE :%s".formatted(fieldName, parameterName);
            }
            case ILIKE -> {
                String lowered = value != null ? value.toString().toLowerCase(Locale.ROOT) : null;
                parameters.put(parameterName, ensureLikePattern(lowered));
                yield "LOWER(%s) LIKE :%s".formatted(fieldName, parameterName);
            }
            case IN, NOT_IN -> buildCollectionPredicate(operator, fieldName, parameterName, value, condition, parameters);
            case BETWEEN -> buildBetweenPredicate(fieldName, parameterName, condition, parameters, paramSequence);
            case EXISTS -> "%s IS NOT NULL".formatted(fieldName);
            case NOT_EXISTS -> "%s IS NULL".formatted(fieldName);
        };
    }

    private String ensureLikePattern(String raw) {
        if (raw == null) {
            return raw;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return raw;
        }
        // If the caller already specified LIKE wildcards, keep it as-is.
        if (text.indexOf('%') >= 0 || text.indexOf('_') >= 0) {
            return text;
        }
        // Default to "contains" semantics for LIKE filters coming from NL queries.
        return "%" + text + "%";
    }

    private String buildCollectionPredicate(FilterOperator operator,
                                            String field,
                                            String parameterName,
                                            Object value,
                                            FilterCondition condition,
                                            Map<String, Object> parameters) {
        if (value instanceof Iterable<?> iterable) {
            List<Object> values = new ArrayList<>();
            iterable.forEach(item -> values.add(coerceParameterValue(item, condition)));
            parameters.put(parameterName, values);
        } else if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> values = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                Object element = Array.get(value, i);
                values.add(coerceParameterValue(element, condition));
            }
            parameters.put(parameterName, values);
        } else {
            parameters.put(parameterName, List.of(coerceParameterValue(value, condition)));
        }
        String clause = "%s %s :%s".formatted(field, operator == FilterOperator.IN ? "IN" : "NOT IN", parameterName);
        return clause;
    }

    private String buildBetweenPredicate(String field,
                                         String parameterName,
                                         FilterCondition condition,
                                         Map<String, Object> parameters,
                                         AtomicInteger paramSequence) {
        String secondParameter = "p" + paramSequence.incrementAndGet();
        parameters.put(parameterName, coerceParameterValue(condition.getValue(), condition));
        parameters.put(secondParameter, coerceParameterValue(condition.getSecondaryValue(), condition));
        return "%s BETWEEN :%s AND :%s".formatted(field, parameterName, secondParameter);
    }

    private String resolveFieldName(FilterCondition condition, String defaultAlias, AliasRegistry aliases) {
        if (!StringUtils.hasText(condition.getField())) {
            return defaultAlias;
        }
        String raw = condition.getField().trim();
        if (raw.isEmpty()) {
            return defaultAlias;
        }
        if (!raw.contains(".")) {
            return defaultAlias + "." + raw;
        }

        String[] parts = raw.split("\\.", 2);
        if (parts.length != 2) {
            return defaultAlias + "." + raw;
        }

        String prefix = parts[0].trim();
        String suffix = parts[1].trim();
        if (suffix.isEmpty()) {
            return defaultAlias;
        }

        String resolvedAlias = StringUtils.hasText(prefix) ? aliases.aliasFor(prefix) : null;
        if (StringUtils.hasText(resolvedAlias)) {
            return resolvedAlias + "." + suffix;
        }
        if (aliases.isKnownAlias(prefix)) {
            return raw;
        }
        return defaultAlias + "." + raw;
    }

    private Optional<String> resolveFieldReference(Object value, AliasRegistry aliases) {
        if (!(value instanceof String str)) {
            return Optional.empty();
        }
        String trimmed = str.trim();
        if (!trimmed.contains(".")) {
            return Optional.empty();
        }
        String[] parts = trimmed.split("\\.", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }
        String refAlias = aliases.aliasFor(parts[0]);
        if (!StringUtils.hasText(refAlias)) {
            return Optional.empty();
        }
        return Optional.of(refAlias + "." + parts[1]);
    }

    private Object coerceParameterValue(Object value) {
        if (!(value instanceof String str)) {
            return value;
        }
        String trimmed = str.trim();
        if (trimmed.isEmpty()) {
            return value;
        }
        try {
            return LocalDateTime.parse(trimmed);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException ignored) {
        }
        return value;
    }

    private Object coerceParameterValue(Object value, FilterCondition condition) {
        Object coerced = coerceParameterValue(value);
        if (coerced == null || condition == null) {
            return coerced;
        }

        Optional<Class<?>> expectedJavaType = resolveExpectedJavaType(condition);
        if (expectedJavaType.isEmpty()) {
            return coerced;
        }
        Class<?> expectedType = expectedJavaType.get();

        if (expectedType.isEnum()) {
            if (coerced instanceof String str) {
                String constant = str.trim();
                if (constant.isEmpty()) {
                    return coerced;
                }
                try {
                    return Enum.valueOf(expectedType.asSubclass(Enum.class), constant.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    return coerced;
                }
            }
            return coerced;
        }

        if (expectedType == BigDecimal.class) {
            if (coerced instanceof BigDecimal) {
                return coerced;
            }
            if (coerced instanceof Number number) {
                return new BigDecimal(number.toString());
            }
            if (coerced instanceof String str) {
                try {
                    return new BigDecimal(str.trim());
                } catch (NumberFormatException ignored) {
                    return coerced;
                }
            }
            return coerced;
        }

        if (expectedType == LocalDateTime.class && coerced instanceof LocalDate date) {
            return date.atStartOfDay();
        }
        if (expectedType == LocalDate.class && coerced instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }

        if (expectedType == Integer.class || expectedType == int.class) {
            if (coerced instanceof Number number) {
                return number.intValue();
            }
            if (coerced instanceof String str) {
                try {
                    return Integer.parseInt(str.trim());
                } catch (NumberFormatException ignored) {
                    return coerced;
                }
            }
            return coerced;
        }

        if (expectedType == Long.class || expectedType == long.class) {
            if (coerced instanceof Number number) {
                return number.longValue();
            }
            if (coerced instanceof String str) {
                try {
                    return Long.parseLong(str.trim());
                } catch (NumberFormatException ignored) {
                    return coerced;
                }
            }
            return coerced;
        }

        if (expectedType == Double.class || expectedType == double.class) {
            if (coerced instanceof Number number) {
                return number.doubleValue();
            }
            if (coerced instanceof String str) {
                try {
                    return Double.parseDouble(str.trim());
                } catch (NumberFormatException ignored) {
                    return coerced;
                }
            }
            return coerced;
        }

        if (expectedType == Boolean.class || expectedType == boolean.class) {
            if (coerced instanceof Boolean) {
                return coerced;
            }
            if (coerced instanceof String str) {
                String normalized = str.trim().toLowerCase(Locale.ROOT);
                if ("true".equals(normalized) || "false".equals(normalized)) {
                    return Boolean.parseBoolean(normalized);
                }
            }
            return coerced;
        }

        return coerced;
    }

    private Optional<Class<?>> resolveExpectedJavaType(FilterCondition condition) {
        String entityType = condition.getEntityType();
        String field = condition.getField();

        String resolvedEntityType = entityType;
        if (!StringUtils.hasText(resolvedEntityType) && StringUtils.hasText(field) && field.contains(".")) {
            resolvedEntityType = field.substring(0, field.indexOf('.'));
        }
        if (!StringUtils.hasText(resolvedEntityType)) {
            return Optional.empty();
        }

        Class<?> entityClass;
        try {
            entityClass = relationshipMapper.getEntityClass(resolvedEntityType);
        } catch (Exception ignored) {
            return Optional.empty();
        }

        String propertyPath = field;
        if (StringUtils.hasText(propertyPath) && propertyPath.contains(".")) {
            String prefix = propertyPath.substring(0, propertyPath.indexOf('.'));
            if (prefix.equalsIgnoreCase(resolvedEntityType)) {
                propertyPath = propertyPath.substring(prefix.length() + 1);
            }
        }

        if (!StringUtils.hasText(propertyPath)) {
            return Optional.empty();
        }

        String cacheKey = resolvedEntityType.trim().toLowerCase(Locale.ROOT) + "|" + propertyPath.trim();
        Class<?> cached = expectedTypeCache.get(cacheKey);
        if (cached != null) {
            return cached == NO_TYPE ? Optional.empty() : Optional.of(cached);
        }

        Optional<Class<?>> resolved = resolvePropertyType(entityClass, propertyPath);
        expectedTypeCache.put(cacheKey, resolved.orElse(NO_TYPE));
        return resolved;
    }

    private Optional<Class<?>> resolvePropertyType(Class<?> rootType, String propertyPath) {
        String[] segments = propertyPath.split("\\.");
        Class<?> currentType = rootType;
        for (String segment : segments) {
            if (!StringUtils.hasText(segment) || currentType == null) {
                return Optional.empty();
            }
            Optional<Class<?>> nextType = resolveSinglePropertyType(currentType, segment.trim());
            if (nextType.isEmpty()) {
                return Optional.empty();
            }
            currentType = nextType.get();
        }
        return Optional.ofNullable(currentType);
    }

    private Optional<Class<?>> resolveSinglePropertyType(Class<?> type, String property) {
        Optional<Class<?>> fieldType = findFieldType(type, property);
        if (fieldType.isPresent()) {
            return fieldType;
        }
        return findGetter(type, property).map(Method::getReturnType);
    }

    private Optional<Class<?>> findFieldType(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(name);
                return Optional.of(field.getType());
            } catch (NoSuchFieldException ignored) {
            }
            current = current.getSuperclass();
        }
        return Optional.empty();
    }

    private Optional<Method> findGetter(Class<?> type, String property) {
        String capitalized = property.substring(0, 1).toUpperCase(Locale.ROOT) + property.substring(1);
        for (String candidate : List.of("get" + capitalized, "is" + capitalized)) {
            try {
                return Optional.of(type.getMethod(candidate));
            } catch (NoSuchMethodException ignored) {
            }
        }
        return Optional.empty();
    }

    private static class AliasRegistry {
        private final Map<String, String> aliasByEntity = new LinkedHashMap<>();
        private final Map<String, Integer> aliasCounters = new LinkedHashMap<>();

        String register(String entityType) {
            return aliasByEntity.computeIfAbsent(normalize(entityType), key -> {
                int index = aliasCounters.merge(key, 1, Integer::sum);
                return (key.length() > 2 ? key.substring(0, 2) : key) + index;
            });
        }

        String register(String entityType, String alias) {
            aliasByEntity.put(normalize(entityType), alias);
            return alias;
        }

        String aliasFor(String entityType) {
            return aliasByEntity.get(normalize(entityType));
        }

        boolean isKnownAlias(String alias) {
            if (!StringUtils.hasText(alias)) {
                return false;
            }
            String trimmed = alias.trim();
            return aliasByEntity.values().stream().anyMatch(value -> value.equals(trimmed));
        }

        private String normalize(String entityType) {
            if (!StringUtils.hasText(entityType)) {
                return "entity";
            }
            return entityType.trim().toLowerCase(Locale.ROOT);
        }
    }
}
