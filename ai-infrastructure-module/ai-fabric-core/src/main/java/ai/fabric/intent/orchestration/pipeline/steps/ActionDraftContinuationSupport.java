package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionNames;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.actiondraft.ActionDraft;
import ai.fabric.intent.actiondraft.ActionDraftContinuation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * Builds safe action-draft context and merges it with LLM-extracted parameters.
 */
final class ActionDraftContinuationSupport {

    private static final Set<String> HIDDEN_VISIBILITIES = Set.of(
        "INTERNAL",
        "SECRET",
        "SYSTEM"
    );

    private ActionDraftContinuationSupport() {
    }

    static ActionDraftContinuation continuation(
        ActionDraft draft,
        AIActionMetaData metadata
    ) {
        if (draft == null
            || !StringUtils.hasText(draft.action())
            || metadata == null) {
            return null;
        }
        Map<String, Object> collected = sanitizePublicParameters(
            metadata,
            draft.params()
        );
        List<String> missing = metadata.getRequiredParameters() == null
            ? List.of()
            : metadata.getRequiredParameters().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(parameter ->
                    ActionParameterSupport.isUserVisibleActionParameter(
                        metadata,
                        parameter
                    )
                )
                .filter(parameter ->
                    !ActionContextSchemaSupport.hasMeaningfulActionParamValue(
                        collected.get(parameter)
                    )
                )
                .distinct()
                .toList();
        return new ActionDraftContinuation(
            draft.action(),
            collected,
            missing
        );
    }

    static MergeOutcome merge(
        MultiIntentResponse response,
        ActionDraftContinuation continuation
    ) {
        if (response == null
            || response.getIntents() == null
            || response.getIntents().isEmpty()
            || continuation == null) {
            return new MergeOutcome(response, false, List.of(), List.of());
        }

        String draftAction = AIActionNames.normalize(continuation.action());
        for (Intent intent : response.getIntents()) {
            if (intent == null || intent.getType() != IntentType.ACTION) {
                continue;
            }
            String currentAction = AIActionNames.normalize(
                intent.getIntentOrAction()
            );
            if (!StringUtils.hasText(currentAction)
                || !draftAction.equals(currentAction)) {
                continue;
            }

            Map<String, Object> current = intent.getActionParams() != null
                ? intent.getActionParams()
                : Map.of();
            Map<String, Object> merged = deepMerge(
                continuation.collectedParams(),
                current
            );
            List<String> preserved = continuation.collectedParams().keySet()
                .stream()
                .filter(key ->
                    !ActionContextSchemaSupport.hasMeaningfulActionParamValue(
                        current.get(key)
                    )
                )
                .toList();
            List<String> supplied = current.entrySet().stream()
                .filter(entry ->
                    StringUtils.hasText(entry.getKey())
                        && ActionContextSchemaSupport
                            .hasMeaningfulActionParamValue(entry.getValue())
                )
                .map(Map.Entry::getKey)
                .map(String::trim)
                .distinct()
                .toList();
            intent.setActionParams(merged);
            return new MergeOutcome(
                response,
                true,
                preserved,
                supplied
            );
        }
        return new MergeOutcome(response, false, List.of(), List.of());
    }

    static Map<String, Object> sanitizePublicParameters(
        AIActionMetaData metadata,
        Map<String, Object> params
    ) {
        Map<String, Object> publicParams =
            ActionMetadataVisibilitySupport.publicProvidedParameters(
                metadata,
                params
            );
        if (publicParams.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        publicParams.forEach((name, value) -> {
            AIActionParamSchema schema = ActionParameterSupport.paramSchema(
                metadata,
                name
            );
            Object safeValue = sanitizeValue(value, schema);
            if (safeValue != null) {
                sanitized.put(name, safeValue);
            }
        });
        return sanitized.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(sanitized);
    }

    static Map<String, Object> sanitizeAnalyzedParameters(
        AIActionMetaData metadata,
        Map<String, Object> params,
        Set<String> allowedParameterNames
    ) {
        if (metadata == null
            || params == null
            || params.isEmpty()
            || allowedParameterNames == null
            || allowedParameterNames.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        params.forEach((rawName, value) -> {
            String canonicalName = canonicalName(
                allowedParameterNames,
                rawName
            );
            if (!StringUtils.hasText(canonicalName)
                || !ActionContextSchemaSupport
                    .hasMeaningfulActionParamValue(value)) {
                return;
            }
            AIActionParamSchema schema = ActionParameterSupport.paramSchema(
                metadata,
                canonicalName
            );
            Object safeValue = sanitizeAnalyzedValue(value, schema);
            if (safeValue != null) {
                sanitized.put(canonicalName, safeValue);
            }
        });
        return sanitized.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(sanitized);
    }

    private static Object sanitizeAnalyzedValue(
        Object value,
        AIActionParamSchema schema
    ) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, AIActionParamSchema> properties =
                schema != null && schema.getProperties() != null
                    ? schema.getProperties()
                    : Map.of();
            if (properties.isEmpty()) {
                return Map.of();
            }
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((rawKey, nestedValue) -> {
                if (rawKey == null || nestedValue == null) {
                    return;
                }
                String canonical = canonicalName(
                    properties.keySet(),
                    String.valueOf(rawKey)
                );
                AIActionParamSchema nestedSchema = properties.get(canonical);
                if (!StringUtils.hasText(canonical)
                    || nestedSchema == null
                    || hidden(nestedSchema)) {
                    return;
                }
                Object safeValue = sanitizeAnalyzedValue(
                    nestedValue,
                    nestedSchema
                );
                if (safeValue != null) {
                    sanitized.put(canonical, safeValue);
                }
            });
            return sanitized.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(sanitized);
        }
        if (value instanceof List<?> list) {
            AIActionParamSchema itemSchema =
                schema != null && schema.getType() == AIActionParamType.ARRAY
                    ? schema.getItems()
                    : null;
            List<Object> sanitized = new ArrayList<>();
            for (Object item : list) {
                Object safeValue = sanitizeAnalyzedValue(item, itemSchema);
                if (safeValue != null) {
                    sanitized.add(safeValue);
                }
            }
            return Collections.unmodifiableList(sanitized);
        }
        return value;
    }

    private static String canonicalName(
        Set<String> allowedNames,
        String candidate
    ) {
        if (allowedNames == null
            || !StringUtils.hasText(candidate)) {
            return null;
        }
        String normalized = candidate.trim();
        return allowedNames.stream()
            .filter(StringUtils::hasText)
            .filter(name -> name.trim().equalsIgnoreCase(normalized))
            .map(String::trim)
            .findFirst()
            .orElse(null);
    }

    private static Object sanitizeValue(
        Object value,
        AIActionParamSchema schema
    ) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, AIActionParamSchema> properties =
                schema != null && schema.getProperties() != null
                    ? schema.getProperties()
                    : Map.of();
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((rawKey, nestedValue) -> {
                if (rawKey == null || nestedValue == null) {
                    return;
                }
                String key = String.valueOf(rawKey).trim();
                if (!StringUtils.hasText(key)) {
                    return;
                }
                AIActionParamSchema nestedSchema = schemaIgnoreCase(
                    properties,
                    key
                );
                if (nestedSchema != null && hidden(nestedSchema)) {
                    return;
                }
                Object safeValue = sanitizeValue(nestedValue, nestedSchema);
                if (safeValue != null) {
                    sanitized.put(key, safeValue);
                }
            });
            return Collections.unmodifiableMap(sanitized);
        }
        if (value instanceof List<?> list) {
            AIActionParamSchema itemSchema =
                schema != null && schema.getType() == AIActionParamType.ARRAY
                    ? schema.getItems()
                    : null;
            List<Object> sanitized = new ArrayList<>(list.size());
            for (Object item : list) {
                Object safeValue = sanitizeValue(item, itemSchema);
                if (safeValue != null) {
                    sanitized.add(safeValue);
                }
            }
            return Collections.unmodifiableList(sanitized);
        }
        return value;
    }

    private static AIActionParamSchema schemaIgnoreCase(
        Map<String, AIActionParamSchema> schemas,
        String name
    ) {
        if (schemas == null || schemas.isEmpty()) {
            return null;
        }
        AIActionParamSchema exact = schemas.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, AIActionParamSchema> entry : schemas.entrySet()) {
            if (entry != null
                && StringUtils.hasText(entry.getKey())
                && name.equalsIgnoreCase(entry.getKey().trim())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean hidden(AIActionParamSchema schema) {
        if (schema == null) {
            return false;
        }
        if (Boolean.FALSE.equals(schema.getAskUser())) {
            return true;
        }
        String visibility = schema.getVisibility();
        return StringUtils.hasText(visibility)
            && HIDDEN_VISIBILITIES.contains(
                visibility.trim().toUpperCase(Locale.ROOT)
            );
    }

    private static Map<String, Object> deepMerge(
        Map<String, Object> previous,
        Map<String, Object> current
    ) {
        Map<String, Object> merged = mutableCopy(previous);
        if (current != null) {
            current.forEach((key, value) -> {
                if (!StringUtils.hasText(key)
                    || !ActionContextSchemaSupport
                        .hasMeaningfulActionParamValue(value)) {
                    return;
                }
                String normalizedKey = key.trim();
                Object oldValue = merged.get(normalizedKey);
                if (oldValue instanceof Map<?, ?> oldMap
                    && value instanceof Map<?, ?> newMap) {
                    merged.put(
                        normalizedKey,
                        deepMerge(stringMap(oldMap), stringMap(newMap))
                    );
                } else {
                    merged.put(normalizedKey, immutableCopy(value));
                }
            });
        }
        return merged.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(merged);
    }

    private static Map<String, Object> mutableCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        source.forEach((key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
                copy.put(key.trim(), immutableCopy(value));
            }
        });
        return copy;
    }

    private static Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(String.valueOf(key), value);
            }
        });
        return copy;
    }

    private static Object immutableCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            return Collections.unmodifiableMap(mutableCopy(stringMap(map)));
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) {
                    copy.add(immutableCopy(item));
                }
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    record MergeOutcome(
        MultiIntentResponse response,
        boolean matched,
        List<String> preservedParameterNames,
        List<String> suppliedParameterNames
    ) {
        MergeOutcome {
            preservedParameterNames = preservedParameterNames == null
                ? List.of()
                : List.copyOf(new LinkedHashSet<>(preservedParameterNames));
            suppliedParameterNames = suppliedParameterNames == null
                ? List.of()
                : List.copyOf(new LinkedHashSet<>(suppliedParameterNames));
        }
    }
}
