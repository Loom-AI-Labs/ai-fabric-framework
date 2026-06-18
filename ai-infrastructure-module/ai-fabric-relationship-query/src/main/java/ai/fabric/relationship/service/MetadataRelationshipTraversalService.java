package ai.fabric.relationship.service;

import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.dto.VectorScanRequest;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.relationship.dto.FilterCondition;
import ai.fabric.relationship.dto.FilterOperator;
import ai.fabric.relationship.dto.JpqlQuery;
import ai.fabric.relationship.dto.RelationshipQueryPlan;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Fallback traversal strategy that inspects vector metadata when direct JPA joins are insufficient.
 *
 * <p>This implementation relies on {@link VectorDatabaseService} as the single source of truth.</p>
 */
public class MetadataRelationshipTraversalService implements RelationshipTraversalService {

    private final VectorDatabaseService vectorDatabaseService;

    public MetadataRelationshipTraversalService(VectorDatabaseService vectorDatabaseService) {
        this.vectorDatabaseService = Objects.requireNonNull(vectorDatabaseService);
    }

    @Override
    public TraversalMode getMode() {
        return TraversalMode.METADATA;
    }

    @Override
    public boolean supports(RelationshipQueryPlan plan) {
        return plan != null && StringUtils.hasText(plan.getPrimaryEntityType());
    }

    @Override
    public TraversalResult traverse(RelationshipQueryPlan plan, JpqlQuery query) {
        if (!supports(plan)) {
            return TraversalResult.empty();
        }

        String entityType = plan.getPrimaryEntityType();
        Integer limit = query != null ? query.getLimit() : null;
        if (limit != null && limit <= 0) {
            return TraversalResult.empty();
        }

        List<FilterCondition> filterConditions = mergeFilters(plan);
        if (filterConditions.isEmpty()) {
            return TraversalResult.ids(scanIds(entityType, limit));
        }

        List<String> matches = new ArrayList<>();
        String cursor = null;
        do {
            int pageLimit = pageLimit(limit, matches.size());
            if (pageLimit <= 0) {
                return TraversalResult.ids(matches);
            }
            VectorScanPage page = vectorDatabaseService.scan(VectorScanRequest.builder()
                .entityType(entityType)
                .cursor(cursor)
                .limit(pageLimit)
                .includeContent(false)
                .includeEmbedding(false)
                .includeMetadata(true)
                .build());

            List<VectorRecord> vectors = page != null ? page.getVectors() : null;
            if (vectors != null) {
                for (VectorRecord record : vectors) {
                    if (record == null || !StringUtils.hasText(record.getEntityId())) {
                        continue;
                    }
                    Map<String, Object> metadata = record.getMetadata();
                    if (matches(metadata, filterConditions)) {
                        matches.add(record.getEntityId());
                    }
                    if (limit != null && matches.size() >= limit) {
                        return TraversalResult.ids(matches);
                    }
                }
            }

            cursor = page != null ? page.getNextCursor() : null;
        } while (cursor != null);

        return TraversalResult.ids(matches);
    }

    private List<String> scanIds(String entityType, Integer limit) {
        if (!StringUtils.hasText(entityType)) {
            return Collections.emptyList();
        }
        if (limit != null && limit <= 0) {
            return Collections.emptyList();
        }

        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            int pageLimit = pageLimit(limit, ids.size());
            if (pageLimit <= 0) {
                return ids;
            }
            VectorScanPage page = vectorDatabaseService.scan(VectorScanRequest.builder()
                .entityType(entityType)
                .cursor(cursor)
                .limit(pageLimit)
                .includeContent(false)
                .includeEmbedding(false)
                .includeMetadata(false)
                .build());

            List<VectorRecord> vectors = page != null ? page.getVectors() : null;
            if (vectors != null) {
                for (VectorRecord record : vectors) {
                    if (record != null && StringUtils.hasText(record.getEntityId())) {
                        ids.add(record.getEntityId());
                    }
                    if (limit != null && ids.size() >= limit) {
                        return ids;
                    }
                }
            }

            cursor = page != null ? page.getNextCursor() : null;
        } while (cursor != null);

        return ids;
    }

    private int pageLimit(Integer requestedLimit, int alreadyMatched) {
        if (requestedLimit == null) {
            return 500;
        }
        return Math.min(Math.max(requestedLimit - alreadyMatched, 0), 500);
    }

    private List<FilterCondition> mergeFilters(RelationshipQueryPlan plan) {
        List<FilterCondition> merged = new ArrayList<>();
        if (!CollectionUtils.isEmpty(plan.getDirectFilters())) {
            plan.getDirectFilters().values().stream()
                .filter(Objects::nonNull)
                .forEach(merged::addAll);
        }
        if (!CollectionUtils.isEmpty(plan.getRelationshipFilters())) {
            plan.getRelationshipFilters().values().stream()
                .filter(Objects::nonNull)
                .forEach(merged::addAll);
        }
        if (!CollectionUtils.isEmpty(plan.getRelationshipPaths())) {
            plan.getRelationshipPaths().forEach(path -> {
                if (path != null && !CollectionUtils.isEmpty(path.getConditions())) {
                    merged.addAll(path.getConditions());
                }
            });
        }
        return merged;
    }

    private boolean matches(Map<String, Object> metadata, List<FilterCondition> filters) {
        for (FilterCondition condition : filters) {
            Object value = lookup(metadata, condition.getField()).orElse(null);
            if (!evaluateCondition(value, condition)) {
                return false;
            }
        }
        return true;
    }

    private Optional<Object> lookup(Map<String, Object> metadata, String field) {
        if (!StringUtils.hasText(field) || metadata == null) {
            return Optional.empty();
        }
        if (metadata.containsKey(field)) {
            return Optional.ofNullable(metadata.get(field));
        }
        int dot = field.lastIndexOf('.');
        if (dot >= 0) {
            String suffix = field.substring(dot + 1);
            if (metadata.containsKey(suffix)) {
                return Optional.ofNullable(metadata.get(suffix));
            }
            String condensed = field.replace(".", "");
            return Optional.ofNullable(metadata.get(condensed));
        }
        return Optional.empty();
    }

    private boolean evaluateCondition(Object candidate, FilterCondition condition) {
        FilterOperator operator = condition.getOperator() != null ? condition.getOperator() : FilterOperator.EQUALS;
        Object expected = condition.getValue();
        return switch (operator) {
            case EQUALS -> compareStrings(candidate, expected) == 0;
            case NOT_EQUALS -> compareStrings(candidate, expected) != 0;
            case GREATER_THAN -> compareNumbers(candidate, expected)
                .map(result -> result > 0)
                .orElse(false);
            case GREATER_THAN_OR_EQUAL -> compareNumbers(candidate, expected)
                .map(result -> result >= 0)
                .orElse(false);
            case LESS_THAN -> compareNumbers(candidate, expected)
                .map(result -> result < 0)
                .orElse(false);
            case LESS_THAN_OR_EQUAL -> compareNumbers(candidate, expected)
                .map(result -> result <= 0)
                .orElse(false);
            case LIKE, ILIKE -> {
                Optional<String> haystack = normalize(candidate);
                Optional<String> needle = normalize(expected);
                yield haystack.isPresent()
                    && needle.isPresent()
                    && haystack.get().contains(needle.get().replace("%", ""));
            }
            case IN -> containsValue(candidate, expected, true);
            case NOT_IN -> !containsValue(candidate, expected, true);
            case BETWEEN -> {
                Optional<Integer> first = compareNumbers(candidate, expected);
                Optional<Integer> second = compareNumbers(candidate, condition.getSecondaryValue());
                yield first.isPresent() && second.isPresent() && first.get() >= 0 && second.get() <= 0;
            }
            case EXISTS -> candidate != null;
            case NOT_EXISTS -> candidate == null;
        };
    }

    private int compareStrings(Object first, Object second) {
        Optional<String> left = normalize(first);
        Optional<String> right = normalize(second);
        if (left.isEmpty() || right.isEmpty()) {
            return left.isEmpty() && right.isEmpty() ? 0 : -1;
        }
        return left.get().compareTo(right.get());
    }

    private Optional<Integer> compareNumbers(Object first, Object second) {
        Optional<Double> a = parseDouble(first);
        Optional<Double> b = parseDouble(second);
        if (a.isEmpty() || b.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Double.compare(a.get(), b.get()));
    }

    private Optional<Double> parseDouble(Object value) {
        if (value instanceof Number number) {
            return Optional.of(number.doubleValue());
        }
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private boolean containsValue(Object candidate, Object expected, boolean normalize) {
        if (expected instanceof Iterable<?> iterable) {
            for (Object option : iterable) {
                if (normalize) {
                    if (normalizedEquals(candidate, option)) {
                        return true;
                    }
                } else if (Objects.equals(candidate, option)) {
                    return true;
                }
            }
            return false;
        }
        if (expected != null && expected.getClass().isArray()) {
            int length = Array.getLength(expected);
            for (int i = 0; i < length; i++) {
                Object option = Array.get(expected, i);
                if (normalizedEquals(candidate, option)) {
                    return true;
                }
            }
        }
        return normalizedEquals(candidate, expected);
    }

    private boolean normalizedEquals(Object candidate, Object expected) {
        return Objects.equals(normalize(candidate), normalize(expected));
    }

    private Optional<String> normalize(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        String text = String.valueOf(value);
        if (!StringUtils.hasText(text)) {
            return Optional.empty();
        }
        return Optional.of(text.toLowerCase(Locale.ROOT));
    }
}
