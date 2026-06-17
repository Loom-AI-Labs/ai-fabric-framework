package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Schema-driven helpers for batch-capable action parameters.
 */
@Slf4j
final class ActionBatchSupport {

    private final AIActionRegistry actionHandlerRegistry;

    ActionBatchSupport(AIActionRegistry actionHandlerRegistry) {
        this.actionHandlerRegistry = actionHandlerRegistry;
    }

    /**
     * Default population for batch-capable array parameters using resolved targets (attachments / stored pinned targets).
     *
     * <p>This is schema-driven and domain-agnostic: for an action that exposes an array param marked {@code batchTargets},
     * the framework may populate/expand that array by mapping each resolved target's metadata into the item schema.</p>
     *
     * <p>This is intended as a safety net to align runtime behavior with the extraction contract ("apply to all pinned
     * targets by default") while remaining explicit and observable through the confirmation message.</p>
     */
    Map<String, Object> applyBatchTargetsDefaulting(AIActionMetaData meta,
                                                    Map<String, Object> effectiveParams,
                                                    PipelineContext pipelineContext) {
        BatchParamSpec batchSpec = findBatchParamSpec(meta);
        if (batchSpec == null || !StringUtils.hasText(batchSpec.paramName())) {
            return effectiveParams;
        }
        if (pipelineContext == null || pipelineContext.getResolvedTargets() == null || pipelineContext.getResolvedTargets().isEmpty()) {
            return effectiveParams;
        }

        AIActionParamSchema schema = batchSpec.schema();
        AIActionParamSchema itemSchema = schema != null ? schema.getItems() : null;
        Map<String, AIActionParamSchema> props = itemSchema != null ? itemSchema.getProperties() : null;
        if (props == null || props.isEmpty()) {
            return effectiveParams;
        }

        Map<String, Object> params = effectiveParams != null ? effectiveParams : new LinkedHashMap<>();
        Object rawExisting = params.get(batchSpec.paramName());
        List<Object> rawExistingList = coerceToObjectList(rawExisting);
        List<Object> existing = new ArrayList<>();

        java.util.Set<String> existingKeys = new java.util.HashSet<>();
        for (Object element : rawExistingList) {
            Map<String, Object> item = normalizeBatchItemAgainstSchema(element, itemSchema);
            if (item != null && !item.isEmpty()) {
                existing.add(item);
                existingKeys.add(buildBatchItemKey(item, props));
            }
        }

        List<Object> merged = new ArrayList<>(existing);
        for (ResolvedTarget target : pipelineContext.getResolvedTargets()) {
            if (target == null) {
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            for (String propName : props.keySet()) {
                if (!StringUtils.hasText(propName)) {
                    continue;
                }
                Object value = null;

                if ("id".equalsIgnoreCase(propName) && StringUtils.hasText(target.getId())) {
                    value = target.getId().trim();
                } else if ("vectorSpace".equalsIgnoreCase(propName) && StringUtils.hasText(target.getVectorSpace())) {
                    value = target.getVectorSpace().trim();
                } else if (target.getMetadata() != null && !target.getMetadata().isEmpty()) {
                    String metaValue = getMetadataValueIgnoreCase(target.getMetadata(), propName);
                    if (StringUtils.hasText(metaValue)) {
                        value = metaValue.trim();
                    }
                }

                AIActionParamSchema propSchema = props.get(propName);
                if (value == null && propSchema != null && propSchema.getDefaultValue() != null) {
                    value = propSchema.getDefaultValue();
                }

                Object normalizedValue = normalizeBatchValueAgainstSchema(value, propSchema);
                if (normalizedValue != null) {
                    item.put(propName, normalizedValue);
                }
            }

            if (item.isEmpty() || missingRequiredBatchItemProperties(item, itemSchema)) {
                continue;
            }

            String key = buildBatchItemKey(item, props);
            if (existingKeys.add(key)) {
                merged.add(Collections.unmodifiableMap(item));
            }
        }

        if (merged.isEmpty()) {
            if (rawExisting != null) {
                if (hasConfiguredParamResolver(schema)) {
                    return params;
                }
                Map<String, Object> updated = new LinkedHashMap<>(params);
                updated.remove(batchSpec.paramName());
                return updated;
            }
            return params;
        }

        if (merged.equals(rawExistingList)) {
            return params;
        }

        Map<String, Object> updated = new LinkedHashMap<>(params);
        updated.put(batchSpec.paramName(), Collections.unmodifiableList(merged));
        return updated;
    }

    /**
     * If the model emits multiple ACTION intents for the same action, but that action exposes a batch-capable
     * array parameter (marked with {@code [batchTargets]} in the paramsSchema), coalesce them into a single
     * ACTION intent by concatenating the batch parameter list.
     *
     * <p>This is schema-driven and domain-agnostic. It reduces multi-confirmation loops and aligns with the
     * "true batch schema" contract for actions with array item parameters.</p>
     */
    MultiIntentResponse coalesceBatchActionIntents(MultiIntentResponse response) {
        if (response == null || response.getIntents() == null || response.getIntents().size() < 2) {
            return response;
        }

        List<Intent> intents = response.getIntents();
        Map<String, List<Integer>> indicesByAction = new LinkedHashMap<>();
        Map<String, String> actionNameByKey = new LinkedHashMap<>();

        for (int i = 0; i < intents.size(); i++) {
            Intent intent = intents.get(i);
            if (intent == null || intent.getType() != IntentType.ACTION) {
                continue;
            }
            String actionName = resolveActionName(intent);
            if (!StringUtils.hasText(actionName)) {
                continue;
            }
            AIActionMetaData meta = getMetadataForAction(actionName);
            BatchParamSpec batchSpec = findBatchParamSpec(meta);
            if (batchSpec == null) {
                continue;
            }

            String key = actionName.trim().toLowerCase(java.util.Locale.ROOT);
            indicesByAction.computeIfAbsent(key, ignored -> new ArrayList<>()).add(i);
            actionNameByKey.putIfAbsent(key, actionName);
        }

        if (indicesByAction.isEmpty()) {
            return response;
        }

        Map<Integer, Intent> mergedAtIndex = new LinkedHashMap<>();
        java.util.Set<Integer> remove = new java.util.HashSet<>();

        for (Map.Entry<String, List<Integer>> entry : indicesByAction.entrySet()) {
            List<Integer> indices = entry.getValue();
            if (indices == null || indices.size() < 2) {
                continue;
            }
            String actionName = actionNameByKey.get(entry.getKey());
            AIActionMetaData meta = getMetadataForAction(actionName);
            BatchParamSpec batchSpec = findBatchParamSpec(meta);
            if (batchSpec == null) {
                continue;
            }

            int firstIndex = indices.getFirst();
            Intent first = intents.get(firstIndex);
            if (first == null) {
                continue;
            }

            List<Object> combined = new ArrayList<>();
            for (Integer idx : indices) {
                if (idx == null) {
                    continue;
                }
                Intent it = intents.get(idx);
                if (it == null) {
                    continue;
                }
                Map<String, Object> params = it.getActionParams() != null ? it.getActionParams() : Map.of();
                Object raw = params.get(batchSpec.paramName());
                List<Object> list = coerceToObjectList(raw);
                if (!list.isEmpty()) {
                    combined.addAll(list);
                }
            }

            List<Object> deduped = dedupeListElements(combined);
            if (deduped.isEmpty()) {
                continue;
            }

            Map<String, Object> mergedParams = new LinkedHashMap<>(first.getActionParams() != null ? first.getActionParams() : Map.of());
            mergedParams.put(batchSpec.paramName(), deduped);

            Intent merged = Intent.builder()
                .type(IntentType.ACTION)
                .intent(first.getIntent())
                .confidence(first.getConfidence())
                .action(actionName)
                .actionParams(Collections.unmodifiableMap(mergedParams))
                .vectorSpace(first.getVectorSpace())
                .requiresRetrieval(first.getRequiresRetrieval())
                .requiresGeneration(first.getRequiresGeneration())
                .requiresTargetResolution(first.getRequiresTargetResolution())
                .directAnswer(first.getDirectAnswer())
                .generationInstructions(first.getGenerationInstructions())
                .needsAdvancedRAG(first.getNeedsAdvancedRAG())
                .optimizedQuery(first.getOptimizedQuery())
                .nextStepRecommended(first.getNextStepRecommended())
                .build();

            mergedAtIndex.put(firstIndex, merged);
            for (Integer idx : indices) {
                if (idx != null && idx != firstIndex) {
                    remove.add(idx);
                }
            }
        }

        if (mergedAtIndex.isEmpty() || remove.isEmpty()) {
            return response;
        }

        List<Intent> out = new ArrayList<>();
        for (int i = 0; i < intents.size(); i++) {
            if (remove.contains(i)) {
                continue;
            }
            Intent replacement = mergedAtIndex.get(i);
            out.add(replacement != null ? replacement : intents.get(i));
        }

        return MultiIntentResponse.builder()
            .intents(out)
            .orchestrationStrategy(response.getOrchestrationStrategy())
            .metadata(response.getMetadata() != null ? response.getMetadata() : Map.of())
            .build();
    }

    static Map<String, Object> normalizeBatchItemAgainstSchema(Object element,
                                                               AIActionParamSchema itemSchema) {
        if (!(element instanceof Map<?, ?> raw) || itemSchema == null || itemSchema.getProperties() == null
            || itemSchema.getProperties().isEmpty()) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        for (Map.Entry<String, AIActionParamSchema> entry : itemSchema.getProperties().entrySet()) {
            if (entry == null || !StringUtils.hasText(entry.getKey())) {
                continue;
            }
            Object value = mapValueForSchemaProperty(raw, entry.getKey(), entry.getValue());
            if (!hasMeaningfulBatchValue(value)
                && entry.getValue() != null
                && entry.getValue().getDefaultValue() != null) {
                value = entry.getValue().getDefaultValue();
            }
            Object normalized = normalizeBatchValueAgainstSchema(value, entry.getValue());
            if (normalized != null) {
                item.put(entry.getKey().trim(), normalized);
            }
        }
        if (item.isEmpty() || missingRequiredBatchItemProperties(item, itemSchema)) {
            return null;
        }
        return Collections.unmodifiableMap(item);
    }

    static boolean hasMeaningfulBatchValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof CharSequence text) {
            return StringUtils.hasText(text.toString());
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(ActionBatchSupport::hasMeaningfulBatchValue);
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (hasMeaningfulBatchValue(item)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    static BatchParamSpec findBatchParamSpec(AIActionMetaData meta) {
        if (meta == null || meta.getParameterSchemas() == null || meta.getParameterSchemas().isEmpty()) {
            return null;
        }
        for (Map.Entry<String, AIActionParamSchema> entry : meta.getParameterSchemas().entrySet()) {
            if (entry == null || !StringUtils.hasText(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            AIActionParamSchema schema = entry.getValue();
            if (!Boolean.TRUE.equals(schema.getBatchTargets())) {
                continue;
            }
            if (schema.getType() != AIActionParamType.ARRAY) {
                continue;
            }
            if (schema.getItems() == null) {
                continue;
            }
            return new BatchParamSpec(entry.getKey(), schema);
        }
        return null;
    }

    static List<Object> coerceToObjectList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    out.add(item);
                }
            }
            return out;
        }
        if (raw instanceof Map<?, ?> map) {
            return List.of(map);
        }
        return List.of();
    }

    record BatchParamSpec(String paramName, AIActionParamSchema schema) {}

    private static boolean hasConfiguredParamResolver(AIActionParamSchema schema) {
        return schema != null && schema.getResolveFrom() != null && !schema.getResolveFrom().isEmpty();
    }

    private static Object mapValueForSchemaProperty(Map<?, ?> raw, String propName, AIActionParamSchema propSchema) {
        if (raw == null || raw.isEmpty() || !StringUtils.hasText(propName)) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        if (propSchema != null && propSchema.getEvidenceKeys() != null) {
            candidates.addAll(propSchema.getEvidenceKeys());
        }
        candidates.addAll(attachmentContextCandidateKeys(propName));
        for (String candidate : candidates) {
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry == null || entry.getKey() == null || !StringUtils.hasText(entry.getKey().toString())) {
                    continue;
                }
                if (entry.getKey().toString().trim().equalsIgnoreCase(candidate)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static Object normalizeBatchValueAgainstSchema(Object value, AIActionParamSchema schema) {
        if (!hasMeaningfulBatchValue(value)) {
            return null;
        }
        if (schema == null || schema.getType() == null) {
            return value;
        }
        return switch (schema.getType()) {
            case STRING -> normalizeBatchStringValue(value, schema);
            case INTEGER -> normalizeBatchIntegerValue(value, schema);
            case NUMBER -> normalizeBatchNumberValue(value, schema);
            case BOOLEAN -> normalizeBatchBooleanValue(value);
            default -> value;
        };
    }

    private static Object normalizeBatchStringValue(Object value, AIActionParamSchema schema) {
        String normalized = value != null ? value.toString().trim() : null;
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        if (schema.getAllowedValues() != null && !schema.getAllowedValues().isEmpty()
            && schema.getAllowedValues().stream().noneMatch(allowed -> normalized.equals(allowed))) {
            return null;
        }
        if (StringUtils.hasText(schema.getPattern())) {
            try {
                if (!Pattern.compile(schema.getPattern()).matcher(normalized).matches()) {
                    return null;
                }
            } catch (PatternSyntaxException ex) {
                log.debug("Ignoring invalid batch parameter pattern for '{}': {}", schema.getName(), ex.getMessage());
                return null;
            }
        }
        return normalized;
    }

    private static Object normalizeBatchIntegerValue(Object value, AIActionParamSchema schema) {
        Long parsed = parseLong(value);
        if (parsed == null || !withinNumericBounds(parsed.doubleValue(), schema)) {
            return null;
        }
        return parsed;
    }

    private static Object normalizeBatchNumberValue(Object value, AIActionParamSchema schema) {
        Double parsed = parseDouble(value);
        if (parsed == null || !withinNumericBounds(parsed, schema)) {
            return null;
        }
        return parsed;
    }

    private static Object normalizeBatchBooleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof CharSequence text) {
            String normalized = text.toString().trim();
            if ("true".equalsIgnoreCase(normalized)) {
                return true;
            }
            if ("false".equalsIgnoreCase(normalized)) {
                return false;
            }
        }
        return null;
    }

    private static Long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof CharSequence text && StringUtils.hasText(text.toString())) {
            try {
                return Long.parseLong(text.toString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Double parseDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof CharSequence text && StringUtils.hasText(text.toString())) {
            try {
                return Double.parseDouble(text.toString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean withinNumericBounds(double value, AIActionParamSchema schema) {
        if (schema == null) {
            return true;
        }
        if (schema.getMin() != null && value < schema.getMin()) {
            return false;
        }
        return schema.getMax() == null || value <= schema.getMax();
    }

    private static boolean missingRequiredBatchItemProperties(Map<String, Object> item, AIActionParamSchema itemSchema) {
        if (item == null || itemSchema == null || itemSchema.getRequiredProperties() == null
            || itemSchema.getRequiredProperties().isEmpty()) {
            return false;
        }
        for (String required : itemSchema.getRequiredProperties()) {
            if (!StringUtils.hasText(required)) {
                continue;
            }
            if (!hasMeaningfulBatchValue(item.get(required.trim()))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> attachmentContextCandidateKeys(String required) {
        String normalized = required.trim();
        List<String> keys = new ArrayList<>();
        keys.add(normalized);
        String camel = snakeToCamel(normalized);
        if (!camel.equals(normalized)) {
            keys.add(camel);
        }
        if (normalized.endsWith("_id")) {
            String base = normalized.substring(0, normalized.length() - "_id".length());
            String baseCamel = snakeToCamel(base);
            keys.add(baseCamel + "Id");
            keys.add(baseCamel + "ID");
            keys.add(baseCamel + "Gid");
        }
        return keys.stream()
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    private static String snakeToCamel(String value) {
        if (!StringUtils.hasText(value) || !value.contains("_")) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        boolean upperNext = false;
        for (char ch : value.toCharArray()) {
            if (ch == '_') {
                upperNext = true;
                continue;
            }
            sb.append(upperNext ? Character.toUpperCase(ch) : ch);
            upperNext = false;
        }
        return sb.toString();
    }

    private static String getMetadataValueIgnoreCase(Map<String, String> metadata, String key) {
        if (metadata == null || metadata.isEmpty() || !StringUtils.hasText(key)) {
            return null;
        }
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (entry == null || !StringUtils.hasText(entry.getKey())) {
                continue;
            }
            if (entry.getKey().trim().equalsIgnoreCase(key.trim())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String buildBatchItemKey(Map<String, Object> item, Map<String, AIActionParamSchema> props) {
        if (item == null || item.isEmpty()) {
            return "";
        }
        if (props == null || props.isEmpty()) {
            return item.toString();
        }
        StringBuilder sb = new StringBuilder(64);
        for (String prop : props.keySet()) {
            if (!StringUtils.hasText(prop)) {
                continue;
            }
            Object value = item.get(prop);
            if (value == null) {
                continue;
            }
            sb.append(prop.trim().toLowerCase(java.util.Locale.ROOT)).append('=')
                .append(value.toString().trim().toLowerCase(java.util.Locale.ROOT)).append(';');
        }
        String key = sb.toString();
        return StringUtils.hasText(key) ? key : item.toString();
    }

    private String resolveActionName(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (StringUtils.hasText(intent.getAction())) {
            return intent.getAction().trim();
        }
        if (StringUtils.hasText(intent.getIntent())) {
            return intent.getIntent().trim();
        }
        return null;
    }

    private List<Object> dedupeListElements(List<Object> combined) {
        if (combined == null || combined.isEmpty()) {
            return List.of();
        }
        List<Object> out = new ArrayList<>();
        java.util.Set<Object> seen = new java.util.HashSet<>();
        for (Object item : combined) {
            if (item == null) {
                continue;
            }
            if (seen.add(item)) {
                out.add(item);
            }
        }
        return out;
    }

    private AIActionMetaData getMetadataForAction(String actionName) {
        try {
            Optional<AIActionMetaData> optional = actionHandlerRegistry.findMetadata(actionName);
            return optional != null ? optional.orElse(null) : null;
        } catch (Exception ex) {
            log.debug("Unable to resolve metadata for action {}: {}", actionName, ex.getMessage());
            return null;
        }
    }
}
