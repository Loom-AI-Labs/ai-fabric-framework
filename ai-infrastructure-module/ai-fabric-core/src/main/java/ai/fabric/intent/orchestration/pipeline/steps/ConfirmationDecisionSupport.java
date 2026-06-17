package ai.fabric.intent.orchestration.pipeline.steps;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * Pure helpers for confirmation-loop parameter matching and LLM decision parsing.
 */
final class ConfirmationDecisionSupport {

    private ConfirmationDecisionSupport() {
    }

    enum ConfirmationResolutionDecision {
        POSITIVE,
        NEGATIVE,
        UNKNOWN
    }

    static boolean actionParamsEquivalentOrSubset(Map<String, Object> currentParams, Map<String, Object> pendingParams) {
        if (currentParams == null || currentParams.isEmpty()) {
            return true;
        }
        if (pendingParams == null || pendingParams.isEmpty()) {
            return currentParams.isEmpty();
        }
        if (pendingParams.equals(currentParams)) {
            return true;
        }
        // Subset check: allow missing keys in currentParams as long as provided keys match.
        for (Map.Entry<String, Object> entry : currentParams.entrySet()) {
            if (entry == null) {
                continue;
            }
            String key = entry.getKey();
            if (!StringUtils.hasText(key)) {
                continue;
            }
            if (!pendingParams.containsKey(key)) {
                return false;
            }
            Object pendingValue = pendingParams.get(key);
            if (!valuesEquivalentOrSubset(entry.getValue(), pendingValue)) {
                return false;
            }
        }
        return true;
    }

    static boolean valuesEquivalentOrSubset(Object currentValue, Object pendingValue) {
        if (currentValue == null) {
            return true;
        }
        if (pendingValue == null) {
            return false;
        }
        if (java.util.Objects.equals(currentValue, pendingValue)) {
            return true;
        }

        if (currentValue instanceof String currentText && pendingValue instanceof String pendingText) {
            return currentText.trim().equalsIgnoreCase(pendingText.trim());
        }

        if (currentValue instanceof Number currentNumber && pendingValue instanceof Number pendingNumber) {
            return Double.compare(currentNumber.doubleValue(), pendingNumber.doubleValue()) == 0;
        }

        if (currentValue instanceof Map<?, ?> currentMap && pendingValue instanceof Map<?, ?> pendingMap) {
            return mapEquivalentOrSubset(currentMap, pendingMap);
        }

        if (currentValue instanceof List<?> currentList && pendingValue instanceof List<?> pendingList) {
            return listEquivalentOrSubset(currentList, pendingList);
        }

        return false;
    }

    static ConfirmationResolutionDecision parseConfirmationDecision(String content, ObjectMapper mapper) {
        if (!StringUtils.hasText(content)) {
            return ConfirmationResolutionDecision.UNKNOWN;
        }

        ObjectMapper effectiveMapper = mapper != null ? mapper : new ObjectMapper();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = effectiveMapper.readValue(content, Map.class);
            Object value = map != null ? map.get("decision") : null;
            if (!(value instanceof String text) || !StringUtils.hasText(text)) {
                return ConfirmationResolutionDecision.UNKNOWN;
            }
            String normalized = text.trim().toUpperCase(java.util.Locale.ROOT);
            return switch (normalized) {
                case "POSITIVE", "CONFIRM", "CONFIRMED", "YES", "APPROVE", "APPROVED" ->
                    ConfirmationResolutionDecision.POSITIVE;
                case "NEGATIVE", "REJECT", "REJECTED", "NO", "CANCEL", "CANCELLED" ->
                    ConfirmationResolutionDecision.NEGATIVE;
                default -> ConfirmationResolutionDecision.UNKNOWN;
            };
        } catch (Exception ignored) {
            return ConfirmationResolutionDecision.UNKNOWN;
        }
    }

    static double parseConfirmationConfidence(String content, ObjectMapper mapper) {
        if (!StringUtils.hasText(content)) {
            return 0.0d;
        }
        ObjectMapper effectiveMapper = mapper != null ? mapper : new ObjectMapper();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = effectiveMapper.readValue(content, Map.class);
            Object value = map != null ? map.get("confidence") : null;
            if (value instanceof Number number) {
                double raw = number.doubleValue();
                if (Double.isNaN(raw) || Double.isInfinite(raw)) {
                    return 0.0d;
                }
                return Math.max(0.0d, Math.min(1.0d, raw));
            }
        } catch (Exception ignored) {
            // ignore
        }
        return 0.0d;
    }

    private static boolean mapEquivalentOrSubset(Map<?, ?> currentMap, Map<?, ?> pendingMap) {
        if (currentMap == null || currentMap.isEmpty()) {
            return true;
        }
        if (pendingMap == null || pendingMap.isEmpty()) {
            return currentMap == null || currentMap.isEmpty();
        }
        for (Map.Entry<?, ?> entry : currentMap.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            if (!pendingMap.containsKey(entry.getKey())) {
                return false;
            }
            if (!valuesEquivalentOrSubset(entry.getValue(), pendingMap.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean listEquivalentOrSubset(List<?> currentList, List<?> pendingList) {
        if (currentList == null || currentList.isEmpty()) {
            return true;
        }
        if (pendingList == null || pendingList.isEmpty()) {
            return false;
        }

        // Subset: each current element must appear in pending list (order-insensitive).
        for (Object currentElement : currentList) {
            boolean found = false;
            for (Object pendingElement : pendingList) {
                if (valuesEquivalentOrSubset(currentElement, pendingElement)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
