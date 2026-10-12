package ai.fabric.intent.action;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Deterministic validation for values supplied to a registered action contract.
 */
public final class ActionParameterContractValidator {

    private static final int MAX_TOP_LEVEL_PARAMETERS = 32;
    private static final int MAX_OBJECT_PROPERTIES = 64;
    private static final int MAX_ARRAY_ITEMS = 100;
    private static final int MAX_STRING_LENGTH = 4096;
    private static final int MAX_NESTING_DEPTH = 8;

    private ActionParameterContractValidator() {
    }

    public static void validateExecutable(AIActionMetaData metadata, Map<String, Object> parameters) {
        validate(metadata, parameters, true);
    }

    public static void validateProvided(AIActionMetaData metadata, Map<String, Object> parameters) {
        validate(metadata, parameters, false);
    }

    private static void validate(AIActionMetaData metadata,
                                 Map<String, Object> parameters,
                                 boolean requireRequiredParameters) {
        if (metadata == null) {
            throw invalid("ACTION_NOT_FOUND", "Action is not registered.", null);
        }
        Map<String, Object> values = parameters == null ? Map.of() : parameters;
        Map<String, AIActionParamSchema> schemas = metadata.getParameterSchemas() == null
            ? Map.of()
            : metadata.getParameterSchemas();
        Set<String> required = metadata.getRequiredParameters() == null
            ? Set.of()
            : metadata.getRequiredParameters();

        if (values.size() > MAX_TOP_LEVEL_PARAMETERS) {
            throw invalid(
                "ACTION_PARAMETERS_TOO_LARGE",
                "Action parameters contain too many fields.",
                null
            );
        }
        if (requireRequiredParameters) {
            List<String> requiredWithoutSchema = required.stream()
                .filter(name -> !schemas.containsKey(name))
                .sorted()
                .toList();
            if (!requiredWithoutSchema.isEmpty()) {
                throw invalid(
                    "ACTION_PARAMETER_SCHEMA_INVALID",
                    "A required action parameter has no registered schema.",
                    requiredWithoutSchema.getFirst()
                );
            }
            List<String> missing = required.stream()
                .filter(name -> missing(values.get(name)))
                .sorted()
                .toList();
            if (!missing.isEmpty()) {
                throw invalid(
                    "ACTION_REQUIRED_PARAMETERS_MISSING",
                    "Required action parameters are missing: " + String.join(", ", missing),
                    missing.getFirst()
                );
            }
        }
        if (!schemas.isEmpty()) {
            List<String> unknown = values.keySet().stream()
                .filter(name -> !schemas.containsKey(name))
                .sorted()
                .toList();
            if (!unknown.isEmpty()) {
                throw invalid(
                    "ACTION_UNKNOWN_PARAMETERS",
                    "Action parameters contain unsupported fields.",
                    unknown.getFirst()
                );
            }
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            AIActionParamSchema schema = schemas.get(entry.getKey());
            if (schema != null) {
                validateValue(entry.getKey(), entry.getValue(), schema, 0);
            }
        }
    }

    private static void validateValue(String path,
                                      Object value,
                                      AIActionParamSchema schema,
                                      int depth) {
        if (depth > MAX_NESTING_DEPTH) {
            throw invalid(
                "ACTION_PARAMETERS_TOO_LARGE",
                "Action parameters exceed the maximum nesting depth.",
                path
            );
        }
        if (value == null) {
            if (Boolean.TRUE.equals(schema.getRequired())) {
                throw invalid(
                    "ACTION_PARAMETER_INVALID",
                    "Required action parameter is null: " + path,
                    path
                );
            }
            return;
        }
        AIActionParamType type = schema.getType() != null
            ? schema.getType()
            : AIActionParamType.UNKNOWN;
        switch (type) {
            case STRING -> validateString(path, value, schema);
            case INTEGER -> validateInteger(path, value, schema);
            case NUMBER -> validateNumber(path, value, schema);
            case BOOLEAN -> requireType(path, value, Boolean.class);
            case OBJECT -> validateObject(path, value, schema, depth);
            case ARRAY -> validateArray(path, value, schema, depth);
            case UNKNOWN -> validateUntypedValue(path, value, depth);
        }
    }

    private static void validateString(String path,
                                       Object value,
                                       AIActionParamSchema schema) {
        requireType(path, value, String.class);
        String text = (String) value;
        if (text.length() > MAX_STRING_LENGTH) {
            throw invalid(
                "ACTION_PARAMETERS_TOO_LARGE",
                "Action parameter text exceeds the maximum length: " + path,
                path
            );
        }
        if (schema.getAllowedValues() != null
            && !schema.getAllowedValues().isEmpty()
            && schema.getAllowedValues().stream()
                .noneMatch(allowed -> allowed.equalsIgnoreCase(text))) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action parameter has an unsupported value: " + path,
                path
            );
        }
        if (schema.getPattern() != null && !schema.getPattern().isBlank()) {
            try {
                if (!Pattern.compile(schema.getPattern()).matcher(text).matches()) {
                    throw invalid(
                        "ACTION_PARAMETER_INVALID",
                        "Action parameter does not match its contract: " + path,
                        path
                    );
                }
            } catch (PatternSyntaxException ex) {
                throw invalid(
                    "ACTION_PARAMETER_SCHEMA_INVALID",
                    "Registered action parameter pattern is invalid.",
                    path
                );
            }
        }
    }

    private static void validateInteger(String path,
                                        Object value,
                                        AIActionParamSchema schema) {
        if (!(value instanceof Number number)) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action parameter must be an integer: " + path,
                path
            );
        }
        BigDecimal decimal = decimal(path, number);
        if (decimal.stripTrailingZeros().scale() > 0) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action parameter must be an integer: " + path,
                path
            );
        }
        validateBounds(path, decimal, schema);
    }

    private static void validateNumber(String path,
                                       Object value,
                                       AIActionParamSchema schema) {
        if (!(value instanceof Number number)) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action parameter must be numeric: " + path,
                path
            );
        }
        validateBounds(path, decimal(path, number), schema);
    }

    private static void validateBounds(String path,
                                       BigDecimal value,
                                       AIActionParamSchema schema) {
        if (schema.getMin() != null
            && value.compareTo(BigDecimal.valueOf(schema.getMin())) < 0) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action parameter is below its minimum: " + path,
                path
            );
        }
        if (schema.getMax() != null
            && value.compareTo(BigDecimal.valueOf(schema.getMax())) > 0) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action parameter exceeds its maximum: " + path,
                path
            );
        }
    }

    private static void validateObject(String path,
                                       Object value,
                                       AIActionParamSchema schema,
                                       int depth) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action parameter must be an object: " + path,
                path
            );
        }
        Map<String, Object> object = new LinkedHashMap<>();
        raw.forEach((key, nested) -> {
            if (!(key instanceof String name)) {
                throw invalid(
                    "ACTION_PARAMETER_INVALID",
                    "Action object keys must be strings: " + path,
                    path
                );
            }
            object.put(name, nested);
        });
        if (object.size() > MAX_OBJECT_PROPERTIES) {
            throw invalid(
                "ACTION_PARAMETERS_TOO_LARGE",
                "Action object contains too many properties: " + path,
                path
            );
        }
        Map<String, AIActionParamSchema> properties = schema.getProperties() == null
            ? Map.of()
            : schema.getProperties();
        List<String> missing = schema.getRequiredProperties() == null
            ? List.of()
            : schema.getRequiredProperties().stream()
                .filter(name -> missing(object.get(name)))
                .toList();
        if (!missing.isEmpty()) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action object is missing required properties: " + path,
                path + "." + missing.getFirst()
            );
        }
        if (properties.isEmpty()) {
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                validateUntypedValue(
                    path + "." + entry.getKey(),
                    entry.getValue(),
                    depth + 1
                );
            }
            return;
        }
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            AIActionParamSchema nested = properties.get(entry.getKey());
            if (nested == null) {
                throw invalid(
                    "ACTION_PARAMETER_INVALID",
                    "Action object contains an unsupported property: " + path,
                    path + "." + entry.getKey()
                );
            }
            validateValue(
                path + "." + entry.getKey(),
                entry.getValue(),
                nested,
                depth + 1
            );
        }
    }

    private static void validateArray(String path,
                                      Object value,
                                      AIActionParamSchema schema,
                                      int depth) {
        if (!(value instanceof List<?> items)) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action parameter must be an array: " + path,
                path
            );
        }
        if (items.size() > MAX_ARRAY_ITEMS) {
            throw invalid(
                "ACTION_PARAMETERS_TOO_LARGE",
                "Action array contains too many items: " + path,
                path
            );
        }
        for (int index = 0; index < items.size(); index++) {
            String itemPath = path + "[" + index + "]";
            if (schema.getItems() == null) {
                validateUntypedValue(itemPath, items.get(index), depth + 1);
            } else {
                validateValue(
                    itemPath,
                    items.get(index),
                    schema.getItems(),
                    depth + 1
                );
            }
        }
    }

    private static void validateUntypedValue(String path, Object value, int depth) {
        if (depth > MAX_NESTING_DEPTH) {
            throw invalid(
                "ACTION_PARAMETERS_TOO_LARGE",
                "Action parameters exceed the maximum nesting depth.",
                path
            );
        }
        if (value == null || value instanceof Boolean) {
            return;
        }
        if (value instanceof String text) {
            if (text.length() > MAX_STRING_LENGTH) {
                throw invalid(
                    "ACTION_PARAMETERS_TOO_LARGE",
                    "Action parameter text exceeds the maximum length: " + path,
                    path
                );
            }
            return;
        }
        if (value instanceof Number number) {
            decimal(path, number);
            return;
        }
        if (value instanceof List<?> items) {
            if (items.size() > MAX_ARRAY_ITEMS) {
                throw invalid(
                    "ACTION_PARAMETERS_TOO_LARGE",
                    "Action array contains too many items: " + path,
                    path
                );
            }
            for (int index = 0; index < items.size(); index++) {
                validateUntypedValue(path + "[" + index + "]", items.get(index), depth + 1);
            }
            return;
        }
        if (value instanceof Map<?, ?> object) {
            if (object.size() > MAX_OBJECT_PROPERTIES) {
                throw invalid(
                    "ACTION_PARAMETERS_TOO_LARGE",
                    "Action object contains too many properties: " + path,
                    path
                );
            }
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                if (!(entry.getKey() instanceof String name)) {
                    throw invalid(
                        "ACTION_PARAMETER_INVALID",
                        "Action object keys must be strings: " + path,
                        path
                    );
                }
                validateUntypedValue(path + "." + name, entry.getValue(), depth + 1);
            }
            return;
        }
        throw invalid(
            "ACTION_PARAMETER_INVALID",
            "Action parameter contains an unsupported value type: " + path,
            path
        );
    }

    private static BigDecimal decimal(String path, Number number) {
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException ex) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action parameter must be a finite number: " + path,
                path
            );
        }
    }

    private static void requireType(String path, Object value, Class<?> expected) {
        if (!expected.isInstance(value)) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action parameter has the wrong type: " + path,
                path
            );
        }
    }

    private static boolean missing(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    private static ActionParameterContractException invalid(String reason,
                                                            String message,
                                                            String path) {
        return new ActionParameterContractException(reason, message, path);
    }

    public static final class ActionParameterContractException extends IllegalArgumentException {
        private final String reason;
        private final String path;

        public ActionParameterContractException(String reason, String message, String path) {
            super(message);
            this.reason = reason;
            this.path = path;
        }

        public String reason() {
            return reason;
        }

        public String path() {
            return path;
        }
    }
}
