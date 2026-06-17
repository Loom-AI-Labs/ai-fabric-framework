package ai.fabric.intent.orchestration.pipeline.steps;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * Internal value helpers shared by action validation code.
 */
final class ActionValueSupport {

    private ActionValueSupport() {
    }

    static boolean hasMeaningfulJavaValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return StringUtils.hasText(text);
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(ActionValueSupport::hasMeaningfulJavaValue);
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(ActionValueSupport::hasMeaningfulJavaValue);
        }
        return true;
    }

    static Double numericValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null && StringUtils.hasText(value.toString())) {
            try {
                return Double.parseDouble(value.toString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
