package ai.fabric.intent.action.connector;

import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link AIActionHandler} implementation for connector-backed actions loaded from a file-based catalog.
 */
@Slf4j
public final class ConnectorAIActionHandler implements AIActionHandler {

    private static final String REDACTED = "[REDACTED]";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)(?:\\s*\\|\\s*([^{}]*?))?\\s*}}");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int FALLBACK_MAX_RECORDS = 5;
    private static final int FALLBACK_MAX_SCALAR_FIELDS = 16;
    private static final int FALLBACK_MAX_CONTENT_CHARS = 300;

    private final AIActionMetaData metadata;
    private final boolean requiresConfirmation;
    private final String confirmationTemplate;
    private final Set<String> sensitiveParams;
    private final ActionConnectorExecutor executor;
    private final ConnectorActionLlmFactsDefinition llmFacts;
    private final Map<String, Object> actionConfig;

    public ConnectorAIActionHandler(AIActionMetaData metadata,
                                    boolean requiresConfirmation,
                                    String confirmationTemplate,
                                    Set<String> sensitiveParams,
                                    ActionConnectorExecutor executor) {
        this(metadata, requiresConfirmation, confirmationTemplate, sensitiveParams, executor, null);
    }

    public ConnectorAIActionHandler(AIActionMetaData metadata,
                                    boolean requiresConfirmation,
                                    String confirmationTemplate,
                                    Set<String> sensitiveParams,
                                    ActionConnectorExecutor executor,
                                    ConnectorActionLlmFactsDefinition llmFacts) {
        this(metadata, requiresConfirmation, confirmationTemplate, sensitiveParams, executor, llmFacts, null);
    }

    public ConnectorAIActionHandler(AIActionMetaData metadata,
                                    boolean requiresConfirmation,
                                    String confirmationTemplate,
                                    Set<String> sensitiveParams,
                                    ActionConnectorExecutor executor,
                                    ConnectorActionLlmFactsDefinition llmFacts,
                                    Map<String, Object> actionConfig) {
        this.metadata = metadata;
        this.requiresConfirmation = requiresConfirmation;
        this.confirmationTemplate = StringUtils.hasText(confirmationTemplate) ? confirmationTemplate.trim() : null;
        this.sensitiveParams = sensitiveParams != null ? Set.copyOf(sensitiveParams) : Set.of();
        this.executor = executor;
        this.llmFacts = llmFacts;
        this.actionConfig = actionConfig != null && !actionConfig.isEmpty()
            ? Map.copyOf(actionConfig)
            : Map.of();
    }

    @Override
    public AIActionMetaData getActionMetadata() {
        return metadata;
    }

    @Override
    public boolean requiresConfirmation() {
        return requiresConfirmation;
    }

    @Override
    public String getConfirmationMessage(Map<String, Object> params, ActionContext context) {
        if (!StringUtils.hasText(confirmationTemplate)) {
            return defaultConfirmationMessage(params);
        }
        try {
            return renderTemplate(confirmationTemplate, params != null ? params : Map.of());
        } catch (Exception ex) {
            log.debug("Failed to render confirmation template for action {}: {}", metadata != null ? metadata.getName() : "unknown", ex.getMessage());
            return defaultConfirmationMessage(params);
        }
    }

    @Override
    public ActionResult executeAction(Map<String, Object> params, ActionContext context) {
        if (executor == null) {
            return ActionResult.builder()
                .success(false)
                .errorCode("SERVICE_UNAVAILABLE")
                .message("Connector executor is not configured.")
                .build();
        }
        String actionId = metadata != null ? metadata.getName() : null;
        if (!StringUtils.hasText(actionId)) {
            return ActionResult.builder()
                .success(false)
                .errorCode("ACTION_EXECUTION_FAILED")
                .message("Action metadata is missing action name.")
                .build();
        }
        return executor.execute(actionId, metadata.getAccessMode(), params != null ? params : Map.of(), context, actionConfig);
    }

    @Override
    public Optional<Map<String, Object>> buildPostActionLlmFacts(ActionResult actionResult, ActionContext context) {
        if (actionResult == null) {
            return Optional.empty();
        }

        Map<String, Object> facts = new LinkedHashMap<>();
        if (metadata != null && StringUtils.hasText(metadata.getName())) {
            facts.put("action", metadata.getName());
        }
        if (metadata != null && StringUtils.hasText(metadata.getCategory())) {
            facts.put("category", metadata.getCategory());
        }
        facts.put("success", actionResult.isSuccess());
        if (StringUtils.hasText(actionResult.getMessage())) {
            facts.put("message", actionResult.getMessage());
        }
        if (StringUtils.hasText(actionResult.getErrorCode())) {
            facts.put("errorCode", actionResult.getErrorCode());
        }

        Map<String, Object> payload = actionResult.getData() != null
            ? actionResult.getData().toMap()
            : Map.of();
        Map<String, Object> rootPayload = selectFactsRoot(payload);
        if (llmFacts != null && llmFacts.configured()) {
            applyConfiguredFacts(facts, rootPayload, context);
        } else {
            applyFallbackFacts(facts, payload, rootPayload);
        }
        return Optional.of(Collections.unmodifiableMap(facts));
    }

    @Override
    public Map<String, Object> actionRuntimeConfig() {
        return actionConfig;
    }

    private void applyConfiguredFacts(Map<String, Object> facts, Map<String, Object> rootPayload, ActionContext context) {
        if (rootPayload == null || rootPayload.isEmpty()) {
            return;
        }
        for (String field : llmFacts.copyFields()) {
            copyPath(rootPayload, facts, field);
        }
        Map<String, Object> actionParams = context != null ? context.actionParams() : Map.of();
        for (ConnectorActionLlmFactsListDefinition listDefinition : llmFacts.lists()) {
            putConfiguredList(facts, rootPayload, listDefinition, actionParams);
        }
        for (ConnectorActionLlmFactsObjectDefinition objectDefinition : llmFacts.objects()) {
            putConfiguredObject(facts, rootPayload, objectDefinition);
        }
    }

    private void applyFallbackFacts(Map<String, Object> facts,
                                    Map<String, Object> payload,
                                    Map<String, Object> rootPayload) {
        copyCommonTopLevelFields(rootPayload, facts);
        if (rootPayload != payload) {
            copyCommonTopLevelFields(payload, facts);
        }
        boolean hasPrimaryList = putFallbackList(facts, "documents", rootPayload.get("documents"));
        if (!hasPrimaryList) {
            hasPrimaryList = putFallbackList(facts, "results", rootPayload.get("results"));
        }
        if (!hasPrimaryList) {
            hasPrimaryList = putFallbackList(facts, "items", rootPayload.get("items"));
        }
        if (!hasPrimaryList) {
            putMcpToolContentFacts(facts, rootPayload);
            if (rootPayload != payload) {
                putMcpToolContentFacts(facts, payload);
            }
        }
        if (!hasPrimaryList && supportsObjectFallbackFacts()
            && !facts.containsKey("documents") && !facts.containsKey("record")) {
            putFallbackObjectFacts(facts, rootPayload);
        }
    }

    private boolean supportsObjectFallbackFacts() {
        if (metadata == null || metadata.getAccessMode() == null) {
            return false;
        }
        return metadata.getAccessMode() != ai.fabric.intent.action.ActionAccessMode.WRITE_ONLY;
    }

    private void putConfiguredList(Map<String, Object> facts,
                                   Map<String, Object> rootPayload,
                                   ConnectorActionLlmFactsListDefinition listDefinition,
                                   Map<String, Object> actionParams) {
        if (listDefinition == null || !StringUtils.hasText(listDefinition.sourcePath())
            || !StringUtils.hasText(listDefinition.target())) {
            return;
        }
        Object rawValue = resolvePath(rootPayload, listDefinition.sourcePath());
        List<Map<String, Object>> records = compactRecords(rawValue, listDefinition, actionParams);
        if (records.isEmpty()) {
            return;
        }

        List<Map<String, Object>> constraintMatches = filterConstraintMatches(records, listDefinition.constraints(), actionParams);
        if (!constraintMatches.isEmpty() && listDefinition.constraints() != null) {
            facts.put(listDefinition.constraints().target(), compactConstraintMatches(constraintMatches, listDefinition.constraints()));
            String countTarget = firstNonBlank(
                listDefinition.constraints().countTarget(),
                listDefinition.constraints().target() + "Count"
            );
            facts.put(countTarget, constraintMatches.size());
        }
        for (ConnectorActionLlmFactsSummaryDefinition summaryDefinition : listDefinition.summaries()) {
            List<Map<String, Object>> source = "CONSTRAINT_MATCHES".equalsIgnoreCase(firstNonBlank(summaryDefinition.source(), "ALL"))
                ? constraintMatches
                : records;
            Map<String, Object> summary = buildNumericRangeSummary(source, summaryDefinition);
            if (!summary.isEmpty()) {
                facts.put(summaryDefinition.target(), summary);
            }
        }
        facts.put(listDefinition.target(), records);
        facts.put(listDefinition.target() + "Count", records.size());
    }

    private void putConfiguredObject(Map<String, Object> facts,
                                     Map<String, Object> rootPayload,
                                     ConnectorActionLlmFactsObjectDefinition objectDefinition) {
        if (objectDefinition == null || !StringUtils.hasText(objectDefinition.sourcePath())
            || !StringUtils.hasText(objectDefinition.target())) {
            return;
        }
        Object rawValue = resolvePath(rootPayload, objectDefinition.sourcePath());
        if (!(rawValue instanceof Map<?, ?> rawMap)) {
            return;
        }
        Map<String, Object> compact = compactConfiguredObject(
            rawMap,
            objectDefinition.includeFields(),
            objectDefinition.fallbackContentField(),
            objectDefinition.fallbackContentMaxChars()
        );
        if (!compact.isEmpty()) {
            facts.put(objectDefinition.target(), Collections.unmodifiableMap(compact));
        }
    }

    private boolean putFallbackList(Map<String, Object> facts,
                                    String target,
                                    Object value) {
        List<Map<String, Object>> records = compactFallbackRecords(value);
        if (records.isEmpty()) {
            return false;
        }
        facts.put(target, records);
        facts.put(target + "Count", records.size());
        return true;
    }

    private boolean putFallbackObjectFacts(Map<String, Object> facts, Map<String, Object> payload) {
        if (facts == null || payload == null || payload.isEmpty()) {
            return false;
        }
        Map<String, Object> record = compactFallbackRecord(payload);
        if (record.isEmpty()) {
            return false;
        }
        facts.put("record", Collections.unmodifiableMap(record));
        facts.put("recordCount", 1);
        return true;
    }

    private boolean putMcpToolContentFacts(Map<String, Object> facts, Map<String, Object> payload) {
        if (facts == null || facts.containsKey("documents") || payload == null || payload.isEmpty()) {
            return false;
        }
        Object rawToolResult = mapObject(payload, "toolResult");
        if (!(rawToolResult instanceof Map<?, ?> toolResult)) {
            return false;
        }
        Object rawContent = mapObject(toolResult, "content");
        if (!(rawContent instanceof List<?> contentItems) || contentItems.isEmpty()) {
            return false;
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (Object item : contentItems) {
            if (records.size() >= FALLBACK_MAX_RECORDS) {
                break;
            }
            if (item instanceof Map<?, ?> itemMap) {
                String text = asString(mapObject(itemMap, "text"));
                List<Map<String, Object>> parsedRecords = compactJsonTextRecords(text);
                if (!parsedRecords.isEmpty()) {
                    appendLimited(records, parsedRecords);
                    continue;
                }
                Map<String, Object> compact = compactFallbackRecord(itemMap);
                if (!compact.isEmpty()) {
                    records.add(Collections.unmodifiableMap(compact));
                }
            } else if (item != null) {
                Map<String, Object> compact = new LinkedHashMap<>();
                putIfPresent(compact, "content", truncate(asString(item), FALLBACK_MAX_CONTENT_CHARS));
                if (!compact.isEmpty()) {
                    records.add(Collections.unmodifiableMap(compact));
                }
            }
        }
        if (records.isEmpty()) {
            return false;
        }
        List<Map<String, Object>> limited = limit(records, FALLBACK_MAX_RECORDS);
        facts.put("documents", limited);
        facts.put("documentsCount", limited.size());
        return true;
    }

    private void appendLimited(List<Map<String, Object>> target, List<Map<String, Object>> records) {
        if (target == null || records == null || records.isEmpty()) {
            return;
        }
        for (Map<String, Object> record : records) {
            if (target.size() >= FALLBACK_MAX_RECORDS) {
                return;
            }
            if (record != null && !record.isEmpty()) {
                target.add(record);
            }
        }
    }

    private Map<String, Object> selectFactsRoot(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        if (llmFacts != null && StringUtils.hasText(llmFacts.rootPath())) {
            Object configuredRoot = resolvePath(payload, llmFacts.rootPath());
            if (configuredRoot instanceof Map<?, ?> map) {
                return toStringObjectMap(map);
            }
            if (configuredRoot instanceof CharSequence text) {
                return parseJsonObject(text.toString());
            }
            return Map.of();
        }
        Object nested = mapObject(payload, "data");
        return nested instanceof Map<?, ?> nestedMap ? toStringObjectMap(nestedMap) : payload;
    }

    private List<Map<String, Object>> compactRecords(Object value,
                                                     ConnectorActionLlmFactsListDefinition listDefinition,
                                                     Map<String, Object> actionParams) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> compact = compactConfiguredObject(
                    map,
                    listDefinition.includeFields(),
                    listDefinition.fallbackContentField(),
                    listDefinition.fallbackContentMaxChars()
                );
                if (!compact.isEmpty()) {
                    out.add(Collections.unmodifiableMap(compact));
                }
            }
        }
        sortConfiguredRecords(out, listDefinition.rankRules(), actionParams);
        return limit(out, listDefinition.maxItems());
    }

    private List<Map<String, Object>> compactFallbackRecords(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> compact = compactFallbackRecord(map);
                if (!compact.isEmpty()) {
                    out.add(Collections.unmodifiableMap(compact));
                }
            }
        }
        return limit(out, FALLBACK_MAX_RECORDS);
    }

    private Map<String, Object> compactConfiguredObject(Map<?, ?> record,
                                                        List<String> includeFields,
                                                        String fallbackContentField,
                                                        int fallbackContentMaxChars) {
        Map<String, Object> compact = new LinkedHashMap<>();
        if (includeFields != null) {
            for (String field : includeFields) {
                copyPath(record, compact, field);
            }
        }
        if (compact.isEmpty() && StringUtils.hasText(fallbackContentField)) {
            putIfPresent(compact, leafKey(fallbackContentField), truncate(asString(resolvePath(record, fallbackContentField)), fallbackContentMaxChars));
        }
        return compact;
    }

    private Map<String, Object> compactFallbackRecord(Map<?, ?> record) {
        Map<String, Object> compact = new LinkedHashMap<>();
        copyFallbackScalars(record, compact);
        if (!compact.containsKey("content")) {
            Object content = mapObject(record, "content");
            putIfPresent(compact, "content", truncate(asString(content), FALLBACK_MAX_CONTENT_CHARS));
        }
        return compact;
    }

    private List<Map<String, Object>> compactJsonTextRecords(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) {
            return List.of();
        }
        try {
            Object decoded = JSON.readValue(trimmed, Object.class);
            if (decoded instanceof List<?> list) {
                return compactFallbackRecords(list);
            }
            if (decoded instanceof Map<?, ?> map) {
                List<Map<String, Object>> nested = firstNonEmptyFallbackList(
                    mapObject(map, "documents"),
                    mapObject(map, "results"),
                    mapObject(map, "items"),
                    mapObject(map, "content")
                );
                if (!nested.isEmpty()) {
                    return nested;
                }
                Map<String, Object> compact = compactFallbackRecord(map);
                return compact.isEmpty() ? List.of() : List.of(Collections.unmodifiableMap(compact));
            }
            return List.of();
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private List<Map<String, Object>> firstNonEmptyFallbackList(Object... candidates) {
        if (candidates == null) {
            return List.of();
        }
        for (Object candidate : candidates) {
            List<Map<String, Object>> records = compactFallbackRecords(candidate);
            if (!records.isEmpty()) {
                return records;
            }
        }
        return List.of();
    }

    private void copyFallbackScalars(Map<?, ?> source, Map<String, Object> target) {
        if (source == null || source.isEmpty() || target.size() >= FALLBACK_MAX_SCALAR_FIELDS) {
            return;
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry == null || entry.getKey() == null || target.size() >= FALLBACK_MAX_SCALAR_FIELDS) {
                continue;
            }
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (isSafeScalar(value)) {
                putIfPresent(target, key, truncateScalar(value));
            } else if (value instanceof Map<?, ?> nested) {
                copyNestedFallbackScalars(nested, target);
            }
        }
    }

    private void copyNestedFallbackScalars(Map<?, ?> source, Map<String, Object> target) {
        if (source == null || source.isEmpty() || target.size() >= FALLBACK_MAX_SCALAR_FIELDS) {
            return;
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry == null || entry.getKey() == null || target.size() >= FALLBACK_MAX_SCALAR_FIELDS) {
                continue;
            }
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (isSafeScalar(value) && !target.containsKey(key)) {
                putIfPresent(target, key, truncateScalar(value));
            }
        }
    }

    private List<Map<String, Object>> filterConstraintMatches(List<Map<String, Object>> records,
                                                              ConnectorActionLlmFactsConstraintDefinition constraints,
                                                              Map<String, Object> actionParams) {
        if (records == null || records.isEmpty() || constraints == null || constraints.rules().isEmpty()) {
            return List.of();
        }
        List<ConnectorActionLlmFactsRuleDefinition> activeRules = constraints.rules().stream()
            .filter(rule -> ruleActive(rule, actionParams))
            .toList();
        if (activeRules.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map<String, Object> record : records) {
            boolean matched = true;
            for (ConnectorActionLlmFactsRuleDefinition rule : activeRules) {
                if (!ruleMatches(rule, record, actionParams)) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                matches.add(record);
            }
        }
        return matches.isEmpty() ? List.of() : List.copyOf(matches);
    }

    private List<Map<String, Object>> compactConstraintMatches(List<Map<String, Object>> records,
                                                               ConnectorActionLlmFactsConstraintDefinition constraints) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> record : records) {
            Map<String, Object> compact = constraints.includeFields().isEmpty()
                ? new LinkedHashMap<>(record)
                : compactConfiguredObject(record, constraints.includeFields(), null, FALLBACK_MAX_CONTENT_CHARS);
            if (!compact.isEmpty()) {
                out.add(Collections.unmodifiableMap(compact));
            }
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private Map<String, Object> buildNumericRangeSummary(List<Map<String, Object>> records,
                                                         ConnectorActionLlmFactsSummaryDefinition definition) {
        if (records == null || records.isEmpty() || definition == null || !StringUtils.hasText(definition.field())
            || !StringUtils.hasText(definition.target())) {
            return Map.of();
        }
        Map<String, Object> lowest = null;
        Map<String, Object> highest = null;
        Double lowestValue = null;
        Double highestValue = null;
        int valueRecords = 0;

        for (Map<String, Object> record : records) {
            Double value = numericValue(resolvePath(record, definition.field()));
            if (value == null) {
                continue;
            }
            valueRecords++;
            if (lowestValue == null || value < lowestValue) {
                lowestValue = value;
                lowest = record;
            }
            if (highestValue == null || value > highestValue) {
                highestValue = value;
                highest = record;
            }
        }
        if (valueRecords == 0) {
            return Map.of();
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put(firstNonBlank(definition.recordCountKey(), "valueRecords"), valueRecords);
        putSummaryEndpoint(summary, "lowest", lowest, lowestValue, definition);
        putSummaryEndpoint(summary, "highest", highest, highestValue, definition);
        return Collections.unmodifiableMap(summary);
    }

    private void putSummaryEndpoint(Map<String, Object> summary,
                                    String prefix,
                                    Map<String, Object> record,
                                    Double value,
                                    ConnectorActionLlmFactsSummaryDefinition definition) {
        if (summary == null || record == null || value == null || definition == null) {
            return;
        }
        boolean lowest = "lowest".equals(prefix);
        String valueKey = lowest
            ? firstNonBlank(definition.lowestValueKey(), "lowestValue")
            : firstNonBlank(definition.highestValueKey(), "highestValue");
        summary.put(valueKey, value);
        if (StringUtils.hasText(definition.labelField())) {
            Object label = resolvePath(record, definition.labelField());
            String labelKey = lowest
                ? firstNonBlank(definition.lowestLabelKey(), "lowestLabel")
                : firstNonBlank(definition.highestLabelKey(), "highestLabel");
            putIfPresent(summary, labelKey, label);
        }
        for (ConnectorActionLlmFactsSummaryExtraFieldDefinition extraField : definition.extraFields()) {
            if (extraField == null || !StringUtils.hasText(extraField.field())) {
                continue;
            }
            String targetKey = lowest ? extraField.lowestKey() : extraField.highestKey();
            if (StringUtils.hasText(targetKey)) {
                putIfPresent(summary, targetKey, resolvePath(record, extraField.field()));
            }
        }
    }

    private void sortConfiguredRecords(List<Map<String, Object>> records,
                                       List<ConnectorActionLlmFactsRuleDefinition> rules,
                                       Map<String, Object> actionParams) {
        if (records == null || records.size() < 2) {
            return;
        }
        records.sort((left, right) -> {
            int scoreCompare = Integer.compare(
                configuredRelevanceScore(right, rules, actionParams),
                configuredRelevanceScore(left, rules, actionParams)
            );
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            ConnectorActionLlmFactsRuleDefinition ascendingRule = firstAscendingNumericRule(rules, actionParams);
            if (ascendingRule != null) {
                return Double.compare(
                    numericOrMax(resolvePath(left, ascendingRule.field())),
                    numericOrMax(resolvePath(right, ascendingRule.field()))
                );
            }
            return 0;
        });
    }

    private int configuredRelevanceScore(Map<String, Object> record,
                                         List<ConnectorActionLlmFactsRuleDefinition> rules,
                                         Map<String, Object> actionParams) {
        int score = 0;
        if (rules == null || rules.isEmpty()) {
            return score;
        }
        for (ConnectorActionLlmFactsRuleDefinition rule : rules) {
            score += ruleScore(rule, record, actionParams);
        }
        return score;
    }

    private int ruleScore(ConnectorActionLlmFactsRuleDefinition rule,
                          Map<String, Object> record,
                          Map<String, Object> actionParams) {
        if (rule == null || !StringUtils.hasText(rule.type())) {
            return 0;
        }
        if (!ruleActive(rule, actionParams)) {
            return 0;
        }
        if (ruleMatches(rule, record, actionParams)) {
            return rule.scoreMatch();
        }
        return resolvePath(record, rule.field()) == null ? rule.scoreMissing() : rule.scoreMismatch();
    }

    private boolean ruleActive(ConnectorActionLlmFactsRuleDefinition rule, Map<String, Object> actionParams) {
        if (rule == null || !StringUtils.hasText(rule.type())) {
            return false;
        }
        String type = normalizeRuleType(rule.type());
        Object paramValue = resolvePath(actionParams, rule.paramPath());
        return switch (type) {
            case "PARAM_NUMERIC_UPPER_BOUND", "PARAM_NUMERIC_LOWER_BOUND" -> numericValue(paramValue) != null;
            case "PARAM_BOOLEAN_TRUE" -> Boolean.TRUE.equals(booleanValue(paramValue));
            case "PARAM_BOOLEAN_FALSE" -> Boolean.FALSE.equals(booleanValue(paramValue));
            case "PARAM_EQUALS" -> paramValue != null || rule.value() != null;
            default -> false;
        };
    }

    private boolean ruleMatches(ConnectorActionLlmFactsRuleDefinition rule,
                                Map<String, Object> record,
                                Map<String, Object> actionParams) {
        if (rule == null || !StringUtils.hasText(rule.type())) {
            return false;
        }
        String type = normalizeRuleType(rule.type());
        Object recordValue = resolvePath(record, rule.field());
        Object paramValue = resolvePath(actionParams, rule.paramPath());
        if ("PARAM_NUMERIC_UPPER_BOUND".equals(type)) {
            Double upperBound = numericValue(paramValue);
            Double numeric = numericValue(recordValue);
            return upperBound != null && numeric != null && compareNumeric(numeric, upperBound, firstNonBlank(rule.operator(), "<="));
        }
        if ("PARAM_NUMERIC_LOWER_BOUND".equals(type)) {
            Double lowerBound = numericValue(paramValue);
            Double numeric = numericValue(recordValue);
            return lowerBound != null && numeric != null && compareNumeric(numeric, lowerBound, firstNonBlank(rule.operator(), ">="));
        }
        if ("PARAM_BOOLEAN_TRUE".equals(type)) {
            return Boolean.TRUE.equals(booleanValue(recordValue));
        }
        if ("PARAM_BOOLEAN_FALSE".equals(type)) {
            return Boolean.FALSE.equals(booleanValue(recordValue));
        }
        if ("PARAM_EQUALS".equals(type)) {
            Object expected = rule.value() != null ? rule.value() : paramValue;
            return valuesEqual(recordValue, expected);
        }
        return false;
    }

    private ConnectorActionLlmFactsRuleDefinition firstAscendingNumericRule(List<ConnectorActionLlmFactsRuleDefinition> rules,
                                                                            Map<String, Object> actionParams) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        for (ConnectorActionLlmFactsRuleDefinition rule : rules) {
            if (rule != null
                && rule.sortAscendingOnMatch()
                && "PARAM_NUMERIC_UPPER_BOUND".equals(normalizeRuleType(rule.type()))
                && ruleActive(rule, actionParams)) {
                return rule;
            }
        }
        return null;
    }

    private boolean compareNumeric(double recordValue, double bound, String operator) {
        return switch (firstNonBlank(operator, "<=")) {
            case "<" -> recordValue < bound;
            case ">" -> recordValue > bound;
            case ">=" -> recordValue >= bound;
            default -> recordValue <= bound;
        };
    }

    private boolean valuesEqual(Object recordValue, Object expected) {
        if (recordValue == null || expected == null) {
            return recordValue == expected;
        }
        if (recordValue instanceof Number || expected instanceof Number) {
            Double left = numericValue(recordValue);
            Double right = numericValue(expected);
            return left != null && right != null && Double.compare(left, right) == 0;
        }
        Boolean leftBoolean = booleanValue(recordValue);
        Boolean rightBoolean = booleanValue(expected);
        if (leftBoolean != null || rightBoolean != null) {
            return Objects.equals(leftBoolean, rightBoolean);
        }
        return recordValue.toString().trim().equalsIgnoreCase(expected.toString().trim());
    }

    private void copyCommonTopLevelFields(Map<String, Object> source, Map<String, Object> target) {
        if (source == null || source.isEmpty()) {
            return;
        }
        copyPath(source, target, "query");
        copyPath(source, target, "count");
        copyPath(source, target, "totalResults");
        copyPath(source, target, "returnedResults");
        copyPath(source, target, "lookup");
        copyPath(source, target, "lookupMethod");
    }

    private void copyPath(Map<?, ?> source, Map<String, Object> target, String path) {
        if (source == null || target == null || !StringUtils.hasText(path)) {
            return;
        }
        Object value = resolvePath(source, path);
        putIfPresent(target, leafKey(path), value);
    }

    private Object resolvePath(Map<?, ?> source, String path) {
        if (source == null || !StringUtils.hasText(path)) {
            return null;
        }
        Object current = source;
        String[] parts = path.trim().split("\\.");
        for (String part : parts) {
            if (!StringUtils.hasText(part)) {
                return null;
            }
            String segment = part.trim();
            if (current instanceof Map<?, ?> map) {
                current = mapObject(map, segment);
            } else if (current instanceof List<?> list) {
                Integer index = parseIndex(segment);
                current = index != null && index >= 0 && index < list.size() ? list.get(index) : null;
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private Integer parseIndex(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Map<String, Object> parseJsonObject(String value) {
        if (!StringUtils.hasText(value)) {
            return Map.of();
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("{")) {
            return Map.of();
        }
        try {
            Object decoded = JSON.readValue(trimmed, Object.class);
            return decoded instanceof Map<?, ?> map ? toStringObjectMap(map) : Map.of();
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toStringObjectMap(Map<?, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry != null && entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private Object mapObject(Map<?, ?> source, String key) {
        if (source == null || key == null) {
            return null;
        }
        Object direct = source.get(key);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry != null && entry.getKey() != null && key.equalsIgnoreCase(entry.getKey().toString())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (target == null || !StringUtils.hasText(key) || value == null) {
            return;
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (!trimmed.isEmpty()) {
                target.put(key, trimmed);
            }
            return;
        }
        target.put(key, value);
    }

    private String renderTemplate(String template, Map<String, Object> params) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String fallback = matcher.group(2);
            String replacement = renderValue(name, fallback, params);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String renderValue(String name, String fallback, Map<String, Object> params) {
        if (!StringUtils.hasText(name)) {
            return "";
        }
        String key = name.trim();
        Object raw = params != null ? params.get(key) : null;
        if (raw == null) {
            return renderFallback(fallback);
        }
        if (sensitiveParams.contains(key)) {
            return REDACTED;
        }
        String s = raw.toString();
        if (!StringUtils.hasText(s)) {
            return renderFallback(fallback);
        }
        return escapeHtml(s.trim());
    }

    private String renderFallback(String fallback) {
        if (!StringUtils.hasText(fallback)) {
            return "";
        }
        return escapeHtml(fallback.trim());
    }

    private String defaultConfirmationMessage(Map<String, Object> params) {
        String actionName = metadata != null && StringUtils.hasText(metadata.getName()) ? metadata.getName() : "action";
        if (params == null || params.isEmpty()) {
            return "Confirm " + actionName + "?";
        }

        Map<String, Object> safe = new LinkedHashMap<>(params);
        String joined = safe.entrySet().stream()
            .filter(e -> e.getKey() != null && StringUtils.hasText(e.getKey()))
            .limit(6)
            .map(e -> {
                String k = e.getKey();
                String v = sensitiveParams.contains(k) ? REDACTED : (e.getValue() == null ? "null" : String.valueOf(e.getValue()));
                return k + "=" + v;
            })
            .reduce((a, b) -> a + ", " + b)
            .orElse("");
        return "Confirm " + actionName + " (" + joined + ")?";
    }

    private List<Map<String, Object>> limit(List<Map<String, Object>> records, int maxItems) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(0, maxItems);
        if (records.size() > limit) {
            return List.copyOf(records.subList(0, limit));
        }
        return List.copyOf(records);
    }

    private String leafKey(String path) {
        if (!StringUtils.hasText(path)) {
            return path;
        }
        String normalized = path.trim();
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? null : Objects.toString(value, null);
    }

    private Object truncateScalar(Object value) {
        if (value instanceof String text) {
            return truncate(text, FALLBACK_MAX_CONTENT_CHARS);
        }
        return value;
    }

    private String truncate(String value, int maxChars) {
        if (value == null || maxChars <= 0 || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private boolean isSafeScalar(Object value) {
        return value == null
            || value instanceof String
            || value instanceof Number
            || value instanceof Boolean
            || value instanceof Character
            || value instanceof Enum<?>;
    }

    private Double numericValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        String raw = value.toString().trim();
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String normalized = raw.replaceAll("[^0-9.\\-]", "");
        if (!StringUtils.hasText(normalized) || ".".equals(normalized) || "-".equals(normalized)) {
            return null;
        }
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private double numericOrMax(Object value) {
        Double numeric = numericValue(value);
        return numeric != null ? numeric : Double.MAX_VALUE;
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return null;
        }
        String raw = value.toString().trim();
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        if ("true".equalsIgnoreCase(raw) || "yes".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw) || "no".equalsIgnoreCase(raw)) {
            return false;
        }
        return null;
    }

    private String normalizeRuleType(String type) {
        return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
    }

    private String escapeHtml(String input) {
        if (!StringUtils.hasText(input)) {
            return "";
        }
        String s = input;
        s = s.replace("&", "&amp;");
        s = s.replace("<", "&lt;");
        s = s.replace(">", "&gt;");
        s = s.replace("\"", "&quot;");
        s = s.replace("'", "&#39;");
        // Normalize newlines to avoid UI injection tricks
        s = s.replace("\r\n", "\n").replace("\r", "\n");
        // Keep confirmation messages readable and bounded
        int max = 200;
        if (s.length() > max) {
            return s.substring(0, max - 3) + "...";
        }
        return s;
    }

}
