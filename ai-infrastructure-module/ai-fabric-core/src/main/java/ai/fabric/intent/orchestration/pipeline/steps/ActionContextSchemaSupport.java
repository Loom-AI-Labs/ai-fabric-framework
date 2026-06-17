package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static ai.fabric.intent.orchestration.pipeline.steps.ActionContextLookupSupport.valueByCandidateKeys;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionValueSupport.numericValue;

@Slf4j
final class ActionContextSchemaSupport {

    private ActionContextSchemaSupport() {
    }

    static boolean shouldResolveConfiguredActionParam(String parameter,
                                                      AIActionParamSchema schema,
                                                      Object existingValue) {
        if (schema == null || schema.getResolveFrom() == null || schema.getResolveFrom().isEmpty()) {
            return false;
        }
        if (!hasMeaningfulActionParamValue(existingValue)) {
            return true;
        }
        return !actionParamValueSatisfiesSchema(parameter, existingValue, schema);
    }

    static boolean actionParamValueSatisfiesSchema(String parameter,
                                                   Object value,
                                                   AIActionParamSchema schema) {
        if (schema == null || !hasMeaningfulActionParamValue(value)) {
            return false;
        }
        if (schema.getType() != null) {
            boolean typeMatches = switch (schema.getType()) {
                case STRING -> value instanceof CharSequence || !(value instanceof Map<?, ?>) && !(value instanceof List<?>);
                case INTEGER -> parseLong(value) != null;
                case NUMBER -> parseDouble(value) != null;
                case BOOLEAN -> value instanceof Boolean
                    || "true".equalsIgnoreCase(value.toString().trim())
                    || "false".equalsIgnoreCase(value.toString().trim());
                case ARRAY -> value instanceof List<?>;
                case OBJECT -> value instanceof Map<?, ?>;
                case UNKNOWN -> true;
            };
            if (!typeMatches) {
                return false;
            }
        }
        if (schema.getType() == AIActionParamType.ARRAY
            && schema.getItems() != null
            && value instanceof List<?> list) {
            for (Object item : list) {
                if (!actionParamValueSatisfiesSchema(parameter, item, schema.getItems())) {
                    return false;
                }
            }
        }
        if (schema.getType() == AIActionParamType.OBJECT
            && value instanceof Map<?, ?> map
            && schema.getProperties() != null
            && !schema.getProperties().isEmpty()) {
            for (Map.Entry<String, AIActionParamSchema> property : schema.getProperties().entrySet()) {
                if (property == null || !StringUtils.hasText(property.getKey()) || property.getValue() == null) {
                    continue;
                }
                Object propertyValue = valueByCandidateKeys(map, List.of(property.getKey().trim()));
                if (!hasMeaningfulActionParamValue(propertyValue)) {
                    if (Boolean.TRUE.equals(property.getValue().getRequired())
                        || (schema.getRequiredProperties() != null
                            && schema.getRequiredProperties().stream()
                                .filter(StringUtils::hasText)
                                .anyMatch(required -> required.trim().equalsIgnoreCase(property.getKey().trim())))) {
                        return false;
                    }
                    continue;
                }
                if (!actionParamValueSatisfiesSchema(property.getKey(), propertyValue, property.getValue())) {
                    return false;
                }
            }
        }
        if (StringUtils.hasText(schema.getPattern())) {
            try {
                if (!Pattern.compile(schema.getPattern()).matcher(value.toString().trim()).matches()) {
                    return false;
                }
            } catch (PatternSyntaxException ex) {
                log.debug("Ignoring invalid resolver parameter pattern for '{}': {}", parameter, ex.getMessage());
                return false;
            }
        }
        if (schema.getAllowedValues() != null && !schema.getAllowedValues().isEmpty()) {
            String normalized = value.toString().trim();
            boolean allowed = schema.getAllowedValues().stream()
                .filter(StringUtils::hasText)
                .anyMatch(allowedValue -> allowedValue.trim().equalsIgnoreCase(normalized));
            if (!allowed) {
                return false;
            }
        }
        if (schema.getMin() != null || schema.getMax() != null) {
            Double numeric = numericValue(value);
            if (numeric == null) {
                return false;
            }
            if (schema.getMin() != null && numeric < schema.getMin()) {
                return false;
            }
            if (schema.getMax() != null && numeric > schema.getMax()) {
                return false;
            }
        }
        if (schema.getType() == AIActionParamType.OBJECT
            && value instanceof Map<?, ?> map
            && schema.getRequiredProperties() != null
            && !schema.getRequiredProperties().isEmpty()) {
            for (String requiredProperty : schema.getRequiredProperties()) {
                if (!StringUtils.hasText(requiredProperty)
                    || !hasMeaningfulActionParamValue(valueByCandidateKeys(map, List.of(requiredProperty.trim())))) {
                    return false;
                }
            }
        }
        return true;
    }

    static Object normalizeResolvedActionParamValue(Object value) {
        if (value instanceof String text) {
            return text.trim();
        }
        return value;
    }

    static Object normalizeResolvedActionParamValue(Object value, AIActionParamSchema schema) {
        Object normalized = normalizeResolvedActionParamValue(value);
        if (schema == null
            || schema.getType() == null
            || !hasMeaningfulActionParamValue(normalized)) {
            return normalized;
        }
        if (schema.getType() == AIActionParamType.ARRAY
            && schema.getItems() != null
            && schema.getItems().getType() == AIActionParamType.OBJECT) {
            List<?> rawItems = normalized instanceof List<?> list ? list : List.of(normalized);
            List<Object> items = new ArrayList<>();
            for (Object rawItem : rawItems) {
                Map<String, Object> item = ActionBatchSupport.normalizeBatchItemAgainstSchema(rawItem, schema.getItems());
                if (item != null && !item.isEmpty()) {
                    items.add(item);
                }
            }
            return items.isEmpty() ? normalized : Collections.unmodifiableList(items);
        }
        if (schema.getType() == AIActionParamType.OBJECT) {
            Map<String, Object> item = ActionBatchSupport.normalizeBatchItemAgainstSchema(normalized, schema);
            return item != null && !item.isEmpty() ? item : normalized;
        }
        return normalized;
    }

    static boolean hasMeaningfulActionParamValue(Object value) {
        return ActionBatchSupport.hasMeaningfulBatchValue(value);
    }

    static Long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof CharSequence text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text.toString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static Double parseDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof CharSequence text && StringUtils.hasText(text)) {
            try {
                return Double.parseDouble(text.toString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
