package ai.fabric.util;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared portable exact-match metadata filter rules for vector providers.
 */
public final class VectorMetadataFilterSupport {

    public static final String IMPOSSIBLE_FILTER_FIELD = "__ai_fabric_unsupported_metadata_filter__";
    public static final String IMPOSSIBLE_FILTER_VALUE = "__ai_fabric_no_match__";

    private VectorMetadataFilterSupport() {
    }

    public static ValidationResult validatePortableEquals(Map<String, Object> filters) {
        return validateEquals(filters, false);
    }

    public static ValidationResult validateEquals(Map<String, Object> filters, boolean allowDecimalNumbers) {
        if (filters == null || filters.isEmpty()) {
            return new ValidationResult(List.of(), List.of());
        }

        List<FilterTerm> terms = new ArrayList<>();
        List<RejectedFilter> rejected = new ArrayList<>();
        filters.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                rejected.add(new RejectedFilter(key, "blank key"));
                return;
            }
            FilterTerm term = toFilterTerm(key, value, allowDecimalNumbers);
            if (term == null) {
                rejected.add(new RejectedFilter(key, value == null ? "null value" : "unsupported value type"));
            } else {
                terms.add(term);
            }
        });

        return new ValidationResult(List.copyOf(terms), List.copyOf(rejected));
    }

    public static boolean matchesPortableEquals(Map<String, Object> metadata, Map<String, Object> filters) {
        ValidationResult validation = validatePortableEquals(filters);
        if (validation.hasRejectedFilters()) {
            return false;
        }
        if (validation.isEmpty()) {
            return true;
        }
        Map<String, Object> candidate = metadata == null ? Collections.emptyMap() : metadata;
        for (FilterTerm term : validation.terms()) {
            if (!term.matches(candidate.get(term.key()))) {
                return false;
            }
        }
        return true;
    }

    public static Map<String, Object> portableEqualsAsMap(Map<String, Object> filters) {
        ValidationResult validation = validatePortableEquals(filters);
        if (validation.hasRejectedFilters()) {
            return Map.of(IMPOSSIBLE_FILTER_FIELD, IMPOSSIBLE_FILTER_VALUE);
        }
        if (validation.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        validation.terms().forEach(term -> result.put(term.key(), term.value()));
        return Collections.unmodifiableMap(result);
    }

    private static FilterTerm toFilterTerm(String key, Object value, boolean allowDecimalNumbers) {
        if (value instanceof String string) {
            return new FilterTerm(key, string, ValueKind.STRING);
        }
        if (value instanceof Boolean bool) {
            return new FilterTerm(key, bool, ValueKind.BOOLEAN);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return new FilterTerm(key, ((Number) value).longValue(), ValueKind.INTEGRAL_NUMBER);
        }
        if (value instanceof BigInteger bigInteger && bigInteger.bitLength() < Long.SIZE) {
            return new FilterTerm(key, bigInteger.longValue(), ValueKind.INTEGRAL_NUMBER);
        }
        if (allowDecimalNumbers && value instanceof Number number) {
            double normalized = number.doubleValue();
            if (Double.isFinite(normalized)) {
                return new FilterTerm(key, normalized, ValueKind.DECIMAL_NUMBER);
            }
        }
        return null;
    }

    public enum ValueKind {
        STRING,
        BOOLEAN,
        INTEGRAL_NUMBER,
        DECIMAL_NUMBER
    }

    public record FilterTerm(String key, Object value, ValueKind kind) {
        public boolean matches(Object actual) {
            if (actual == null) {
                return false;
            }
            return switch (kind) {
                case STRING -> String.valueOf(value).equals(String.valueOf(actual));
                case BOOLEAN -> actual instanceof Boolean actualBool
                    ? value.equals(actualBool)
                    : String.valueOf(value).equalsIgnoreCase(String.valueOf(actual));
                case INTEGRAL_NUMBER -> matchesLong(actual, (Long) value);
                case DECIMAL_NUMBER -> matchesDouble(actual, (Double) value);
            };
        }

        private static boolean matchesLong(Object actual, long expected) {
            if (actual instanceof Byte || actual instanceof Short || actual instanceof Integer || actual instanceof Long) {
                return ((Number) actual).longValue() == expected;
            }
            if (actual instanceof BigInteger bigInteger && bigInteger.bitLength() < Long.SIZE) {
                return bigInteger.longValue() == expected;
            }
            try {
                return Long.parseLong(String.valueOf(actual)) == expected;
            } catch (NumberFormatException ex) {
                return false;
            }
        }

        private static boolean matchesDouble(Object actual, double expected) {
            if (actual instanceof Number number) {
                return Double.compare(number.doubleValue(), expected) == 0;
            }
            try {
                return Double.compare(Double.parseDouble(String.valueOf(actual)), expected) == 0;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
    }

    public record RejectedFilter(String key, String reason) {
    }

    public record ValidationResult(List<FilterTerm> terms, List<RejectedFilter> rejectedFilters) {
        public boolean isEmpty() {
            return terms.isEmpty() && rejectedFilters.isEmpty();
        }

        public boolean hasRejectedFilters() {
            return !rejectedFilters.isEmpty();
        }
    }
}
