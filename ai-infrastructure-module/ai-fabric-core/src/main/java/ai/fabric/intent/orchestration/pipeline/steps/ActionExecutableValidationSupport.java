package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static ai.fabric.intent.orchestration.pipeline.steps.ActionEvidenceSupport.isEvidenceBoundValueTrusted;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParameterSupport.normalizeParameterNameSet;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionValueSupport.hasMeaningfulJavaValue;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionValueSupport.numericValue;

/**
 * Internal validation for executable action arguments, especially connector/MCP-backed actions.
 */
final class ActionExecutableValidationSupport {

    private ActionExecutableValidationSupport() {
    }

    record ActionExecutableValidation(
        List<String> missingExecutable,
        List<String> invalidArguments,
        List<String> untrustedArguments,
        Map<String, Object> debugMetadata
    ) {
        boolean hasFailures() {
            return !missingExecutable.isEmpty() || !invalidArguments.isEmpty() || !untrustedArguments.isEmpty();
        }

        List<String> publicMissing() {
            List<String> out = new ArrayList<>();
            out.addAll(missingExecutable);
            out.addAll(invalidArguments);
            out.addAll(untrustedArguments);
            return out.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        }
    }

    static ActionExecutableValidation validateExecutableActionParams(Map<String, Object> actionRuntimeConfig,
                                                                     AIActionMetaData meta,
                                                                     Map<String, Object> params,
                                                                     ActionEvidenceSupport.EvidenceBundle evidence,
                                                                     Set<String> trustedResolvedParameters) {
        if (!isMcpRuntimeAction(actionRuntimeConfig)) {
            return null;
        }

        List<String> missingExecutable = new ArrayList<>();
        List<String> invalidArguments = new ArrayList<>();
        List<String> untrustedArguments = new ArrayList<>();
        Map<String, Object> debug = new LinkedHashMap<>();
        Set<String> trustedResolved = normalizeParameterNameSet(trustedResolvedParameters);

        List<String> requiredAnyArguments = mcpRequiredAnyArguments(actionRuntimeConfig);
        if (!requiredAnyArguments.isEmpty()) {
            boolean hasAny = false;
            for (String requiredAny : requiredAnyArguments) {
                if (hasMeaningfulActionParamAtPath(params, requiredAny)) {
                    hasAny = true;
                    break;
                }
            }
            if (!hasAny) {
                missingExecutable.addAll(requiredAnyArguments);
            }
        }

        if (meta != null && meta.getParameterSchemas() != null && !meta.getParameterSchemas().isEmpty()) {
            for (Map.Entry<String, AIActionParamSchema> entry : meta.getParameterSchemas().entrySet()) {
                if (entry == null || !StringUtils.hasText(entry.getKey()) || entry.getValue() == null) {
                    continue;
                }
                Object value = params != null ? params.get(entry.getKey()) : null;
                if (!hasMeaningfulJavaValue(value)) {
                    continue;
                }
                validateExecutableParamValue(
                    entry.getKey(),
                    value,
                    entry.getValue(),
                    evidence,
                    trustedResolved,
                    invalidArguments,
                    untrustedArguments
                );
            }
        }

        debug.put("missingExecutable", List.copyOf(missingExecutable));
        debug.put("invalidArguments", List.copyOf(invalidArguments));
        debug.put("untrustedArguments", List.copyOf(untrustedArguments));
        debug.put("requiredAnyArguments", List.copyOf(requiredAnyArguments));
        debug.put("trustedResolvedParameters", List.copyOf(trustedResolved));
        debug.put("sourcesUsed", evidence != null ? evidence.sourcesUsed() : Map.of());
        return new ActionExecutableValidation(
            List.copyOf(missingExecutable),
            List.copyOf(invalidArguments),
            List.copyOf(untrustedArguments),
            Collections.unmodifiableMap(debug)
        );
    }

    static boolean isMcpRuntimeAction(Map<String, Object> actionRuntimeConfig) {
        if (actionRuntimeConfig == null || actionRuntimeConfig.isEmpty()) {
            return false;
        }
        if ("mcp-tool".equalsIgnoreCase(textFromMap(actionRuntimeConfig, "adapterType"))) {
            return true;
        }
        Object execution = actionRuntimeConfig.get("execution");
        if (execution instanceof Map<?, ?> executionMap) {
            if ("mcp-tool".equalsIgnoreCase(textFromMap(executionMap, "adapterType"))) {
                return true;
            }
            return executionMap.get("mcp") instanceof Map<?, ?>;
        }
        return false;
    }

    static List<String> mcpRequiredAnyArguments(Map<String, Object> actionRuntimeConfig) {
        Map<?, ?> mcp = mcpRuntimeConfig(actionRuntimeConfig);
        if (mcp == null || mcp.isEmpty()) {
            return List.of();
        }
        Object raw = mcp.get("requiredAnyArguments");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.stream()
            .filter(value -> value != null && StringUtils.hasText(value.toString()))
            .map(value -> value.toString().trim())
            .toList();
    }

    private static Map<?, ?> mcpRuntimeConfig(Map<String, Object> actionRuntimeConfig) {
        if (actionRuntimeConfig == null || actionRuntimeConfig.isEmpty()) {
            return Map.of();
        }
        Object execution = actionRuntimeConfig.get("execution");
        if (execution instanceof Map<?, ?> executionMap && executionMap.get("mcp") instanceof Map<?, ?> mcpMap) {
            return mcpMap;
        }
        Object directMcp = actionRuntimeConfig.get("mcp");
        if (directMcp instanceof Map<?, ?> mcpMap) {
            return mcpMap;
        }
        return Map.of();
    }

    static boolean hasMeaningfulActionParamAtPath(Map<String, Object> params, String configuredPath) {
        Object value = readActionParamAtPath(params, configuredPath);
        return hasMeaningfulJavaValue(value);
    }

    static Object readActionParamAtPath(Map<String, Object> params, String configuredPath) {
        if (params == null || params.isEmpty() || !StringUtils.hasText(configuredPath)) {
            return null;
        }
        String path = configuredPath.trim();
        if (path.startsWith("$.")) {
            path = path.substring(2);
        }
        if (path.startsWith("params.")) {
            path = path.substring("params.".length());
        }
        if (!StringUtils.hasText(path)) {
            return null;
        }

        Object current = params;
        for (String token : path.split("\\.")) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(token.trim());
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static void validateExecutableParamValue(String path,
                                                     Object value,
                                                     AIActionParamSchema schema,
                                                     ActionEvidenceSupport.EvidenceBundle evidence,
                                                     Set<String> trustedResolvedParameters,
                                                     List<String> invalidArguments,
                                                     List<String> untrustedArguments) {
        if (schema == null || !hasMeaningfulJavaValue(value)) {
            return;
        }

        boolean trustedResolvedValue = isTrustedResolvedExecutablePath(path, trustedResolvedParameters);
        if (Boolean.TRUE.equals(schema.getEvidenceBound())
            && !trustedResolvedValue
            && !isEvidenceBoundValueTrusted(
                value,
                schema,
                evidence != null ? evidence.trustedValuesByKey() : null
            )) {
            untrustedArguments.add(path);
            return;
        }

        validateScalarExecutableConstraints(path, value, schema, invalidArguments);

        if (schema.getType() == AIActionParamType.ARRAY) {
            if (!(value instanceof List<?> list)) {
                invalidArguments.add(path);
                return;
            }
            AIActionParamSchema itemSchema = schema.getItems();
            if (itemSchema == null) {
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                validateExecutableParamValue(
                    path + "[" + i + "]",
                    list.get(i),
                    itemSchema,
                    evidence,
                    trustedResolvedParameters,
                    invalidArguments,
                    untrustedArguments
                );
            }
            return;
        }

        if (schema.getType() == AIActionParamType.OBJECT) {
            if (!(value instanceof Map<?, ?> map)) {
                invalidArguments.add(path);
                return;
            }
            validateExecutableRequiredProperties(path, map, schema, invalidArguments);
            if (schema.getProperties() == null || schema.getProperties().isEmpty()) {
                return;
            }
            for (Map.Entry<String, AIActionParamSchema> property : schema.getProperties().entrySet()) {
                if (property == null || !StringUtils.hasText(property.getKey()) || property.getValue() == null) {
                    continue;
                }
                Object propertyValue = map.get(property.getKey());
                validateExecutableParamValue(
                    path + "." + property.getKey(),
                    propertyValue,
                    property.getValue(),
                    evidence,
                    trustedResolvedParameters,
                    invalidArguments,
                    untrustedArguments
                );
            }
        }
    }

    private static boolean isTrustedResolvedExecutablePath(String path, Set<String> trustedResolvedParameters) {
        if (!StringUtils.hasText(path) || trustedResolvedParameters == null || trustedResolvedParameters.isEmpty()) {
            return false;
        }
        String root = path.trim();
        int dot = root.indexOf('.');
        int bracket = root.indexOf('[');
        int end = root.length();
        if (dot >= 0) {
            end = Math.min(end, dot);
        }
        if (bracket >= 0) {
            end = Math.min(end, bracket);
        }
        if (end <= 0) {
            return false;
        }
        return trustedResolvedParameters.contains(root.substring(0, end).trim().toLowerCase(java.util.Locale.ROOT));
    }

    private static void validateScalarExecutableConstraints(String path,
                                                            Object value,
                                                            AIActionParamSchema schema,
                                                            List<String> invalidArguments) {
        if (schema == null || value == null) {
            return;
        }
        if (StringUtils.hasText(schema.getPattern())) {
            String raw = value.toString();
            try {
                if (!Pattern.compile(schema.getPattern()).matcher(raw).matches()) {
                    invalidArguments.add(path);
                }
            } catch (PatternSyntaxException ex) {
                invalidArguments.add(path);
            }
        }
        if (schema.getMin() != null) {
            Double numeric = numericValue(value);
            if (numeric == null || numeric < schema.getMin()) {
                invalidArguments.add(path);
            }
        }
        if (schema.getMax() != null) {
            Double numeric = numericValue(value);
            if (numeric == null || numeric > schema.getMax()) {
                invalidArguments.add(path);
            }
        }
        if (schema.getAllowedValues() != null && !schema.getAllowedValues().isEmpty()) {
            String normalized = value.toString().trim();
            boolean allowed = schema.getAllowedValues().stream()
                .filter(StringUtils::hasText)
                .anyMatch(allowedValue -> allowedValue.trim().equalsIgnoreCase(normalized));
            if (!allowed) {
                invalidArguments.add(path);
            }
        }
    }

    private static void validateExecutableRequiredProperties(String path,
                                                             Map<?, ?> value,
                                                             AIActionParamSchema schema,
                                                             List<String> invalidArguments) {
        if (schema == null || schema.getRequiredProperties() == null || schema.getRequiredProperties().isEmpty()) {
            return;
        }
        for (String requiredProperty : schema.getRequiredProperties()) {
            if (!StringUtils.hasText(requiredProperty)) {
                continue;
            }
            Object propertyValue = value != null ? value.get(requiredProperty.trim()) : null;
            if (!hasMeaningfulJavaValue(propertyValue)) {
                invalidArguments.add(path + "." + requiredProperty.trim());
            }
        }
    }

    private static String textFromMap(Map<?, ?> map, String key) {
        if (map == null || !StringUtils.hasText(key)) {
            return null;
        }
        Object value = map.get(key);
        return value != null && StringUtils.hasText(value.toString()) ? value.toString().trim() : null;
    }
}
