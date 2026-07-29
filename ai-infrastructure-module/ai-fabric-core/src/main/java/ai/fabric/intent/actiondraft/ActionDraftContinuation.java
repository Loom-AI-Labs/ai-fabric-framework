package ai.fabric.intent.actiondraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * Internal, user-safe continuation state for one incomplete action.
 *
 * <p>Only user-visible parameters belong here. Trusted runtime parameters must be
 * resolved again by the application when the completed action is handled.</p>
 */
public record ActionDraftContinuation(
    String action,
    Map<String, Object> collectedParams,
    List<String> missingParameters
) {

    public ActionDraftContinuation {
        if (!StringUtils.hasText(action)) {
            throw new IllegalArgumentException("action is required");
        }
        action = action.trim();
        collectedParams = freezeMap(collectedParams);
        missingParameters = missingParameters == null
            ? List.of()
            : missingParameters.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static Map<String, Object> freezeMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
                copy.put(key.trim(), freezeValue(value));
            }
        });
        return copy.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(copy);
    }

    private static Object freezeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> {
                if (key != null && nestedValue != null) {
                    copy.put(String.valueOf(key), freezeValue(nestedValue));
                }
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) {
                    copy.add(freezeValue(item));
                }
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
