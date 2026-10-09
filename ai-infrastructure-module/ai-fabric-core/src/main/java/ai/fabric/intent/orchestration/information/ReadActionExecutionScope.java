package ai.fabric.intent.orchestration.information;

import org.springframework.util.StringUtils;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Request-local registry that prevents the same read action and parameter set from executing more
 * than once while compound evidence obligations are collected.
 */
public final class ReadActionExecutionScope {

    private final Set<String> claimedExecutionKeys = new LinkedHashSet<>();
    private final Map<String, String> executionIdsByKey = new LinkedHashMap<>();

    public synchronized Claim claim(String actionName, Map<String, Object> params) {
        String key = executionKey(actionName, params);
        if (claimedExecutionKeys.add(key)) {
            return new Claim(true, key, null);
        }
        return new Claim(false, key, executionIdsByKey.get(key));
    }

    public synchronized void recordExecution(String actionName,
                                             Map<String, Object> params,
                                             String actionExecutionId) {
        String key = executionKey(actionName, params);
        claimedExecutionKeys.add(key);
        if (StringUtils.hasText(actionExecutionId)) {
            executionIdsByKey.putIfAbsent(key, actionExecutionId.trim());
        }
    }

    public synchronized void recordExecution(String executionKey, String actionExecutionId) {
        if (!StringUtils.hasText(executionKey)) {
            return;
        }
        String normalizedKey = executionKey.trim();
        claimedExecutionKeys.add(normalizedKey);
        if (StringUtils.hasText(actionExecutionId)) {
            executionIdsByKey.putIfAbsent(normalizedKey, actionExecutionId.trim());
        }
    }

    static String executionKey(String actionName, Map<String, Object> params) {
        String normalizedAction = StringUtils.hasText(actionName)
            ? actionName.trim().toLowerCase(java.util.Locale.ROOT)
            : "";
        return normalizedAction + "::" + canonicalValue(params != null ? params : Map.of());
    }

    private static String canonicalValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence text) {
            String string = text.toString();
            return "s" + string.length() + ":" + string;
        }
        if (value instanceof Number number) {
            try {
                return "n:" + new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ignored) {
                return "n:" + number;
            }
        }
        if (value instanceof Boolean bool) {
            return "b:" + bool;
        }
        if (value instanceof Enum<?> enumValue) {
            return "e:" + enumValue.getDeclaringClass().getName() + ":" + enumValue.name();
        }
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<String, Object>> entries = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    entries.add(new AbstractMap.SimpleImmutableEntry<>(
                        String.valueOf(entry.getKey()),
                        entry.getValue()
                    ));
                }
            }
            entries.sort(Comparator.comparing(Map.Entry::getKey));
            StringBuilder out = new StringBuilder("m{");
            for (Map.Entry<String, Object> entry : entries) {
                out.append(canonicalValue(entry.getKey()))
                    .append('=')
                    .append(canonicalValue(entry.getValue()))
                    .append(';');
            }
            return out.append('}').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder out = new StringBuilder("l[");
            for (Object item : iterable) {
                out.append(canonicalValue(item)).append(';');
            }
            return out.append(']').toString();
        }
        if (value.getClass().isArray()) {
            StringBuilder out = new StringBuilder("a[");
            for (int index = 0; index < Array.getLength(value); index++) {
                out.append(canonicalValue(Array.get(value, index))).append(';');
            }
            return out.append(']').toString();
        }
        return "o:" + value.getClass().getName() + ":" + value;
    }

    public record Claim(boolean acquired, String executionKey, String existingExecutionId) {
    }
}
