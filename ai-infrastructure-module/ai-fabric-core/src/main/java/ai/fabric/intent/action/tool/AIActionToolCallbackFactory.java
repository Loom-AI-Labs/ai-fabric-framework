package ai.fabric.intent.action.tool;

import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionNames;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.invocation.ActionConfirmationState;
import ai.fabric.intent.action.invocation.DefaultGovernedActionInvocationService;
import ai.fabric.intent.action.invocation.GovernedActionInvocation;
import ai.fabric.intent.action.invocation.GovernedActionInvocationOutcome;
import ai.fabric.intent.action.invocation.GovernedActionInvocationService;
import ai.fabric.intent.action.invocation.GovernedActionInvocationSupport;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.capability.CapabilityAwareActionCatalog;
import ai.fabric.intent.orchestration.capability.DefaultEffectiveCapabilitiesResolver;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public class AIActionToolCallbackFactory {

    public static final String PARAM_ACTION_TOOL_ENABLED = "__aiFabricActionToolsEnabled";
    public static final String PARAM_ACTION_TOOL_CONTEXT = "__aiFabricActionToolContext";
    public static final String PARAM_ACTION_TOOL_NAMES = "__aiFabricActionToolNames";

    private static final String CONFIRMATION_ACCEPTED_PARAMETER = "confirmationAccepted";
    private static final Set<String> SYSTEM_CONTEXT_PARAMETER_NAMES = Set.of(
        "shopperSessionId",
        CONFIRMATION_ACCEPTED_PARAMETER
    );
    private static final Set<String> HIDDEN_VISIBILITY = Set.of("INTERNAL", "SECRET", "SYSTEM");

    private final Supplier<AIActionRegistry> actionRegistrySupplier;
    private final ObjectMapper objectMapper;

    public AIActionToolCallbackFactory(AIActionRegistry actionRegistry, ObjectMapper objectMapper) {
        this(() -> actionRegistry, objectMapper);
    }

    public AIActionToolCallbackFactory(Supplier<AIActionRegistry> actionRegistrySupplier, ObjectMapper objectMapper) {
        this.actionRegistrySupplier = actionRegistrySupplier != null ? actionRegistrySupplier : () -> null;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
    }

    public static Map<String, Object> requestParameters(ActionContext actionContext, Collection<String> actionNames) {
        return requestParameters(Map.of(), actionContext, actionNames);
    }

    public static Map<String, Object> requestParameters(Map<String, Object> existing,
                                                        ActionContext actionContext,
                                                        Collection<String> actionNames) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (existing != null && !existing.isEmpty()) {
            existing.forEach((key, value) -> {
                if (StringUtils.hasText(key) && value != null) {
                    parameters.put(key.trim(), value);
                }
            });
        }
        parameters.put(PARAM_ACTION_TOOL_ENABLED, true);
        if (actionContext != null) {
            parameters.put(PARAM_ACTION_TOOL_CONTEXT, actionContext);
        }
        List<String> names = normalizeActionNames(actionNames);
        if (!names.isEmpty()) {
            parameters.put(PARAM_ACTION_TOOL_NAMES, names);
        }
        return Map.copyOf(parameters);
    }

    public static boolean isActionToolBridgeEnabled(Map<String, Object> parameters) {
        Object value = parameters != null ? parameters.get(PARAM_ACTION_TOOL_ENABLED) : null;
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value instanceof String text && Boolean.parseBoolean(text.trim());
    }

    public static Optional<ActionContext> actionContextFrom(Map<String, Object> parameters) {
        Object value = parameters != null ? parameters.get(PARAM_ACTION_TOOL_CONTEXT) : null;
        return value instanceof ActionContext actionContext ? Optional.of(actionContext) : Optional.empty();
    }

    public static List<String> actionNamesFrom(Map<String, Object> parameters) {
        Object value = parameters != null ? parameters.get(PARAM_ACTION_TOOL_NAMES) : null;
        if (value instanceof Collection<?> collection) {
            return normalizeActionNames(collection);
        }
        if (value instanceof String text) {
            return normalizeActionNames(List.of(text.split(",")));
        }
        return List.of();
    }

    public List<ToolCallback> createCallbacks(ActionContext actionContext) {
        AIActionRegistry actionRegistry = resolveActionRegistry();
        if (actionRegistry == null) {
            return List.of();
        }
        ActionContext effectiveContext = actionContext != null
            ? actionContext
            : new ActionContext(OrchestrationContext.anonymous(), null, Map.of());
        EffectiveCapabilityProfile profile = GovernedActionInvocationSupport.effectiveProfile(
            actionRegistry,
            effectiveContext.pipelineContext(),
            effectiveContext.orchestrationContext()
        );
        List<AIActionMetaData> metadata = new CapabilityAwareActionCatalog(actionRegistry)
            .listVisibleActions(profile);
        if (metadata == null || metadata.isEmpty()) {
            return List.of();
        }
        List<ToolCallback> callbacks = new ArrayList<>();
        for (AIActionMetaData meta : metadata) {
            if (meta == null || !StringUtils.hasText(meta.getName())) {
                continue;
            }
            actionRegistry.findHandler(meta.getName())
                .map(handler -> createCallback(
                    handler,
                    effectiveContext,
                    new DefaultGovernedActionInvocationService(actionRegistry),
                    profile
                ))
                .ifPresent(callbacks::add);
        }
        return List.copyOf(callbacks);
    }

    public Optional<ToolCallback> createCallback(String actionName, ActionContext actionContext) {
        if (!StringUtils.hasText(actionName)) {
            return Optional.empty();
        }
        AIActionRegistry actionRegistry = resolveActionRegistry();
        if (actionRegistry == null) {
            return Optional.empty();
        }
        ActionContext effectiveContext = actionContext != null
            ? actionContext
            : new ActionContext(OrchestrationContext.anonymous(), null, Map.of());
        EffectiveCapabilityProfile profile = GovernedActionInvocationSupport.effectiveProfile(
            actionRegistry,
            effectiveContext.pipelineContext(),
            effectiveContext.orchestrationContext()
        );
        if (!profile.isActionVisible(actionName)) {
            return Optional.empty();
        }
        return actionRegistry.findHandler(actionName)
            .map(handler -> createCallback(
                handler,
                effectiveContext,
                new DefaultGovernedActionInvocationService(actionRegistry),
                profile
            ));
    }

    private AIActionRegistry resolveActionRegistry() {
        return actionRegistrySupplier.get();
    }

    public ToolCallback createCallback(AIActionHandler handler, ActionContext actionContext) {
        if (handler == null || handler.getActionMetadata() == null
            || !StringUtils.hasText(handler.getActionMetadata().getName())) {
            throw new IllegalArgumentException("AIActionHandler and named action metadata are required");
        }
        ActionContext effectiveContext = actionContext != null
            ? actionContext
            : new ActionContext(OrchestrationContext.anonymous(), null, Map.of());
        OrchestrationPolicy policy = effectiveContext.pipelineContext() != null
            ? effectiveContext.pipelineContext().getOrchestrationPolicy()
            : effectiveContext.orchestrationContext().getOrchestrationPolicy();
        EffectiveCapabilityProfile profile = new DefaultEffectiveCapabilitiesResolver()
            .resolveLegacy(policy, List.of(handler.getActionMetadata()));
        return createCallback(
            handler,
            effectiveContext,
            new DefaultGovernedActionInvocationService(handler),
            profile
        );
    }

    private ToolCallback createCallback(
        AIActionHandler handler,
        ActionContext actionContext,
        GovernedActionInvocationService invocationService,
        EffectiveCapabilityProfile profile
    ) {
        return new AIActionToolCallback(handler, actionContext, invocationService, profile);
    }

    private static List<String> normalizeActionNames(Collection<?> actionNames) {
        if (actionNames == null || actionNames.isEmpty()) {
            return List.of();
        }
        return actionNames.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .map(String::trim)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    private final class AIActionToolCallback implements ToolCallback {

        private final AIActionHandler handler;
        private final AIActionMetaData metadata;
        private final ActionContext baseContext;
        private final GovernedActionInvocationService invocationService;
        private final EffectiveCapabilityProfile effectiveCapabilityProfile;
        private final ToolDefinition toolDefinition;

        private AIActionToolCallback(
            AIActionHandler handler,
            ActionContext baseContext,
            GovernedActionInvocationService invocationService,
            EffectiveCapabilityProfile effectiveCapabilityProfile
        ) {
            this.handler = handler;
            this.metadata = handler.getActionMetadata();
            this.invocationService = invocationService;
            this.effectiveCapabilityProfile = effectiveCapabilityProfile;
            this.baseContext = baseContext != null
                ? baseContext
                : new ActionContext(OrchestrationContext.anonymous(), null, Map.of());
            this.toolDefinition = ToolDefinition.builder()
                .name(toolName(metadata))
                .description(toolDescription(metadata))
                .inputSchema(inputSchema(metadata))
                .build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().returnDirect(false).build();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            Map<String, Object> modelParams;
            try {
                modelParams = parseToolInput(toolInput);
            } catch (Exception ex) {
                return resultJson(metadata, error("TOOL_INPUT_INVALID", "Tool input must be a JSON object."));
            }

            Map<String, Object> executableParams = executableParams(metadata, modelParams, baseContext.actionParams());
            ActionContext executionContext = baseContext.withActionParams(executableParams);

            List<String> missing = missingRequiredParameters(metadata, executableParams);
            if (!missing.isEmpty()) {
                return resultJson(metadata, missingRequiredResult(metadata, missing));
            }
            GovernedActionInvocationOutcome outcome =
                invocationService.invoke(
                    new GovernedActionInvocation(
                        metadata.getName(),
                        executableParams,
                        executionContext,
                        GovernedActionInvocationSupport.trustedContext(
                            executionContext.pipelineContext()
                        ),
                        effectiveCapabilityProfile,
                        ActionConfirmationState.NOT_CONFIRMED,
                        List.of()
                    )
                );
            return resultJson(metadata, outcome.actionResult());
        }
    }

    private Map<String, Object> parseToolInput(String toolInput) throws Exception {
        if (!StringUtils.hasText(toolInput)) {
            return Map.of();
        }
        Map<String, Object> parsed = objectMapper.readValue(toolInput, new TypeReference<>() {});
        if (parsed == null || parsed.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        parsed.forEach((key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
                normalized.put(key.trim(), value);
            }
        });
        return Map.copyOf(normalized);
    }

    private Map<String, Object> executableParams(AIActionMetaData meta,
                                                 Map<String, Object> modelParams,
                                                 Map<String, Object> trustedParams) {
        Map<String, Object> executable = new LinkedHashMap<>();
        if (modelParams != null) {
            modelParams.forEach((key, value) -> {
                String canonicalName = canonicalDeclaredParameterName(meta, key);
                if (StringUtils.hasText(canonicalName) && value != null && isUserVisibleParameter(meta, canonicalName)) {
                    executable.put(canonicalName, value);
                }
            });
        }
        if (trustedParams != null) {
            trustedParams.forEach((key, value) -> {
                if (StringUtils.hasText(key) && value != null) {
                    executable.put(key.trim(), value);
                }
            });
        }
        return executable.isEmpty() ? Map.of() : Map.copyOf(executable);
    }

    private String inputSchema(AIActionMetaData meta) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, AIActionParamSchema> schemas = meta.getParameterSchemas() != null
            ? meta.getParameterSchemas()
            : Map.of();
        Map<String, String> descriptions = meta.getParameters() != null ? meta.getParameters() : Map.of();

        if (!schemas.isEmpty()) {
            schemas.forEach((name, schema) -> {
                if (isUserVisibleParameter(meta, name)) {
                    properties.put(name, schemaFor(name, schema, descriptions.get(name)));
                }
            });
        } else {
            descriptions.forEach((name, description) -> {
                if (isUserVisibleParameter(meta, name)) {
                    properties.put(name, Map.of(
                        "type", "string",
                        "description", description != null ? description : name
                    ));
                }
            });
        }

        root.put("properties", properties);
        List<String> required = publicRequiredParameters(meta);
        if (!required.isEmpty()) {
            root.put("required", required);
        }
        root.put("additionalProperties", false);
        return toJson(root);
    }

    private Map<String, Object> schemaFor(String name, AIActionParamSchema schema, String fallbackDescription) {
        Map<String, Object> value = new LinkedHashMap<>();
        AIActionParamType type = schema != null && schema.getType() != null ? schema.getType() : AIActionParamType.STRING;
        value.put("type", jsonType(type));
        String description = schema != null && StringUtils.hasText(schema.getDescription())
            ? schema.getDescription().trim()
            : fallbackDescription;
        if (StringUtils.hasText(description)) {
            value.put("description", description.trim());
        }
        if (schema != null) {
            if (StringUtils.hasText(schema.getPattern())) {
                value.put("pattern", schema.getPattern().trim());
            }
            if (schema.getAllowedValues() != null && !schema.getAllowedValues().isEmpty()) {
                value.put("enum", schema.getAllowedValues());
            }
            if (schema.getMin() != null) {
                value.put("minimum", schema.getMin());
            }
            if (schema.getMax() != null) {
                value.put("maximum", schema.getMax());
            }
            if (schema.getDefaultValue() != null) {
                value.put("default", schema.getDefaultValue());
            }
            if (type == AIActionParamType.ARRAY) {
                value.put("items", schemaFor(name + "Item", schema.getItems(), null));
            }
            if (type == AIActionParamType.OBJECT && schema.getProperties() != null && !schema.getProperties().isEmpty()) {
                Map<String, Object> nested = new LinkedHashMap<>();
                schema.getProperties().forEach((childName, childSchema) ->
                    nested.put(childName, schemaFor(childName, childSchema, null)));
                value.put("properties", nested);
                value.put("additionalProperties", false);
                if (schema.getRequiredProperties() != null && !schema.getRequiredProperties().isEmpty()) {
                    value.put("required", schema.getRequiredProperties());
                }
            }
        }
        return Map.copyOf(value);
    }

    private String jsonType(AIActionParamType type) {
        return switch (type) {
            case INTEGER -> "integer";
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
            case OBJECT -> "object";
            case ARRAY -> "array";
            case STRING, UNKNOWN -> "string";
        };
    }

    private List<String> publicRequiredParameters(AIActionMetaData meta) {
        if (meta.getRequiredParameters() == null || meta.getRequiredParameters().isEmpty()) {
            return List.of();
        }
        return meta.getRequiredParameters().stream()
            .filter(parameter -> isUserVisibleParameter(meta, parameter))
            .sorted()
            .toList();
    }

    private List<String> missingRequiredParameters(AIActionMetaData meta, Map<String, Object> params) {
        if (meta.getRequiredParameters() == null || meta.getRequiredParameters().isEmpty()) {
            return List.of();
        }
        return meta.getRequiredParameters().stream()
            .filter(StringUtils::hasText)
            .filter(parameter -> !hasMeaningfulValue(params != null ? params.get(parameter) : null))
            .toList();
    }

    private boolean hasMeaningfulValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return StringUtils.hasText(text);
        }
        if (value instanceof Iterable<?> iterable) {
            return iterable.iterator().hasNext();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    private ActionResult missingRequiredResult(AIActionMetaData meta, List<String> missing) {
        List<String> publicMissing = missing.stream()
            .filter(parameter -> isUserVisibleParameter(meta, parameter))
            .toList();
        long hiddenMissing = missing.size() - publicMissing.size();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("actionName", meta.getName());
        data.put("missingRequired", publicMissing);
        if (hiddenMissing > 0) {
            data.put("hiddenContextMissingCount", hiddenMissing);
        }
        return ActionResult.builder()
            .success(false)
            .message(publicMissing.isEmpty()
                ? "Trusted context is missing required action data."
                : "Required action parameters are missing.")
            .errorCode("ACTION_PARAMETERS_MISSING")
            .data(ai.fabric.intent.action.ActionPayload.object(data))
            .build();
    }

    private boolean isUserVisibleParameter(AIActionMetaData meta, String parameter) {
        return !isHiddenParameter(meta, parameter);
    }

    private String canonicalDeclaredParameterName(AIActionMetaData meta, String parameter) {
        if (meta == null || !StringUtils.hasText(parameter)) {
            return null;
        }
        String normalized = parameter.trim();
        String schemaName = canonicalMapKey(meta.getParameterSchemas(), normalized);
        if (StringUtils.hasText(schemaName)) {
            return schemaName;
        }
        String descriptionName = canonicalMapKey(meta.getParameters(), normalized);
        if (StringUtils.hasText(descriptionName)) {
            return descriptionName;
        }
        return null;
    }

    private String canonicalMapKey(Map<?, ?> values, String parameter) {
        if (values == null || values.isEmpty() || !StringUtils.hasText(parameter)) {
            return null;
        }
        if (values.containsKey(parameter)) {
            return parameter;
        }
        for (Object key : values.keySet()) {
            if (key instanceof String text && text.trim().equalsIgnoreCase(parameter.trim())) {
                return text.trim();
            }
        }
        return null;
    }

    private boolean isHiddenParameter(AIActionMetaData meta, String parameter) {
        if (!StringUtils.hasText(parameter)) {
            return true;
        }
        if (SYSTEM_CONTEXT_PARAMETER_NAMES.contains(parameter.trim())) {
            return true;
        }
        AIActionParamSchema schema = paramSchema(meta, parameter);
        if (schema == null) {
            return false;
        }
        if (Boolean.FALSE.equals(schema.getAskUser())) {
            return true;
        }
        if (!StringUtils.hasText(schema.getVisibility())) {
            return false;
        }
        return HIDDEN_VISIBILITY.contains(schema.getVisibility().trim().toUpperCase(Locale.ROOT));
    }

    private AIActionParamSchema paramSchema(AIActionMetaData meta, String parameter) {
        if (meta == null || meta.getParameterSchemas() == null || meta.getParameterSchemas().isEmpty()) {
            return null;
        }
        AIActionParamSchema exact = meta.getParameterSchemas().get(parameter);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, AIActionParamSchema> entry : meta.getParameterSchemas().entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(parameter)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean isAnonymous(ActionContext context) {
        return context == null || context.isAnonymous();
    }

    private String toolName(AIActionMetaData meta) {
        return AIActionNames.normalize(meta.getName());
    }

    private String toolDescription(AIActionMetaData meta) {
        StringBuilder description = new StringBuilder();
        if (StringUtils.hasText(meta.getDescription())) {
            description.append(meta.getDescription().trim());
        } else if (StringUtils.hasText(meta.getDisplayName())) {
            description.append(meta.getDisplayName().trim());
        } else {
            description.append(meta.getName().trim());
        }
        if (meta.getAccessMode() != null) {
            description.append(" Access mode: ").append(meta.getAccessMode().name()).append(".");
        }
        if (meta.isConfirmationRequired()) {
            description.append(" Requires explicit user confirmation before execution.");
        }
        return description.toString();
    }

    private ActionResult error(String code, String message) {
        return ActionResult.builder()
            .success(false)
            .message(message)
            .errorCode(code)
            .build();
    }

    private ActionResult handleErrorSafely(AIActionHandler handler, Exception ex, ActionContext executionContext) {
        try {
            ActionResult handled = handler.handleError(ex, executionContext);
            return handled != null ? handled : error("ACTION_EXECUTION_FAILED", "Action failed.");
        } catch (Exception handlerError) {
            return error("ACTION_EXECUTION_FAILED", "Action failed.");
        }
    }

    private String resultJson(AIActionMetaData meta, ActionResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (meta != null && StringUtils.hasText(meta.getName())) {
            out.put("actionName", meta.getName().trim());
        }
        if (meta != null && StringUtils.hasText(meta.getName())) {
            out.put("toolName", toolName(meta));
        }
        out.put("success", result != null && result.isSuccess());
        if (StringUtils.hasText(result != null ? result.getMessage() : null)) {
            out.put("message", result.getMessage());
        }
        if (StringUtils.hasText(result != null ? result.getErrorCode() : null)) {
            out.put("errorCode", result.getErrorCode());
            if ("CONFIRMATION_REQUIRED".equals(result.getErrorCode())) {
                out.put("confirmationRequired", true);
            }
        }
        if (result != null && result.getData() != null) {
            out.put("data", result.getData().toMap());
        }
        if (result != null && result.getPinnedTargets() != null && !result.getPinnedTargets().isEmpty()) {
            out.put("pinnedTargets", result.getPinnedTargets());
        }
        return toJson(out);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{\"success\":false,\"errorCode\":\"ACTION_RESULT_SERIALIZATION_FAILED\"}";
        }
    }
}
