package ai.fabric.intent.actiondraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * Bounded user-supplied values for continuing an existing action draft.
 *
 * <p>This input is not trusted application context. It only avoids reparsing a
 * typed form through an LLM; normal action schema, provenance, policy,
 * confirmation, and execution checks still apply.</p>
 */
public record ActionDraftSubmission(
    String action,
    Map<String, Object> parameters
) {

    private static final int MAX_ACTION_CHARACTERS = 160;
    private static final int MAX_PARAMETER_COUNT = 32;
    private static final int MAX_PARAMETER_NAME_CHARACTERS = 96;
    private static final int MAX_STRING_CHARACTERS = 4_000;
    private static final int MAX_COLLECTION_SIZE = 64;
    private static final int MAX_NESTING_DEPTH = 4;

    public ActionDraftSubmission {
        action = normalizeAction(action);
        parameters = immutableParameters(parameters);
    }

    @Override
    public String toString() {
        return "ActionDraftSubmission[action=" + action
            + ", parameterNames=" + parameters.keySet() + "]";
    }

    private static String normalizeAction(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("action must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_ACTION_CHARACTERS) {
            throw new IllegalArgumentException("action exceeds the maximum length");
        }
        return normalized;
    }

    private static Map<String, Object> immutableParameters(
        Map<String, Object> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        if (source.size() > MAX_PARAMETER_COUNT) {
            throw new IllegalArgumentException("too many action draft parameters");
        }
        Map<String, Object> copied = copyMap(source, 0);
        return copied.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(copied);
    }

    private static Map<String, Object> copyMap(
        Map<?, ?> source,
        int depth
    ) {
        requireDepth(depth);
        if (source.size() > MAX_COLLECTION_SIZE) {
            throw new IllegalArgumentException("action draft object is too large");
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        source.forEach((rawKey, rawValue) -> {
            if (rawKey == null) {
                throw new IllegalArgumentException("action draft parameter name must not be null");
            }
            String key = String.valueOf(rawKey).trim();
            if (!StringUtils.hasText(key)
                || key.length() > MAX_PARAMETER_NAME_CHARACTERS) {
                throw new IllegalArgumentException("action draft parameter name is invalid");
            }
            if (rawValue != null) {
                copied.put(key, copyValue(rawValue, depth + 1));
            }
        });
        return copied;
    }

    private static Object copyValue(Object value, int depth) {
        requireDepth(depth);
        if (value instanceof String stringValue) {
            if (stringValue.length() > MAX_STRING_CHARACTERS) {
                throw new IllegalArgumentException("action draft string value is too long");
            }
            return stringValue;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> mapValue) {
            return Collections.unmodifiableMap(copyMap(mapValue, depth));
        }
        if (value instanceof Iterable<?> iterableValue) {
            List<Object> copied = new ArrayList<>();
            for (Object item : iterableValue) {
                if (copied.size() >= MAX_COLLECTION_SIZE) {
                    throw new IllegalArgumentException("action draft list is too large");
                }
                if (item != null) {
                    copied.add(copyValue(item, depth + 1));
                }
            }
            return List.copyOf(copied);
        }
        throw new IllegalArgumentException(
            "unsupported action draft parameter value type"
        );
    }

    private static void requireDepth(int depth) {
        if (depth > MAX_NESTING_DEPTH) {
            throw new IllegalArgumentException("action draft parameters are nested too deeply");
        }
    }
}
