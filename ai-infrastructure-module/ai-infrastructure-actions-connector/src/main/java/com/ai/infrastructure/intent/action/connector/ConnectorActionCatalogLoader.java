package com.ai.infrastructure.intent.action.connector;

import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.intent.action.AIActionParamType;
import com.ai.infrastructure.intent.action.AIContributionProvenance;
import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.ActionResultPresentationHint;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorDecision;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorDecisionType;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorRule;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorStackPolicy;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorTrigger;
import com.ai.infrastructure.shell.BuiltInShellCatalog;
import lombok.extern.slf4j.Slf4j;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loads connector-backed action contracts from configured sources (file-based in the initial release).
 *
 * <p>Greenfield: this loader is strict and fail-fast. Invalid schemas or missing files must
 * terminate startup rather than running with a partial catalog.</p>
 */
@Slf4j
@Service
public class ConnectorActionCatalogLoader {

    private static final String KEY_ACTIONS = "actions";
    private static final String KEY_CONFIRMATION_INTERCEPTORS = "confirmationInterceptors";
    private static final String KEY_WEBHOOK_TARGETS = "webhookTargets";
    private static final String KEY_MCP_SERVERS = "mcpServers";
    private static final String KEY_NAME = "name";
    private static final String KEY_SERVER_REF = "serverRef";
    private static final String KEY_DISPLAY_NAME = "displayName";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_CATEGORY = "category";
    private static final String KEY_ADAPTER_TYPE = "adapterType";
    private static final String KEY_EXECUTION = "execution";
    private static final String KEY_ACCESS_MODE = "accessMode";
    private static final String KEY_REQUIRES_CONFIRMATION = "requiresConfirmation";
    private static final String KEY_CONFIRMATION_MESSAGE = "confirmationMessage";
    private static final String KEY_PARAMS = "params";
    private static final String KEY_ANONYMOUS_ALLOWED = "anonymousAllowed";
    private static final String KEY_GROUNDING_ELIGIBLE = "groundingEligible";
    private static final String KEY_READ_ACTION_RESOLUTION_ELIGIBLE = "readActionResolutionEligible";
    private static final String KEY_RESULT_PRESENTATION_HINT = "resultPresentationHint";
    private static final String KEY_BUILT_IN_MODULE_ID = "builtInModuleId";
    private static final String KEY_BUILT_IN_CARD_ID = "builtInCardId";
    private static final String KEY_PROVENANCE = "provenance";
    private static final String KEY_POST_POLICIES = "postPolicies";
    private static final String KEY_LLM_FACTS = "llmFacts";
    private static final String KEY_ROOT_PATH = "rootPath";
    private static final String KEY_COPY_FIELDS = "copyFields";
    private static final String KEY_LISTS = "lists";
    private static final String KEY_OBJECTS = "objects";
    private static final String KEY_SOURCE_PATH = "sourcePath";
    private static final String KEY_TARGET = "target";
    private static final String KEY_MAX_ITEMS = "maxItems";
    private static final String KEY_INCLUDE_FIELDS = "includeFields";
    private static final String KEY_FALLBACK_CONTENT_FIELD = "fallbackContentField";
    private static final String KEY_FALLBACK_CONTENT_MAX_CHARS = "fallbackContentMaxChars";
    private static final String KEY_RANK_RULES = "rankRules";
    private static final String KEY_CONSTRAINTS = "constraints";
    private static final String KEY_RULES = "rules";
    private static final String KEY_COUNT_TARGET = "countTarget";
    private static final String KEY_FIELD = "field";
    private static final String KEY_PARAM_PATH = "paramPath";
    private static final String KEY_OPERATOR = "operator";
    private static final String KEY_VALUE = "value";
    private static final String KEY_SCORE = "score";
    private static final String KEY_SCORE_MATCH = "scoreMatch";
    private static final String KEY_SCORE_MISSING = "scoreMissing";
    private static final String KEY_SCORE_MISMATCH = "scoreMismatch";
    private static final String KEY_SORT_ASCENDING_ON_MATCH = "sortAscendingOnMatch";
    private static final String KEY_SUMMARIES = "summaries";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_RECORD_COUNT_KEY = "recordCountKey";
    private static final String KEY_LOWEST_VALUE_KEY = "lowestValueKey";
    private static final String KEY_HIGHEST_VALUE_KEY = "highestValueKey";
    private static final String KEY_LABEL_FIELD = "labelField";
    private static final String KEY_LOWEST_LABEL_KEY = "lowestLabelKey";
    private static final String KEY_HIGHEST_LABEL_KEY = "highestLabelKey";
    private static final String KEY_EXTRA_FIELDS = "extraFields";
    private static final String KEY_LOWEST_KEY = "lowestKey";
    private static final String KEY_HIGHEST_KEY = "highestKey";
    private static final String KEY_TARGET_REF = "targetRef";
    private static final String KEY_EVENT_TYPE = "eventType";
    private static final String KEY_ID = "id";
    private static final String KEY_URL_SECRET_REF = "urlSecretRef";
    private static final String KEY_SIGNING_SECRET_REF = "signingSecretRef";
    private static final String KEY_TIMEOUT_MS = "timeoutMs";
    private static final String KEY_MAX_ATTEMPTS = "maxAttempts";

    private static final String KEY_TYPE = "type";
    private static final String KEY_REQUIRED = "required";
    private static final String KEY_BATCH_TARGETS = "batchTargets";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_PROPERTIES = "properties";
    private static final String KEY_REQUIRED_PROPERTIES = "requiredProperties";
    private static final String KEY_PATTERN = "pattern";
    private static final String KEY_ALLOWED_VALUES = "allowedValues";
    private static final String KEY_MIN = "min";
    private static final String KEY_MAX = "max";
    private static final String KEY_DEFAULT_VALUE = "defaultValue";
    private static final String KEY_VISIBILITY = "visibility";
    private static final String KEY_ASK_USER = "askUser";
    private static final String KEY_RESOLVE_FROM = "resolveFrom";
    private static final String KEY_SENSITIVE = "sensitive";
    private static final String KEY_EVIDENCE_BOUND = "evidenceBound";
    private static final String KEY_EVIDENCE_KEYS = "evidenceKeys";
    private static final String KEY_EVIDENCE_FALLBACK_POLICY = "evidenceFallbackPolicy";
    private static final String KEY_TRIGGER = "trigger";
    private static final String KEY_CONFIRMATION = "confirmation";
    private static final String KEY_ONCE_PARAM = "onceParam";
    private static final String KEY_DECISION = "decision";
    private static final String KEY_ACTION = "action";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_STACK = "stack";
    private static final String KEY_POP_CURRENT = "popCurrent";
    private static final String KEY_POP_PREVIOUS_IF_ACTION_IN = "popPreviousIfActionIn";

    private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)(?:\\s*\\|\\s*([^{}]*?))?\\s*}}");
    private static final Pattern INTERCEPTION_TEMPLATE_PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");
    private static final Pattern SAFE_ONCE_PARAM = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SAFE_SECRET_REF = Pattern.compile("[A-Z][A-Z0-9_]*");

    private final ResourceLoader resourceLoader;
    private final Yaml yaml;

    public ConnectorActionCatalogLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;

        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        this.yaml = new Yaml(new SafeConstructor(options));
    }

    /**
     * Load all connector action definitions from configured sources.
     *
     * @param sources configured catalog sources (may be empty)
     * @return list of parsed action definitions (never null)
     */
    public List<ConnectorActionDefinition> loadActions(List<AIActionCatalogProperties.ActionSourceProperties> sources) {
        return loadCatalog(sources).actions();
    }

    public ConnectorActionCatalog loadCatalog(List<AIActionCatalogProperties.ActionSourceProperties> sources) {
        if (sources == null || sources.isEmpty()) {
            return new ConnectorActionCatalog(List.of(), List.of(), List.of(), List.of());
        }

        List<ConnectorActionDefinition> actions = new ArrayList<>();
        List<ConfirmationInterceptorRule> confirmationInterceptors = new ArrayList<>();
        List<ConnectorWebhookTargetDefinition> webhookTargets = new ArrayList<>();
        List<String> sourceLocations = new ArrayList<>();
        for (AIActionCatalogProperties.ActionSourceProperties source : sources) {
            if (source == null) {
                continue;
            }
            if (source.getType() == AIActionCatalogProperties.ActionSourceType.DB) {
                throw new IllegalStateException("Action source type DB is not supported yet (configure FILE sources only).");
            }

            String path = source.getPath();
            if (!StringUtils.hasText(path)) {
                throw new IllegalStateException("Action source path is required for FILE sources.");
            }

            String label = path.trim();
            Resource resource = resourceLoader.getResource(label);
            if (resource == null || !resource.exists()) {
                if (source.isOptional()) {
                    log.info("Optional action contract file not found: {} (skipping)", label);
                    continue;
                }
                throw new IllegalStateException("Action contract file not found: " + label);
            }

            CatalogPart part = loadFromResource(resource, label);
            actions.addAll(part.actions());
            confirmationInterceptors.addAll(part.confirmationInterceptors());
            webhookTargets.addAll(part.webhookTargets());
            sourceLocations.add(label);
        }

        validateCatalog(actions, confirmationInterceptors, webhookTargets);
        return new ConnectorActionCatalog(actions, confirmationInterceptors, webhookTargets, sourceLocations);
    }

    private CatalogPart loadFromResource(Resource resource, String label) {
        Object root;
        try (InputStream in = resource.getInputStream()) {
            root = yaml.load(in);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read action contract YAML from " + label + ": " + ex.getMessage(), ex);
        }

        Map<String, Object> rootMap = extractRootMap(root, label);
        List<Map<String, Object>> actionMaps = extractSectionMaps(rootMap.get(KEY_ACTIONS), label, KEY_ACTIONS, "action");
        List<Map<String, Object>> interceptorMaps = extractSectionMaps(
            rootMap.get(KEY_CONFIRMATION_INTERCEPTORS),
            label,
            KEY_CONFIRMATION_INTERCEPTORS,
            "confirmation interceptor"
        );
        List<Map<String, Object>> webhookTargetMaps = extractSectionMaps(
            rootMap.get(KEY_WEBHOOK_TARGETS),
            label,
            KEY_WEBHOOK_TARGETS,
            "webhook target"
        );
        Map<String, Object> mcpServers = indexMcpServers(
            extractSectionMaps(rootMap.get(KEY_MCP_SERVERS), label, KEY_MCP_SERVERS, "MCP server"),
            label
        );
        if (actionMaps.isEmpty()) {
            log.info("No connector actions found in {}", label);
        }

        List<ConnectorActionDefinition> actions = new ArrayList<>();
        for (Map<String, Object> map : actionMaps) {
            if (map == null || map.isEmpty()) {
                continue;
            }
            actions.add(parseAction(map, label, mcpServers));
        }

        List<ConfirmationInterceptorRule> confirmationInterceptors = new ArrayList<>();
        for (Map<String, Object> map : interceptorMaps) {
            if (map == null || map.isEmpty()) {
                continue;
            }
            confirmationInterceptors.add(parseConfirmationInterceptor(map, label));
        }
        List<ConnectorWebhookTargetDefinition> webhookTargets = new ArrayList<>();
        for (Map<String, Object> map : webhookTargetMaps) {
            if (map == null || map.isEmpty()) {
                continue;
            }
            webhookTargets.add(parseWebhookTarget(map, label));
        }
        return new CatalogPart(actions, confirmationInterceptors, webhookTargets);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractRootMap(Object root, String label) {
        if (root == null) {
            return Map.of();
        }
        if (root instanceof Map<?, ?> map) {
            return toStringKeyedMap(map);
        }

        throw new IllegalStateException("Invalid action contract in " + label + ": expected a YAML object.");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractSectionMaps(Object rawSection, String label, String sectionName, String entryLabel) {
        if (rawSection == null) {
            return List.of();
        }
        if (!(rawSection instanceof List<?> list)) {
            throw new IllegalStateException("Invalid action contract in " + label + ": '" + sectionName + "' must be a list.");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new IllegalStateException("Invalid action contract in " + label + ": each " + entryLabel + " must be a map/object.");
            }
            out.add(toStringKeyedMap(raw));
        }
        return List.copyOf(out);
    }

    private ConnectorActionDefinition parseAction(Map<String, Object> raw, String label, Map<String, Object> mcpServers) {
        String name = readString(raw, KEY_NAME);
        if (!StringUtils.hasText(name)) {
            throw new IllegalStateException("Invalid action contract in " + label + ": action.name is required.");
        }

        String adapterType = readString(raw, KEY_ADAPTER_TYPE);
        Map<String, Object> execution = readOptionalObjectMap(raw.get(KEY_EXECUTION), label, "actions[" + name + "].execution");
        String displayName = readString(raw, KEY_DISPLAY_NAME);
        String description = readString(raw, KEY_DESCRIPTION);
        String category = readString(raw, KEY_CATEGORY);

        String accessModeRaw = readString(raw, KEY_ACCESS_MODE);
        ActionAccessMode accessMode = parseAccessMode(accessModeRaw, label, name);

        boolean requiresConfirmation = readBoolean(raw, KEY_REQUIRES_CONFIRMATION, false);
        String confirmationMessage = readString(raw, KEY_CONFIRMATION_MESSAGE);
        boolean anonymousAllowed = readBoolean(raw, KEY_ANONYMOUS_ALLOWED, false);
        boolean groundingEligible = raw.containsKey(KEY_GROUNDING_ELIGIBLE)
            ? readBoolean(raw, KEY_GROUNDING_ELIGIBLE, false)
            : defaultGroundingEligible(accessMode);
        boolean readActionResolutionEligible = readBoolean(raw, KEY_READ_ACTION_RESOLUTION_ELIGIBLE, false);
        ActionResultPresentationHint resultPresentationHint = parseResultPresentationHint(
            readString(raw, KEY_RESULT_PRESENTATION_HINT),
            accessMode,
            label,
            name
        );
        String builtInModuleId = readString(raw, KEY_BUILT_IN_MODULE_ID);
        String builtInCardId = readString(raw, KEY_BUILT_IN_CARD_ID);
        AIContributionProvenance provenance = parseProvenance(raw.get(KEY_PROVENANCE), label, name);

        List<ConnectorActionParamDefinition> params = parseParams(raw.get(KEY_PARAMS), label, name);
        List<ConnectorActionPostPolicyDefinition> postPolicies = parsePostPolicies(raw.get(KEY_POST_POLICIES), label, name);
        ConnectorActionLlmFactsDefinition llmFacts = parseLlmFacts(raw.get(KEY_LLM_FACTS), label, name);

        validateConfirmationTemplate(name, confirmationMessage, params, label);

        return new ConnectorActionDefinition(
            name.trim(),
            StringUtils.hasText(displayName) ? displayName.trim() : humanizeActionName(name),
            description,
            category,
            accessMode,
            requiresConfirmation,
            confirmationMessage,
            params,
            anonymousAllowed,
            groundingEligible,
            readActionResolutionEligible,
            resultPresentationHint,
            StringUtils.hasText(builtInModuleId) ? builtInModuleId.trim() : null,
            StringUtils.hasText(builtInCardId) ? builtInCardId.trim() : null,
            provenance,
            postPolicies,
            llmFacts,
            adapterType,
            execution,
            mcpServers
        );
    }

    private Map<String, Object> indexMcpServers(List<Map<String, Object>> serverMaps, String label) {
        if (serverMaps == null || serverMaps.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> server : serverMaps) {
            if (server == null || server.isEmpty()) {
                continue;
            }
            String serverRef = readString(server, KEY_SERVER_REF);
            if (!StringUtils.hasText(serverRef)) {
                serverRef = readString(server, KEY_ID);
            }
            if (!StringUtils.hasText(serverRef)) {
                throw new IllegalStateException("Invalid action contract in " + label + ": each MCP server must declare serverRef or id.");
            }
            String normalized = serverRef.trim();
            if (out.containsKey(normalized)) {
                throw new IllegalStateException("Invalid action contract in " + label + ": duplicate MCP serverRef: " + normalized);
            }
            out.put(normalized, Collections.unmodifiableMap(new LinkedHashMap<>(server)));
        }
        return Collections.unmodifiableMap(out);
    }

    private ConnectorActionLlmFactsDefinition parseLlmFacts(Object rawLlmFacts,
                                                            String label,
                                                            String actionName) {
        if (rawLlmFacts == null) {
            return null;
        }
        Map<String, Object> raw = readOptionalObjectMap(rawLlmFacts, label, "actions[" + actionName + "].llmFacts");
        if (raw.isEmpty()) {
            return null;
        }
        String rootPath = readString(raw, KEY_ROOT_PATH);
        List<String> copyFields = readStringList(raw.get(KEY_COPY_FIELDS));
        List<ConnectorActionLlmFactsListDefinition> lists = parseLlmFactLists(raw.get(KEY_LISTS), label, actionName);
        List<ConnectorActionLlmFactsObjectDefinition> objects = parseLlmFactObjects(raw.get(KEY_OBJECTS), label, actionName);
        return new ConnectorActionLlmFactsDefinition(rootPath, copyFields, lists, objects);
    }

    private List<ConnectorActionLlmFactsListDefinition> parseLlmFactLists(Object rawLists,
                                                                          String label,
                                                                          String actionName) {
        if (rawLists == null) {
            return List.of();
        }
        if (!(rawLists instanceof List<?> list)) {
            throw new IllegalStateException("Invalid action contract in " + label
                + " for action '" + actionName + "': llmFacts.lists must be a list.");
        }
        List<ConnectorActionLlmFactsListDefinition> out = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> raw = readObjectMap(item, label, "actions[" + actionName + "].llmFacts.lists[]");
            String sourcePath = readString(raw, KEY_SOURCE_PATH);
            String target = readString(raw, KEY_TARGET);
            if (!StringUtils.hasText(sourcePath) || !StringUtils.hasText(target)) {
                throw new IllegalStateException("Invalid action contract in " + label
                    + " for action '" + actionName + "': each llmFacts list requires sourcePath and target.");
            }
            int maxItems = readPositiveInt(raw.get(KEY_MAX_ITEMS), label, actionName, "llmFacts.lists.maxItems", 5);
            int fallbackContentMaxChars = readPositiveInt(
                raw.get(KEY_FALLBACK_CONTENT_MAX_CHARS),
                label,
                actionName,
                "llmFacts.lists.fallbackContentMaxChars",
                300
            );
            out.add(new ConnectorActionLlmFactsListDefinition(
                sourcePath.trim(),
                target.trim(),
                maxItems,
                readStringList(raw.get(KEY_INCLUDE_FIELDS)),
                readString(raw, KEY_FALLBACK_CONTENT_FIELD),
                fallbackContentMaxChars,
                parseLlmFactRules(raw.get(KEY_RANK_RULES), label, actionName, "llmFacts.lists.rankRules"),
                parseLlmFactConstraints(raw.get(KEY_CONSTRAINTS), label, actionName),
                parseLlmFactSummaries(raw.get(KEY_SUMMARIES), label, actionName)
            ));
        }
        return List.copyOf(out);
    }

    private List<ConnectorActionLlmFactsObjectDefinition> parseLlmFactObjects(Object rawObjects,
                                                                              String label,
                                                                              String actionName) {
        if (rawObjects == null) {
            return List.of();
        }
        if (!(rawObjects instanceof List<?> list)) {
            throw new IllegalStateException("Invalid action contract in " + label
                + " for action '" + actionName + "': llmFacts.objects must be a list.");
        }
        List<ConnectorActionLlmFactsObjectDefinition> out = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> raw = readObjectMap(item, label, "actions[" + actionName + "].llmFacts.objects[]");
            String sourcePath = readString(raw, KEY_SOURCE_PATH);
            String target = readString(raw, KEY_TARGET);
            if (!StringUtils.hasText(sourcePath) || !StringUtils.hasText(target)) {
                throw new IllegalStateException("Invalid action contract in " + label
                    + " for action '" + actionName + "': each llmFacts object requires sourcePath and target.");
            }
            out.add(new ConnectorActionLlmFactsObjectDefinition(
                sourcePath.trim(),
                target.trim(),
                readStringList(raw.get(KEY_INCLUDE_FIELDS)),
                readString(raw, KEY_FALLBACK_CONTENT_FIELD),
                readPositiveInt(raw.get(KEY_FALLBACK_CONTENT_MAX_CHARS), label, actionName, "llmFacts.objects.fallbackContentMaxChars", 300)
            ));
        }
        return List.copyOf(out);
    }

    private ConnectorActionLlmFactsConstraintDefinition parseLlmFactConstraints(Object rawConstraints,
                                                                               String label,
                                                                               String actionName) {
        if (rawConstraints == null) {
            return null;
        }
        Map<String, Object> raw = readOptionalObjectMap(rawConstraints, label, "actions[" + actionName + "].llmFacts.lists.constraints");
        if (raw.isEmpty()) {
            return null;
        }
        String target = readString(raw, KEY_TARGET);
        if (!StringUtils.hasText(target)) {
            throw new IllegalStateException("Invalid action contract in " + label
                + " for action '" + actionName + "': llmFacts.lists.constraints.target is required.");
        }
        return new ConnectorActionLlmFactsConstraintDefinition(
            target.trim(),
            readString(raw, KEY_COUNT_TARGET),
            readStringList(raw.get(KEY_INCLUDE_FIELDS)),
            parseLlmFactRules(raw.get(KEY_RULES), label, actionName, "llmFacts.lists.constraints.rules")
        );
    }

    private List<ConnectorActionLlmFactsSummaryDefinition> parseLlmFactSummaries(Object rawSummaries,
                                                                                 String label,
                                                                                 String actionName) {
        if (rawSummaries == null) {
            return List.of();
        }
        if (!(rawSummaries instanceof List<?> list)) {
            throw new IllegalStateException("Invalid action contract in " + label
                + " for action '" + actionName + "': llmFacts.lists.summaries must be a list.");
        }
        List<ConnectorActionLlmFactsSummaryDefinition> out = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> raw = readObjectMap(item, label, "actions[" + actionName + "].llmFacts.lists.summaries[]");
            String target = readString(raw, KEY_TARGET);
            String field = readString(raw, KEY_FIELD);
            if (!StringUtils.hasText(target) || !StringUtils.hasText(field)) {
                throw new IllegalStateException("Invalid action contract in " + label
                    + " for action '" + actionName + "': each llmFacts summary requires target and field.");
            }
            out.add(new ConnectorActionLlmFactsSummaryDefinition(
                target.trim(),
                readString(raw, KEY_SOURCE),
                field.trim(),
                readString(raw, KEY_RECORD_COUNT_KEY),
                readString(raw, KEY_LOWEST_VALUE_KEY),
                readString(raw, KEY_HIGHEST_VALUE_KEY),
                readString(raw, KEY_LABEL_FIELD),
                readString(raw, KEY_LOWEST_LABEL_KEY),
                readString(raw, KEY_HIGHEST_LABEL_KEY),
                parseLlmFactSummaryExtraFields(raw.get(KEY_EXTRA_FIELDS), label, actionName)
            ));
        }
        return List.copyOf(out);
    }

    private List<ConnectorActionLlmFactsSummaryExtraFieldDefinition> parseLlmFactSummaryExtraFields(Object rawExtraFields,
                                                                                                    String label,
                                                                                                    String actionName) {
        if (rawExtraFields == null) {
            return List.of();
        }
        if (!(rawExtraFields instanceof List<?> list)) {
            throw new IllegalStateException("Invalid action contract in " + label
                + " for action '" + actionName + "': llmFacts.lists.summaries.extraFields must be a list.");
        }
        List<ConnectorActionLlmFactsSummaryExtraFieldDefinition> out = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> raw = readObjectMap(item, label, "actions[" + actionName + "].llmFacts.lists.summaries.extraFields[]");
            String field = readString(raw, KEY_FIELD);
            if (!StringUtils.hasText(field)) {
                throw new IllegalStateException("Invalid action contract in " + label
                    + " for action '" + actionName + "': each llmFacts summary extraField requires field.");
            }
            out.add(new ConnectorActionLlmFactsSummaryExtraFieldDefinition(
                field.trim(),
                readString(raw, KEY_LOWEST_KEY),
                readString(raw, KEY_HIGHEST_KEY)
            ));
        }
        return List.copyOf(out);
    }

    private List<ConnectorActionLlmFactsRuleDefinition> parseLlmFactRules(Object rawRules,
                                                                          String label,
                                                                          String actionName,
                                                                          String fieldName) {
        if (rawRules == null) {
            return List.of();
        }
        if (!(rawRules instanceof List<?> list)) {
            throw new IllegalStateException("Invalid action contract in " + label
                + " for action '" + actionName + "': " + fieldName + " must be a list.");
        }
        List<ConnectorActionLlmFactsRuleDefinition> out = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> raw = readObjectMap(item, label, "actions[" + actionName + "]." + fieldName + "[]");
            String type = readString(raw, KEY_TYPE);
            String field = readString(raw, KEY_FIELD);
            if (!StringUtils.hasText(type) || !StringUtils.hasText(field)) {
                throw new IllegalStateException("Invalid action contract in " + label
                    + " for action '" + actionName + "': each " + fieldName + " entry requires type and field.");
            }
            String normalizedType = type.trim().toUpperCase(Locale.ROOT);
            if (!isSupportedLlmFactRuleType(normalizedType)) {
                throw new IllegalStateException("Invalid action contract in " + label
                    + " for action '" + actionName + "': unsupported " + fieldName + " type '" + type + "'.");
            }
            String paramPath = readString(raw, KEY_PARAM_PATH);
            if (!StringUtils.hasText(paramPath)) {
                throw new IllegalStateException("Invalid action contract in " + label
                    + " for action '" + actionName + "': each " + fieldName + " entry requires paramPath.");
            }
            out.add(new ConnectorActionLlmFactsRuleDefinition(
                type.trim(),
                field.trim(),
                paramPath.trim(),
                readString(raw, KEY_OPERATOR),
                raw.get(KEY_VALUE),
                readPositiveInt(raw.get(KEY_SCORE), label, actionName, fieldName + ".score", 0),
                readInt(raw.get(KEY_SCORE_MATCH), label, actionName, fieldName + ".scoreMatch", 0),
                readInt(raw.get(KEY_SCORE_MISSING), label, actionName, fieldName + ".scoreMissing", 0),
                readInt(raw.get(KEY_SCORE_MISMATCH), label, actionName, fieldName + ".scoreMismatch", 0),
                readBoolean(raw, KEY_SORT_ASCENDING_ON_MATCH, false)
            ));
        }
        return List.copyOf(out);
    }

    private boolean isSupportedLlmFactRuleType(String type) {
        return "PARAM_NUMERIC_UPPER_BOUND".equals(type)
            || "PARAM_NUMERIC_LOWER_BOUND".equals(type)
            || "PARAM_BOOLEAN_TRUE".equals(type)
            || "PARAM_BOOLEAN_FALSE".equals(type)
            || "PARAM_EQUALS".equals(type);
    }

    private List<ConnectorActionPostPolicyDefinition> parsePostPolicies(Object rawPostPolicies,
                                                                        String label,
                                                                        String actionName) {
        if (rawPostPolicies == null) {
            return List.of();
        }
        if (!(rawPostPolicies instanceof List<?> list)) {
            throw new IllegalStateException("Invalid action contract in " + label
                + " for action '" + actionName + "': postPolicies must be a list.");
        }
        List<ConnectorActionPostPolicyDefinition> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalStateException("Invalid action contract in " + label
                    + " for action '" + actionName + "': each post policy must be a map/object.");
            }
            Map<String, Object> raw = toStringKeyedMap(map);
            String type = readString(raw, KEY_TYPE);
            if (!"webhook".equalsIgnoreCase(type)) {
                throw new IllegalStateException("Invalid action contract in " + label
                    + " for action '" + actionName + "': unsupported post policy type '" + type + "'.");
            }
            String targetRef = readString(raw, KEY_TARGET_REF);
            if (!StringUtils.hasText(targetRef)) {
                throw new IllegalStateException("Invalid action contract in " + label
                    + " for action '" + actionName + "': post policy targetRef is required.");
            }
            String eventType = readString(raw, KEY_EVENT_TYPE);
            if (!StringUtils.hasText(eventType)) {
                throw new IllegalStateException("Invalid action contract in " + label
                    + " for action '" + actionName + "': webhook post policy eventType is required.");
            }
            out.add(new ConnectorActionPostPolicyDefinition(
                "webhook",
                targetRef.trim(),
                eventType.trim()
            ));
        }
        return List.copyOf(out);
    }

    private ConnectorWebhookTargetDefinition parseWebhookTarget(Map<String, Object> raw, String label) {
        String id = readString(raw, KEY_ID);
        if (!StringUtils.hasText(id)) {
            throw new IllegalStateException("Invalid action contract in " + label + ": webhookTargets[].id is required.");
        }
        String urlSecretRef = readString(raw, KEY_URL_SECRET_REF);
        if (!StringUtils.hasText(urlSecretRef)) {
            throw new IllegalStateException("Invalid action contract in " + label
                + " for webhook target '" + id + "': urlSecretRef is required.");
        }
        if (!SAFE_SECRET_REF.matcher(urlSecretRef.trim()).matches()) {
            throw new IllegalStateException("Invalid action contract in " + label
                + " for webhook target '" + id + "': urlSecretRef must be a managed secret-style name.");
        }
        String signingSecretRef = readString(raw, KEY_SIGNING_SECRET_REF);
        if (StringUtils.hasText(signingSecretRef) && !SAFE_SECRET_REF.matcher(signingSecretRef.trim()).matches()) {
            throw new IllegalStateException("Invalid action contract in " + label
                + " for webhook target '" + id + "': signingSecretRef must be a managed secret-style name.");
        }
        Long timeoutMs = readLong(raw.get(KEY_TIMEOUT_MS), label, "webhook target", id, KEY_TIMEOUT_MS);
        Long maxAttempts = readLong(raw.get(KEY_MAX_ATTEMPTS), label, "webhook target", id, KEY_MAX_ATTEMPTS);
        return new ConnectorWebhookTargetDefinition(
            id.trim(),
            urlSecretRef.trim(),
            StringUtils.hasText(signingSecretRef) ? signingSecretRef.trim() : null,
            timeoutMs != null ? timeoutMs.intValue() : null,
            maxAttempts != null ? maxAttempts.intValue() : null
        );
    }

    private boolean defaultGroundingEligible(ActionAccessMode accessMode) {
        return accessMode == ActionAccessMode.READ || accessMode == ActionAccessMode.READ_WRITE;
    }

    private ActionResultPresentationHint parseResultPresentationHint(String raw,
                                                                     ActionAccessMode accessMode,
                                                                     String label,
                                                                     String actionName) {
        if (!StringUtils.hasText(raw)) {
            return accessMode == ActionAccessMode.WRITE_ONLY
                ? ActionResultPresentationHint.STATUS
                : ActionResultPresentationHint.DEFAULT;
        }
        try {
            return ActionResultPresentationHint.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid action contract in " + label
                + " for action '" + actionName + "': unsupported resultPresentationHint '" + raw + "'.");
        }
    }

    private AIContributionProvenance parseProvenance(Object raw, String label, String actionName) {
        Map<String, Object> provenance = raw != null
            ? readOptionalObjectMap(raw, label, "actions[" + actionName + "].provenance")
            : Map.of();
        return AIContributionProvenance.builder()
            .contributionType(trimToNull(readString(provenance, "contributionType")) != null
                ? trimToNull(readString(provenance, "contributionType"))
                : "ACTION")
            .sourceType(trimToNull(readString(provenance, "sourceType")) != null
                ? trimToNull(readString(provenance, "sourceType"))
                : "ACTION_CATALOG")
            .sourceId(trimToNull(readString(provenance, "sourceId")) != null
                ? trimToNull(readString(provenance, "sourceId"))
                : actionName.trim())
            .sourceLocation(trimToNull(readString(provenance, "sourceLocation")) != null
                ? trimToNull(readString(provenance, "sourceLocation"))
                : label)
            .publisher(trimToNull(readString(provenance, "publisher")))
            .version(trimToNull(readString(provenance, "version")))
            .build();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String humanizeActionName(String actionName) {
        if (!StringUtils.hasText(actionName)) {
            return null;
        }
        String[] parts = actionName.trim().split("[_\\-\\s]+");
        StringBuilder out = new StringBuilder(actionName.length() + 8);
        for (String part : parts) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            String normalized = part.trim().toLowerCase(Locale.ROOT);
            out.append(Character.toUpperCase(normalized.charAt(0)));
            if (normalized.length() > 1) {
                out.append(normalized.substring(1));
            }
        }
        return out.isEmpty() ? actionName.trim() : out.toString();
    }

    private ConfirmationInterceptorRule parseConfirmationInterceptor(Map<String, Object> raw, String label) {
        String name = readString(raw, KEY_NAME);
        if (!StringUtils.hasText(name)) {
            throw new IllegalStateException("Invalid action contract in " + label + ": confirmationInterceptors[].name is required.");
        }

        Map<String, Object> triggerRaw = readObjectMap(raw.get(KEY_TRIGGER), label, "confirmationInterceptors[" + name + "].trigger");
        List<String> pendingActions = readStringList(triggerRaw.get("pendingActions"));
        if (pendingActions.isEmpty()) {
            throw new IllegalStateException("Invalid action contract in " + label
                + " for confirmation interceptor '" + name + "': trigger.pendingActions must list one or more actions.");
        }

        String confirmationRaw = readString(triggerRaw, KEY_CONFIRMATION);
        IntentType confirmation = parseConfirmationIntentType(confirmationRaw, label, name);
        String onceParam = readString(triggerRaw, KEY_ONCE_PARAM);

        Map<String, Object> decisionRaw = readObjectMap(raw.get(KEY_DECISION), label, "confirmationInterceptors[" + name + "].decision");
        ConfirmationInterceptorDecisionType decisionType = parseDecisionType(readString(decisionRaw, KEY_TYPE), label, name);
        String action = readString(decisionRaw, KEY_ACTION);
        Map<String, Object> params = decisionRaw.containsKey(KEY_PARAMS)
            ? readOptionalObjectMap(decisionRaw.get(KEY_PARAMS), label, "confirmationInterceptors[" + name + "].decision.params")
            : Map.of();
        String message = readString(decisionRaw, KEY_MESSAGE);

        Map<String, Object> stackRaw = raw.containsKey(KEY_STACK)
            ? readOptionalObjectMap(raw.get(KEY_STACK), label, "confirmationInterceptors[" + name + "].stack")
            : Map.of();
        boolean popCurrent = readBoolean(stackRaw, KEY_POP_CURRENT, false);
        List<String> popPreviousIfActionIn = readStringList(stackRaw.get(KEY_POP_PREVIOUS_IF_ACTION_IN));

        return new ConfirmationInterceptorRule(
            name.trim(),
            new ConfirmationInterceptorTrigger(pendingActions, confirmation, onceParam),
            new ConfirmationInterceptorDecision(decisionType, action, params, message),
            new ConfirmationInterceptorStackPolicy(popCurrent, popPreviousIfActionIn)
        );
    }

    private List<ConnectorActionParamDefinition> parseParams(Object rawParams, String label, String actionName) {
        if (rawParams == null) {
            return List.of();
        }
        if (!(rawParams instanceof List<?> list)) {
            throw new IllegalStateException("Invalid action contract in " + label + " for action '" + actionName + "': params must be a list.");
        }

        List<ConnectorActionParamDefinition> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalStateException("Invalid action contract in " + label + " for action '" + actionName + "': each param must be a map/object.");
            }
            Map<String, Object> casted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                casted.put(entry.getKey().toString(), entry.getValue());
            }
            out.add(parseParam(casted, label, actionName));
        }

        return List.copyOf(out);
    }

    private ConnectorActionParamDefinition parseParam(Map<String, Object> raw, String label, String actionName) {
        return parseParam(raw, label, actionName, null);
    }

    private ConnectorActionParamDefinition parseParam(Map<String, Object> raw,
                                                      String label,
                                                      String actionName,
                                                      String defaultName) {
        String name = readString(raw, KEY_NAME);
        if (!StringUtils.hasText(name) && StringUtils.hasText(defaultName)) {
            name = defaultName.trim();
        }
        if (!StringUtils.hasText(name)) {
            throw new IllegalStateException("Invalid action contract in " + label + " for action '" + actionName + "': param.name is required.");
        }

        String description = readString(raw, KEY_DESCRIPTION);
        String typeRaw = readString(raw, KEY_TYPE);
        if (!StringUtils.hasText(typeRaw)) {
            throw new IllegalStateException("Invalid action contract in " + label + " for action '" + actionName + "', param '" + name + "': type is required.");
        }

        AIActionParamType type = parseParamType(typeRaw, label, actionName, name);

        boolean required = readBoolean(raw, KEY_REQUIRED, false);
        boolean batchTargets = readBoolean(raw, KEY_BATCH_TARGETS, false);
        String pattern = readString(raw, KEY_PATTERN);
        List<String> allowedValues = readStringList(raw.get(KEY_ALLOWED_VALUES));
        Long min = readLong(raw.get(KEY_MIN), label, actionName, name, KEY_MIN);
        Long max = readLong(raw.get(KEY_MAX), label, actionName, name, KEY_MAX);
        Object defaultValue = raw.get(KEY_DEFAULT_VALUE);
        String visibility = readString(raw, KEY_VISIBILITY);
        Boolean askUser = raw.containsKey(KEY_ASK_USER)
            ? readBoolean(raw, KEY_ASK_USER, true)
            : null;
        Map<String, Object> resolveFrom = readOptionalObjectMap(raw.get(KEY_RESOLVE_FROM), label, "actions[" + actionName + "].params[" + name + "].resolveFrom");
        boolean sensitive = readBoolean(raw, KEY_SENSITIVE, false);
        ConnectorActionParamDefinition items = parseItemSchema(raw.get(KEY_ITEMS), label, actionName, name);
        Map<String, ConnectorActionParamDefinition> properties = parsePropertySchemas(raw.get(KEY_PROPERTIES), label, actionName, name);
        List<String> requiredProperties = readStringList(raw.get(KEY_REQUIRED_PROPERTIES));
        boolean evidenceBound = readBoolean(raw, KEY_EVIDENCE_BOUND, false);
        List<String> evidenceKeys = readStringList(raw.get(KEY_EVIDENCE_KEYS));
        String evidenceFallbackPolicy = readString(raw, KEY_EVIDENCE_FALLBACK_POLICY);

        return new ConnectorActionParamDefinition(
            name.trim(),
            description,
            type,
            required,
            batchTargets,
            pattern,
            allowedValues,
            min,
            max,
            defaultValue,
            visibility,
            askUser,
            resolveFrom,
            sensitive,
            items,
            properties,
            requiredProperties,
            evidenceBound,
            evidenceKeys,
            evidenceFallbackPolicy
        );
    }

    private ConnectorActionParamDefinition parseItemSchema(Object raw,
                                                           String label,
                                                           String actionName,
                                                           String parentName) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Invalid action contract in " + label + " for action '" + actionName
                + "', param '" + parentName + "': items must be an object.");
        }
        Map<String, Object> item = new LinkedHashMap<>(toStringKeyedMap(map));
        return parseParam(item, label, actionName, parentName + "Item");
    }

    private Map<String, ConnectorActionParamDefinition> parsePropertySchemas(Object raw,
                                                                             String label,
                                                                             String actionName,
                                                                             String parentName) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Invalid action contract in " + label + " for action '" + actionName
                + "', param '" + parentName + "': properties must be an object.");
        }
        LinkedHashMap<String, ConnectorActionParamDefinition> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : toStringKeyedMap(map).entrySet()) {
            if (!StringUtils.hasText(entry.getKey())) {
                continue;
            }
            if (!(entry.getValue() instanceof Map<?, ?> propertyMap)) {
                throw new IllegalStateException("Invalid action contract in " + label + " for action '" + actionName
                    + "', param '" + parentName + "': property '" + entry.getKey() + "' must be an object.");
            }
            Map<String, Object> property = new LinkedHashMap<>(toStringKeyedMap(propertyMap));
            property.putIfAbsent(KEY_NAME, entry.getKey().trim());
            out.put(entry.getKey().trim(), parseParam(property, label, actionName, entry.getKey().trim()));
        }
        return Map.copyOf(out);
    }

    private void validateConfirmationTemplate(String actionName,
                                              String confirmationMessage,
                                              List<ConnectorActionParamDefinition> params,
                                              String label) {
        if (!StringUtils.hasText(confirmationMessage)) {
            return;
        }

        Set<String> paramNames = new LinkedHashSet<>();
        if (params != null) {
            for (ConnectorActionParamDefinition param : params) {
                if (param != null && StringUtils.hasText(param.name())) {
                    paramNames.add(param.name());
                }
            }
        }

        for (String placeholder : extractPlaceholders(confirmationMessage)) {
            if (!paramNames.contains(placeholder)) {
                throw new IllegalStateException("Invalid action contract in " + label + " for action '" + actionName
                    + "': confirmationMessage placeholder '{{" + placeholder + "}}' does not match any declared param.");
            }
        }
    }

    private Set<String> extractPlaceholders(String template) {
        if (!StringUtils.hasText(template)) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        Matcher matcher = TEMPLATE_PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (StringUtils.hasText(name)) {
                out.add(name.trim());
            }
        }
        return Set.copyOf(out);
    }

    private void validateCatalog(List<ConnectorActionDefinition> actions,
                                 List<ConfirmationInterceptorRule> confirmationInterceptors,
                                 List<ConnectorWebhookTargetDefinition> webhookTargets) {
        Map<String, ConnectorActionDefinition> actionsByName = new LinkedHashMap<>();
        for (ConnectorActionDefinition action : actions) {
            if (action == null || !StringUtils.hasText(action.name())) {
                continue;
            }
            validateBuiltInShellMappings(action);
            String normalized = action.name().trim().toLowerCase(Locale.ROOT);
            if (actionsByName.put(normalized, action) != null) {
                throw new IllegalStateException("Duplicate action name in connector catalog: " + action.name());
            }
        }
        Map<String, ConnectorWebhookTargetDefinition> webhookTargetsById = new LinkedHashMap<>();
        for (ConnectorWebhookTargetDefinition target : webhookTargets) {
            if (target == null || !StringUtils.hasText(target.id())) {
                continue;
            }
            String normalizedTargetId = normalizeName(target.id());
            if (webhookTargetsById.put(normalizedTargetId, target) != null) {
                throw new IllegalStateException("Duplicate webhook target id in connector catalog: " + target.id());
            }
            if (target.timeoutMs() != null && target.timeoutMs() <= 0) {
                throw new IllegalStateException("Webhook target '" + target.id() + "' timeoutMs must be positive.");
            }
            if (target.maxAttempts() != null && target.maxAttempts() <= 0) {
                throw new IllegalStateException("Webhook target '" + target.id() + "' maxAttempts must be positive.");
            }
        }
        for (ConnectorActionDefinition action : actions) {
            if (action == null || !StringUtils.hasText(action.name()) || action.postPolicies() == null) {
                continue;
            }
            for (ConnectorActionPostPolicyDefinition policy : action.postPolicies()) {
                if (policy == null) {
                    continue;
                }
                if (!webhookTargetsById.containsKey(normalizeName(policy.targetRef()))) {
                    throw new IllegalStateException("Action '" + action.name()
                        + "' references unknown webhook target '" + policy.targetRef() + "'.");
                }
            }
        }

        Set<String> ruleNames = new LinkedHashSet<>();
        for (ConfirmationInterceptorRule rule : confirmationInterceptors) {
            if (rule == null) {
                continue;
            }
            if (!StringUtils.hasText(rule.name())) {
                throw new IllegalStateException("Confirmation interceptor name is required.");
            }
            String normalizedRuleName = rule.name().trim().toLowerCase(Locale.ROOT);
            if (!ruleNames.add(normalizedRuleName)) {
                throw new IllegalStateException("Duplicate confirmation interceptor name: " + rule.name());
            }
            validateConfirmationInterceptor(rule, actionsByName);
        }
    }

    private void validateBuiltInShellMappings(ConnectorActionDefinition action) {
        if (StringUtils.hasText(action.builtInModuleId())
            && !BuiltInShellCatalog.supportsModuleId(action.builtInModuleId())) {
            throw new IllegalStateException(
                "Action '" + action.name() + "' references unsupported builtInModuleId '"
                    + action.builtInModuleId() + "'."
            );
        }
        if (StringUtils.hasText(action.builtInCardId())
            && !BuiltInShellCatalog.supportsCardId(action.builtInCardId())) {
            throw new IllegalStateException(
                "Action '" + action.name() + "' references unsupported builtInCardId '"
                    + action.builtInCardId() + "'."
            );
        }
    }

    private void validateConfirmationInterceptor(ConfirmationInterceptorRule rule,
                                                 Map<String, ConnectorActionDefinition> actionsByName) {
        ConfirmationInterceptorTrigger trigger = rule.trigger();
        ConfirmationInterceptorDecision decision = rule.decision();
        ConfirmationInterceptorStackPolicy stackPolicy = rule.stackPolicy();

        if (trigger == null || decision == null) {
            throw new IllegalStateException("Confirmation interceptor '" + rule.name() + "' is incomplete.");
        }
        for (String pendingAction : trigger.pendingActions()) {
            requireKnownAction(actionsByName, pendingAction, "trigger.pendingActions", rule.name());
        }
        if (StringUtils.hasText(trigger.onceParam()) && !SAFE_ONCE_PARAM.matcher(trigger.onceParam().trim()).matches()) {
            throw new IllegalStateException("Confirmation interceptor '" + rule.name() + "' has an unsafe trigger.onceParam value.");
        }
        if (decision.type() == ConfirmationInterceptorDecisionType.PROMPT_ACTION
            || decision.type() == ConfirmationInterceptorDecisionType.EXECUTE_ACTION) {
            requireKnownAction(actionsByName, decision.action(), "decision.action", rule.name());
        }
        if (decision.type() == ConfirmationInterceptorDecisionType.PROMPT_ACTION) {
            ConnectorActionDefinition action = actionsByName.get(normalizeName(decision.action()));
            if (action != null && !action.requiresConfirmation()) {
                throw new IllegalStateException("Confirmation interceptor '" + rule.name()
                    + "' uses PROMPT_ACTION with non-confirmable action '" + decision.action() + "'.");
            }
        }
        if (decision.type() == ConfirmationInterceptorDecisionType.REPLY && !StringUtils.hasText(decision.message())) {
            throw new IllegalStateException("Confirmation interceptor '" + rule.name() + "' requires decision.message for REPLY.");
        }
        if (stackPolicy != null) {
            for (String actionName : stackPolicy.popPreviousIfActionIn()) {
                requireKnownAction(actionsByName, actionName, "stack.popPreviousIfActionIn", rule.name());
            }
        }
        validateInterceptorTemplates(decision.params(), "decision.params", rule.name());
        validateInterceptorTemplates(decision.message(), "decision.message", rule.name());
    }

    private void validateInterceptorTemplates(Object raw, String field, String ruleName) {
        if (raw == null) {
            return;
        }
        if (raw instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                validateInterceptorTemplates(value, field, ruleName);
            }
            return;
        }
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                validateInterceptorTemplates(item, field, ruleName);
            }
            return;
        }
        if (!(raw instanceof String text) || !StringUtils.hasText(text)) {
            return;
        }
        Matcher matcher = INTERCEPTION_TEMPLATE_PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            validateInterceptorTemplateExpression(matcher.group(1), field, ruleName);
        }
    }

    private void validateInterceptorTemplateExpression(String expression, String field, String ruleName) {
        if (!StringUtils.hasText(expression)) {
            throw new IllegalStateException("Confirmation interceptor '" + ruleName + "' has an empty template placeholder in " + field + ".");
        }
        String normalized = expression.trim();
        int fallbackSeparator = normalized.indexOf('|');
        String path = fallbackSeparator >= 0 ? normalized.substring(0, fallbackSeparator).trim() : normalized;
        if (path.startsWith("pending.actionParams.")) {
            if (!StringUtils.hasText(path.substring("pending.actionParams.".length()))) {
                throw new IllegalStateException("Confirmation interceptor '" + ruleName + "' has an invalid pending placeholder in " + field + ".");
            }
            return;
        }
        if (path.startsWith("stack.previous.actionParams.")) {
            if (!StringUtils.hasText(path.substring("stack.previous.actionParams.".length()))) {
                throw new IllegalStateException("Confirmation interceptor '" + ruleName + "' has an invalid stack.previous placeholder in " + field + ".");
            }
            return;
        }
        throw new IllegalStateException("Confirmation interceptor '" + ruleName
            + "' uses unsupported template placeholder '{{" + normalized + "}}' in " + field + ".");
    }

    private void requireKnownAction(Map<String, ConnectorActionDefinition> actionsByName,
                                    String actionName,
                                    String field,
                                    String ruleName) {
        if (!StringUtils.hasText(actionName)) {
            throw new IllegalStateException("Confirmation interceptor '" + ruleName + "' requires " + field + ".");
        }
        if (!actionsByName.containsKey(normalizeName(actionName))) {
            throw new IllegalStateException("Confirmation interceptor '" + ruleName
                + "' references unknown action '" + actionName + "' in " + field + ".");
        }
    }

    private IntentType parseConfirmationIntentType(String raw, String label, String ruleName) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalStateException("Invalid action contract in " + label
                + " for confirmation interceptor '" + ruleName + "': trigger.confirmation is required.");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        try {
            IntentType intentType = IntentType.valueOf(normalized);
            if (intentType != IntentType.CONFIRMATION_POSITIVE && intentType != IntentType.CONFIRMATION_NEGATIVE) {
                throw new IllegalStateException("Invalid action contract in " + label
                    + " for confirmation interceptor '" + ruleName
                    + "': trigger.confirmation must be CONFIRMATION_POSITIVE or CONFIRMATION_NEGATIVE.");
            }
            return intentType;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid action contract in " + label
                + " for confirmation interceptor '" + ruleName
                + "': trigger.confirmation must be CONFIRMATION_POSITIVE or CONFIRMATION_NEGATIVE.");
        }
    }

    private ConfirmationInterceptorDecisionType parseDecisionType(String raw, String label, String ruleName) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalStateException("Invalid action contract in " + label
                + " for confirmation interceptor '" + ruleName + "': decision.type is required.");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return ConfirmationInterceptorDecisionType.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid action contract in " + label
                + " for confirmation interceptor '" + ruleName
                + "': decision.type must be PROMPT_ACTION, EXECUTE_ACTION, or REPLY.");
        }
    }

    private ActionAccessMode parseAccessMode(String raw, String label, String actionName) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalStateException("Invalid action contract in " + label + " for action '" + actionName + "': accessMode is required.");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return ActionAccessMode.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid action contract in " + label + " for action '" + actionName
                + "': accessMode must be one of READ, READ_WRITE, WRITE_ONLY (got '" + raw + "').");
        }
    }

    private AIActionParamType parseParamType(String raw, String label, String actionName, String paramName) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return AIActionParamType.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid action contract in " + label + " for action '" + actionName
                + "', param '" + paramName + "': unsupported type '" + raw + "'.");
        }
    }

    private String readString(Map<String, Object> map, String key) {
        if (map == null || !StringUtils.hasText(key)) {
            return null;
        }
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String s = value.toString();
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    private boolean readBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        if (map == null || !StringUtils.hasText(key)) {
            return defaultValue;
        }
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String s = value.toString();
        if (!StringUtils.hasText(s)) {
            return defaultValue;
        }
        return Boolean.parseBoolean(s.trim());
    }

    private Map<String, Object> readObjectMap(Object raw, String label, String field) {
        Map<String, Object> map = readOptionalObjectMap(raw, label, field);
        if (map.isEmpty()) {
            throw new IllegalStateException("Invalid action contract in " + label + ": " + field + " must be an object.");
        }
        return map;
    }

    private Map<String, Object> readOptionalObjectMap(Object raw, String label, String field) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Invalid action contract in " + label + ": " + field + " must be an object.");
        }
        return Collections.unmodifiableMap(toStringKeyedMap(map));
    }

    private List<String> readStringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String s = item.toString();
                if (StringUtils.hasText(s)) {
                    out.add(s.trim());
                }
            }
            return List.copyOf(out);
        }
        String s = raw.toString();
        if (!StringUtils.hasText(s)) {
            return List.of();
        }
        return List.of(s.trim());
    }

    private int readPositiveInt(Object raw,
                                String label,
                                String actionName,
                                String field,
                                int defaultValue) {
        int value = readInt(raw, label, actionName, field, defaultValue);
        if (value < 0) {
            throw new IllegalStateException("Invalid action contract in " + label + " for action '" + actionName
                + "': " + field + " must be zero or positive.");
        }
        return value;
    }

    private int readInt(Object raw,
                        String label,
                        String actionName,
                        String field,
                        int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Number number) {
            double d = number.doubleValue();
            if (!Double.isFinite(d) || d != Math.rint(d)) {
                throw new IllegalStateException("Invalid action contract in " + label + " for action '" + actionName
                    + "': " + field + " must be an integer.");
            }
            return number.intValue();
        }
        String s = raw.toString();
        if (!StringUtils.hasText(s)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Invalid action contract in " + label + " for action '" + actionName
                + "': " + field + " must be an integer.");
        }
    }

    private Long readLong(Object raw,
                          String label,
                          String actionName,
                          String paramName,
                          String field) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            double d = number.doubleValue();
            if (!Double.isFinite(d) || d != Math.rint(d)) {
                throw new IllegalStateException("Invalid action contract in " + label + " for action '" + actionName
                    + "', param '" + paramName + "': " + field + " must be an integer.");
            }
            return number.longValue();
        }
        String s = raw.toString();
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Invalid action contract in " + label + " for action '" + actionName
                + "', param '" + paramName + "': " + field + " must be an integer.");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toStringKeyedMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            out.put(String.valueOf(entry.getKey()), normalizeValue(entry.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object normalizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return toStringKeyedMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(normalizeValue(item));
            }
            return List.copyOf(out);
        }
        return value;
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record CatalogPart(
        List<ConnectorActionDefinition> actions,
        List<ConfirmationInterceptorRule> confirmationInterceptors,
        List<ConnectorWebhookTargetDefinition> webhookTargets
    ) {
    }
}
