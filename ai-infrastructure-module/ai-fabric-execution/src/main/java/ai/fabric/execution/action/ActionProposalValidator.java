package ai.fabric.execution.action;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionNames;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Strict parameter and action-contract validation for persisted proposals.
 */
public final class ActionProposalValidator {

    private static final int MAX_TOP_LEVEL_PARAMETERS = 32;
    private static final int MAX_OBJECT_PROPERTIES = 64;
    private static final int MAX_ARRAY_ITEMS = 100;
    private static final int MAX_STRING_LENGTH = 4096;
    private static final int MAX_NESTING_DEPTH = 8;

    public void validateAction(
        AIActionMetaData metadata,
        String actionName,
        EffectiveCapabilityProfile profile
    ) {
        if (metadata == null) {
            throw invalid("ACTION_NOT_FOUND", "Action is not registered.");
        }
        String normalized = AIActionNames.normalize(actionName);
        if (!normalized.equals(AIActionNames.normalize(metadata.getName()))) {
            throw invalid(
                "ACTION_METADATA_MISMATCH",
                "Action metadata does not match the proposal."
            );
        }
        if (metadata.getAccessMode() == null
            || metadata.getAccessMode().isReadOnly()) {
            throw invalid(
                "SPECIALIST_WRITE_ACTION_REQUIRED",
                "Only registered write actions can create proposal receipts."
            );
        }
        if (!metadata.isConfirmationRequired()) {
            throw invalid(
                "SPECIALIST_WRITE_CONFIRMATION_REQUIRED",
                "Specialist write actions must require explicit confirmation."
            );
        }
        if (profile == null || !profile.canProposeWriteAction(normalized)) {
            throw invalid(
                "ACTION_NOT_IN_EFFECTIVE_PROFILE",
                "Action is not available in the effective specialist profile."
            );
        }
    }

    public void validateParameters(
        AIActionMetaData metadata,
        Map<String, Object> parameters
    ) {
        Map<String, Object> values =
            parameters == null ? Map.of() : parameters;
        Map<String, AIActionParamSchema> schemas =
            metadata.getParameterSchemas() == null
                ? Map.of()
                : metadata.getParameterSchemas();
        Set<String> required = metadata.getRequiredParameters() == null
            ? Set.of()
            : metadata.getRequiredParameters();

        if (values.size() > MAX_TOP_LEVEL_PARAMETERS) {
            throw invalid(
                "ACTION_PARAMETERS_TOO_LARGE",
                "The action proposal contains too many parameters."
            );
        }
        List<String> requiredWithoutSchema = required.stream()
            .filter(name -> !schemas.containsKey(name))
            .sorted()
            .toList();
        if (!requiredWithoutSchema.isEmpty()) {
            throw invalid(
                "ACTION_PARAMETER_SCHEMA_INVALID",
                "A required action parameter has no registered schema."
            );
        }
        List<String> missing = required.stream()
            .filter(name -> missing(values.get(name)))
            .sorted()
            .toList();
        if (!missing.isEmpty()) {
            throw invalid(
                "ACTION_REQUIRED_PARAMETERS_MISSING",
                "Required action parameters are missing: "
                    + String.join(", ", missing)
            );
        }
        List<String> unknown = values.keySet().stream()
            .filter(name -> !schemas.containsKey(name))
            .sorted()
            .toList();
        if (!unknown.isEmpty()) {
            throw invalid(
                "ACTION_UNKNOWN_PARAMETERS",
                "Action parameters contain unsupported fields."
            );
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            AIActionParamSchema schema = schemas.get(entry.getKey());
            if (schema != null) {
                validateValue(entry.getKey(), entry.getValue(), schema, 0);
            }
        }
    }

    public String schemaHash(
        AIActionMetaData metadata,
        ActionProposalSecurity security
    ) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("name", AIActionNames.normalize(metadata.getName()));
        contract.put(
            "accessMode",
            metadata.getAccessMode() != null
                ? metadata.getAccessMode().name()
                : null
        );
        contract.put("confirmationRequired", metadata.isConfirmationRequired());
        contract.put(
            "requiredParameters",
            metadata.getRequiredParameters() == null
                ? List.of()
                : metadata.getRequiredParameters().stream().sorted().toList()
        );
        Map<String, Object> schemas = new TreeMap<>();
        if (metadata.getParameterSchemas() != null) {
            metadata.getParameterSchemas().forEach((name, schema) ->
                schemas.put(name, canonicalSchema(schema))
            );
        }
        contract.put("parameterSchemas", schemas);
        return security.canonicalHash(contract);
    }

    private void validateValue(
        String path,
        Object value,
        AIActionParamSchema schema,
        int depth
    ) {
        if (depth > MAX_NESTING_DEPTH) {
            throw invalid(
                "ACTION_PARAMETERS_TOO_LARGE",
                "Action parameters exceed the maximum nesting depth."
            );
        }
        if (value == null) {
            if (Boolean.TRUE.equals(schema.getRequired())) {
                throw invalid(
                    "ACTION_PARAMETER_INVALID",
                    "Required action parameter is null: " + path
                );
            }
            return;
        }
        AIActionParamType type =
            schema.getType() != null ? schema.getType() : AIActionParamType.UNKNOWN;
        switch (type) {
            case STRING -> validateString(path, value, schema);
            case INTEGER -> validateInteger(path, value, schema);
            case NUMBER -> validateNumber(path, value, schema);
            case BOOLEAN -> requireType(path, value, Boolean.class);
            case OBJECT -> validateObject(path, value, schema, depth);
            case ARRAY -> validateArray(path, value, schema, depth);
            case UNKNOWN -> throw invalid(
                "ACTION_PARAMETER_SCHEMA_INVALID",
                "Persisted write parameters require a concrete registered type."
            );
        }
    }

    private void validateString(
        String path,
        Object value,
        AIActionParamSchema schema
    ) {
        requireType(path, value, String.class);
        String text = (String) value;
        if (text.length() > MAX_STRING_LENGTH) {
            throw invalid(
                "ACTION_PARAMETERS_TOO_LARGE",
                "Action parameter text exceeds the maximum length: " + path
            );
        }
        if (schema.getAllowedValues() != null
            && !schema.getAllowedValues().isEmpty()
            && schema.getAllowedValues().stream()
                .noneMatch(allowed -> allowed.equalsIgnoreCase(text))) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action parameter has an unsupported value: " + path
            );
        }
        if (schema.getPattern() != null && !schema.getPattern().isBlank()) {
            try {
                if (!Pattern.compile(schema.getPattern()).matcher(text).matches()) {
                    throw invalid(
                        "ACTION_PARAMETER_INVALID",
                        "Action parameter does not match its contract: " + path
                    );
                }
            } catch (PatternSyntaxException ex) {
                throw invalid(
                    "ACTION_PARAMETER_SCHEMA_INVALID",
                    "Registered action parameter pattern is invalid."
                );
            }
        }
    }

    private void validateInteger(
        String path,
        Object value,
        AIActionParamSchema schema
    ) {
        if (!(value instanceof Number number)) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action parameter must be an integer: " + path
            );
        }
        BigDecimal decimal = decimal(path, number);
        if (decimal.stripTrailingZeros().scale() > 0) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action parameter must be an integer: " + path
            );
        }
        validateBounds(path, decimal, schema);
    }

    private void validateNumber(
        String path,
        Object value,
        AIActionParamSchema schema
    ) {
        if (!(value instanceof Number number)) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action parameter must be numeric: " + path
            );
        }
        validateBounds(path, decimal(path, number), schema);
    }

    private void validateBounds(
        String path,
        BigDecimal value,
        AIActionParamSchema schema
    ) {
        if (schema.getMin() != null
            && value.compareTo(BigDecimal.valueOf(schema.getMin())) < 0) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action parameter is below its minimum: " + path
            );
        }
        if (schema.getMax() != null
            && value.compareTo(BigDecimal.valueOf(schema.getMax())) > 0) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action parameter exceeds its maximum: " + path
            );
        }
    }

    private void validateObject(
        String path,
        Object value,
        AIActionParamSchema schema,
        int depth
    ) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action parameter must be an object: " + path
            );
        }
        Map<String, Object> object = new LinkedHashMap<>();
        raw.forEach((key, nested) -> {
            if (!(key instanceof String name)) {
                throw invalid(
                    "ACTION_PARAMETER_INVALID",
                    "Action object keys must be strings: " + path
                );
            }
            object.put(name, nested);
        });
        if (object.size() > MAX_OBJECT_PROPERTIES) {
            throw invalid(
                "ACTION_PARAMETERS_TOO_LARGE",
                "Action object contains too many properties: " + path
            );
        }
        Map<String, AIActionParamSchema> properties =
            schema.getProperties() == null ? Map.of() : schema.getProperties();
        List<String> missing = schema.getRequiredProperties() == null
            ? List.of()
            : schema.getRequiredProperties().stream()
                .filter(name -> missing(object.get(name)))
                .toList();
        if (!missing.isEmpty()) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action object is missing required properties: " + path
            );
        }
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            AIActionParamSchema nested = properties.get(entry.getKey());
            if (nested == null) {
                throw invalid(
                    "ACTION_PARAMETER_INVALID",
                    "Action object contains an unsupported property: " + path
                );
            }
            if (nested != null) {
                validateValue(
                    path + "." + entry.getKey(),
                    entry.getValue(),
                    nested,
                    depth + 1
                );
            }
        }
    }

    private void validateArray(
        String path,
        Object value,
        AIActionParamSchema schema,
        int depth
    ) {
        if (!(value instanceof List<?> items)) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action parameter must be an array: " + path
            );
        }
        if (items.size() > MAX_ARRAY_ITEMS) {
            throw invalid(
                "ACTION_PARAMETERS_TOO_LARGE",
                "Action array contains too many items: " + path
            );
        }
        if (!items.isEmpty() && schema.getItems() == null) {
            throw invalid(
                "ACTION_PARAMETER_SCHEMA_INVALID",
                "Action array items require a registered schema."
            );
        }
        int index = 0;
        for (Object item : items) {
            if (schema.getItems() != null) {
                validateValue(
                    path + "[" + index + "]",
                    item,
                    schema.getItems(),
                    depth + 1
                );
            }
            index++;
        }
    }

    private BigDecimal decimal(String path, Number number) {
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException ex) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action parameter must be a finite number: " + path
            );
        }
    }

    private Map<String, Object> canonicalSchema(AIActionParamSchema schema) {
        if (schema == null) {
            return Map.of();
        }
        Map<String, Object> canonical = new TreeMap<>();
        put(canonical, "name", schema.getName());
        put(canonical, "type", schema.getType());
        put(canonical, "required", schema.getRequired());
        put(canonical, "batchTargets", schema.getBatchTargets());
        put(canonical, "pattern", schema.getPattern());
        put(canonical, "allowedValues", sorted(schema.getAllowedValues()));
        put(canonical, "min", schema.getMin());
        put(canonical, "max", schema.getMax());
        put(canonical, "visibility", schema.getVisibility());
        put(canonical, "askUser", schema.getAskUser());
        put(canonical, "resolveFrom", schema.getResolveFrom());
        put(canonical, "evidenceBound", schema.getEvidenceBound());
        put(canonical, "evidenceKeys", sorted(schema.getEvidenceKeys()));
        put(
            canonical,
            "evidenceFallbackPolicy",
            schema.getEvidenceFallbackPolicy()
        );
        if (schema.getItems() != null) {
            canonical.put("items", canonicalSchema(schema.getItems()));
        }
        if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            Map<String, Object> properties = new TreeMap<>();
            schema.getProperties().forEach((name, nested) ->
                properties.put(name, canonicalSchema(nested))
            );
            canonical.put("properties", properties);
        }
        put(
            canonical,
            "requiredProperties",
            sorted(schema.getRequiredProperties())
        );
        return Map.copyOf(canonical);
    }

    private void requireType(
        String path,
        Object value,
        Class<?> expected
    ) {
        if (!expected.isInstance(value)) {
            throw invalid(
                "ACTION_PARAMETER_INVALID",
                "Action parameter has the wrong type: " + path
            );
        }
    }

    private boolean missing(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    private List<String> sorted(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value instanceof Enum<?> item ? item.name() : value);
        }
    }

    private ActionProposalValidationException invalid(
        String reason,
        String message
    ) {
        return new ActionProposalValidationException(reason, message);
    }
}
