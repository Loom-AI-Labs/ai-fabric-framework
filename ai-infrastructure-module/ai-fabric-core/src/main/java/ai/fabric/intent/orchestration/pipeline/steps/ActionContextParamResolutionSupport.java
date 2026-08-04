package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.invocation.ActionConfirmationState;
import ai.fabric.intent.action.invocation.DefaultGovernedActionInvocationService;
import ai.fabric.intent.action.invocation.GovernedActionInvocationOutcome;
import ai.fabric.intent.action.invocation.GovernedActionInvocationSupport;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.attachment.NormalizedAttachment;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.pipeline.steps.ActionParamValidationSupport.ActionParamValidation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ai.fabric.intent.orchestration.pipeline.steps.ActionContextLookupSupport.attachmentContextCandidateKeys;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionContextLookupSupport.collectActionParameterNames;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionContextLookupSupport.firstTextObject;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionContextLookupSupport.metadataValueByCandidateKeys;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionContextLookupSupport.resolveParamCandidateKeys;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionContextLookupSupport.resolveResultPaths;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionContextLookupSupport.stringObject;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionContextLookupSupport.valueByCandidateKeys;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionContextLookupSupport.valueByPath;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionContextSchemaSupport.hasMeaningfulActionParamValue;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionContextSchemaSupport.normalizeResolvedActionParamValue;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionContextSchemaSupport.shouldResolveConfiguredActionParam;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParamValidationSupport.validateRequiredActionParams;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParameterSupport.isSystemContextParameter;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParameterSupport.isUserVisibleActionParameter;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParameterSupport.paramSchema;

@Slf4j
final class ActionContextParamResolutionSupport {

    private static final String PARAM_RESOLVE_SOURCE_RUNTIME_CONTEXT = "RUNTIME_CONTEXT";
    private static final String PARAM_RESOLVE_SOURCE_ATTACHMENT_METADATA = "ATTACHMENT_METADATA";
    private static final String PARAM_RESOLVE_SOURCE_OWNED_RESOURCE = "OWNED_RESOURCE";
    private static final String PARAM_RESOLVE_SOURCE_READ_ACTION = "READ_ACTION";

    private final AIActionRegistry actionHandlerRegistry;

    ActionContextParamResolutionSupport(AIActionRegistry actionHandlerRegistry) {
        this.actionHandlerRegistry = actionHandlerRegistry;
    }

    ResolvedActionParams resolveContextActionParams(AIActionMetaData meta,
                                                    Map<String, Object> effectiveParams,
                                                    OrchestrationContext context,
                                                    PipelineContext pipelineContext) {
        return resolveContextActionParams(meta, effectiveParams, context, pipelineContext, 0);
    }

    private ResolvedActionParams resolveContextActionParams(AIActionMetaData meta,
                                                            Map<String, Object> effectiveParams,
                                                            OrchestrationContext context,
                                                            PipelineContext pipelineContext,
                                                            int depth) {
        if (meta == null) {
            return new ResolvedActionParams(effectiveParams, Set.of(), null);
        }
        Map<String, Object> params = effectiveParams != null ? effectiveParams : new LinkedHashMap<>();
        Set<String> paramNames = collectActionParameterNames(meta);
        if (paramNames.isEmpty()) {
            return new ResolvedActionParams(params, Set.of(), null);
        }
        Map<String, Object> updated = null;
        Map<String, Object> current = params;
        LinkedHashSet<String> resolvedParameters = new LinkedHashSet<>();
        for (String parameter : paramNames) {
            if (!StringUtils.hasText(parameter)) {
                continue;
            }
            AIActionParamSchema schema = paramSchema(meta, parameter);
            Object existingValue = valueByCandidateKeys(current, List.of(parameter));
            boolean hasExistingValue = hasMeaningfulActionParamValue(existingValue);
            boolean shouldResolve = !hasExistingValue || shouldResolveConfiguredActionParam(parameter, schema, existingValue);
            if (!shouldResolve) {
                continue;
            }
            Object resolved = resolveConfiguredActionParam(parameter, schema, current, context, pipelineContext, depth);
            if (resolved instanceof BlockingReadActionResult blockingReadActionResult) {
                return new ResolvedActionParams(
                    updated != null ? updated : params,
                    resolvedParameters.isEmpty() ? Set.of() : Collections.unmodifiableSet(resolvedParameters),
                    blockingReadActionResult
                );
            }
            if (!hasMeaningfulActionParamValue(resolved)
                && isSystemContextParameter(parameter)
                && context != null
                && StringUtils.hasText(context.getSessionId())
                && "shopperSessionId".equals(parameter.trim())) {
                resolved = context.getSessionId().trim();
            }
            if (!hasMeaningfulActionParamValue(resolved)
                && isUserVisibleActionParameter(meta, parameter)) {
                resolved = resolveAttachmentContextParam(context, parameter);
            }
            if (!hasMeaningfulActionParamValue(resolved)) {
                if (hasExistingValue && shouldResolveConfiguredActionParam(parameter, schema, existingValue)) {
                    if (updated == null) {
                        updated = new LinkedHashMap<>(params);
                    }
                    updated.remove(parameter.trim());
                    current = updated;
                }
                continue;
            }
            if (updated == null) {
                updated = new LinkedHashMap<>(params);
            }
            updated.put(parameter.trim(), normalizeResolvedActionParamValue(resolved, schema));
            resolvedParameters.add(parameter.trim());
            current = updated;
        }
        return new ResolvedActionParams(
            updated != null ? updated : params,
            resolvedParameters.isEmpty() ? Set.of() : Collections.unmodifiableSet(resolvedParameters),
            null
        );
    }

    private Object resolveConfiguredActionParam(String parameter,
                                                AIActionParamSchema schema,
                                                Map<String, Object> currentParams,
                                                OrchestrationContext context,
                                                PipelineContext pipelineContext,
                                                int depth) {
        Map<String, Object> resolveFrom = schema != null ? schema.getResolveFrom() : null;
        if (resolveFrom == null || resolveFrom.isEmpty()) {
            return null;
        }
        String source = stringObject(resolveFrom.get("source"));
        if (!StringUtils.hasText(source)) {
            return null;
        }
        String normalizedSource = source.trim().toUpperCase(Locale.ROOT);
        if (PARAM_RESOLVE_SOURCE_RUNTIME_CONTEXT.equals(normalizedSource)) {
            return resolveRuntimeContextValue(parameter, resolveFrom, context, pipelineContext);
        }
        if (PARAM_RESOLVE_SOURCE_ATTACHMENT_METADATA.equals(normalizedSource)) {
            return resolveAttachmentContextParam(context, parameter, resolveParamCandidateKeys(parameter, resolveFrom));
        }
        if (PARAM_RESOLVE_SOURCE_OWNED_RESOURCE.equals(normalizedSource)) {
            Object value = resolveOwnedResourceParam(parameter, resolveFrom, context, pipelineContext);
            return hasMeaningfulActionParamValue(value)
                ? value
                : resolveAttachmentContextParam(context, parameter, resolveParamCandidateKeys(parameter, resolveFrom));
        }
        if (PARAM_RESOLVE_SOURCE_READ_ACTION.equals(normalizedSource)) {
            return resolveParamFromReadAction(parameter, resolveFrom, currentParams, context, pipelineContext, depth);
        }
        return null;
    }

    private Object resolveParamFromReadAction(String parameter,
                                              Map<String, Object> resolveFrom,
                                              Map<String, Object> currentParams,
                                              OrchestrationContext context,
                                              PipelineContext pipelineContext,
                                              int depth) {
        if (depth > 0) {
            return null;
        }
        String actionName = firstTextObject(
            resolveFrom.get("action"),
            resolveFrom.get("actionName"),
            resolveFrom.get("readAction")
        );
        if (!StringUtils.hasText(actionName)) {
            return null;
        }
        Optional<AIActionMetaData> readMetaOptional = actionHandlerRegistry.findMetadata(actionName.trim());
        AIActionMetaData readMeta = readMetaOptional != null ? readMetaOptional.orElse(null) : null;
        if (readMeta == null || readMeta.getAccessMode() != ActionAccessMode.READ) {
            return null;
        }
        OrchestrationPolicy policy = pipelineContext != null ? pipelineContext.getOrchestrationPolicy() : null;
        if (!ReadActionResolutionSupport.isActionExecutionAllowedByPolicy(actionName.trim(), readMeta, policy)) {
            return null;
        }
        Optional<AIActionHandler> readHandlerOptional = actionHandlerRegistry.findHandler(actionName.trim());
        AIActionHandler readHandler = readHandlerOptional != null ? readHandlerOptional.orElse(null) : null;
        if (readHandler == null) {
            return null;
        }
        Map<String, Object> readParams = Map.of();
        Object rawParams = resolveFrom.get("params");
        if (rawParams instanceof Map<?, ?> map && !map.isEmpty()) {
            readParams = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry != null && entry.getKey() != null && entry.getValue() != null) {
                    Object value = resolveReadActionParamTemplate(entry.getValue(), currentParams, context, pipelineContext);
                    if (hasMeaningfulActionParamValue(value)) {
                        readParams.put(entry.getKey().toString(), value);
                    }
                }
            }
        }
        ResolvedActionParams resolvedReadParams = resolveContextActionParams(
            readMeta,
            readParams,
            context,
            pipelineContext,
            depth + 1
        );
        readParams = resolvedReadParams.params();
        ActionContext readContext = new ActionContext(context, pipelineContext, readParams);
        if (!readHandler.validateActionAllowed(readContext)) {
            return null;
        }
        ActionParamValidation validation = validateRequiredActionParams(
            readMeta,
            readParams,
            pipelineContext,
            resolvedReadParams.resolvedParameters()
        );
        if (validation != null && validation.missingRequired() != null && !validation.missingRequired().isEmpty()) {
            return null;
        }
        GovernedActionInvocationOutcome invocationOutcome =
            new DefaultGovernedActionInvocationService(actionHandlerRegistry).invoke(
                GovernedActionInvocationSupport.invocation(
                    actionName,
                    readParams,
                    readContext,
                    actionHandlerRegistry,
                    ActionConfirmationState.CONFIRMED,
                    List.of()
                )
            );
        ActionResult result = invocationOutcome.actionResult();
        if (result == null) {
            return null;
        }
        if (!result.isSuccess()) {
            return new BlockingReadActionResult(actionName.trim(), result);
        }
        if (result.getData() == null) {
            return null;
        }
        List<Map<String, Object>> resultRoots = new ArrayList<>();
        try {
            Optional<Map<String, Object>> facts = readHandler.buildPostActionLlmFacts(result, readContext);
            if (facts != null && facts.isPresent() && facts.get() != null && !facts.get().isEmpty()) {
                resultRoots.add(facts.get());
            }
        } catch (Exception ex) {
            log.debug("Read action {} facts were unavailable while resolving action param {}: {}",
                actionName, parameter, ex.getMessage());
        }
        Map<String, Object> data = result.getData().toMap();
        if (data != null && !data.isEmpty()) {
            resultRoots.add(data);
        }
        List<String> paths = resolveResultPaths(parameter, resolveFrom);
        for (String path : paths) {
            for (Map<String, Object> root : resultRoots) {
                Object value = valueByPath(root, path);
                if (hasMeaningfulActionParamValue(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private Object resolveReadActionParamTemplate(Object raw,
                                                  Map<String, Object> currentParams,
                                                  OrchestrationContext context,
                                                  PipelineContext pipelineContext) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry != null && entry.getKey() != null) {
                    Object value = resolveReadActionParamTemplate(entry.getValue(), currentParams, context, pipelineContext);
                    if (hasMeaningfulActionParamValue(value)) {
                        resolved.put(entry.getKey().toString(), value);
                    }
                }
            }
            return resolved.isEmpty() ? null : Collections.unmodifiableMap(resolved);
        }
        if (raw instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>();
            for (Object item : list) {
                Object value = resolveReadActionParamTemplate(item, currentParams, context, pipelineContext);
                if (hasMeaningfulActionParamValue(value)) {
                    resolved.add(value);
                }
            }
            return resolved.isEmpty() ? null : Collections.unmodifiableList(resolved);
        }
        if (!(raw instanceof CharSequence text)) {
            return raw;
        }
        String value = text.toString();
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Map<String, Object> contextValues = new LinkedHashMap<>();
        contextValues.put("context.originalQuery", pipelineContext != null ? nullToEmpty(pipelineContext.getOriginalQuery()) : "");
        contextValues.put("originalQuery", pipelineContext != null ? nullToEmpty(pipelineContext.getOriginalQuery()) : "");
        contextValues.put("query", pipelineContext != null ? nullToEmpty(pipelineContext.getEffectiveQuery()) : "");
        contextValues.put("context.effectiveQuery", pipelineContext != null ? nullToEmpty(pipelineContext.getEffectiveQuery()) : "");
        contextValues.put("sessionId", context != null ? nullToEmpty(context.getSessionId()) : "");
        contextValues.put("context.sessionId", context != null ? nullToEmpty(context.getSessionId()) : "");
        contextValues.put("position", context != null ? nullToEmpty(context.getPosition()) : "");
        contextValues.put("context.position", context != null ? nullToEmpty(context.getPosition()) : "");
        contextValues.put("mode", context != null ? nullToEmpty(context.getMode()) : "");
        contextValues.put("context.mode", context != null ? nullToEmpty(context.getMode()) : "");
        if (currentParams != null && !currentParams.isEmpty()) {
            currentParams.forEach((paramName, paramValue) -> {
                if (StringUtils.hasText(paramName) && hasMeaningfulActionParamValue(paramValue)) {
                    contextValues.put("params." + paramName.trim(), paramValue);
                }
            });
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
            String key = trimmed.substring(2, trimmed.length() - 2).trim();
            Object exact = resolveTemplateExpressionValue(key, contextValues, currentParams);
            if (hasMeaningfulActionParamValue(exact)) {
                return exact;
            }
        }
        String resolved = value;
        for (Map.Entry<String, Object> entry : contextValues.entrySet()) {
            resolved = resolved.replace("{{" + entry.getKey() + "}}", entry.getValue() != null ? entry.getValue().toString() : "");
        }
        return StringUtils.hasText(resolved) ? resolved.trim() : null;
    }

    private Object resolveTemplateExpressionValue(String expression,
                                                  Map<String, Object> contextValues,
                                                  Map<String, Object> currentParams) {
        if (!StringUtils.hasText(expression) || contextValues == null || contextValues.isEmpty()) {
            return null;
        }
        for (String candidate : expression.split("\\|")) {
            if (!StringUtils.hasText(candidate)) {
                continue;
            }
            Object value = contextValues.get(candidate.trim());
            if (hasMeaningfulActionParamValue(value)) {
                return value;
            }
            String key = candidate.trim();
            if (key.startsWith("params.") && currentParams != null && !currentParams.isEmpty()) {
                value = valueByPath(currentParams, key.substring("params.".length()));
                if (hasMeaningfulActionParamValue(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private Object resolveRuntimeContextValue(String parameter,
                                              Map<String, Object> resolveFrom,
                                              OrchestrationContext context,
                                              PipelineContext pipelineContext) {
        String field = firstTextObject(resolveFrom.get("field"), resolveFrom.get("contextField"), parameter);
        if (!StringUtils.hasText(field)) {
            return null;
        }
        String normalized = field.trim();
        if ("shopperSessionId".equals(normalized) || "sessionId".equals(normalized)) {
            return context != null ? context.getSessionId() : null;
        }
        if ("userId".equals(normalized)) {
            return context != null ? context.getUserId() : null;
        }
        if ("conversationId".equals(normalized)) {
            return context != null ? context.getConversationId() : null;
        }
        if ("requestId".equals(normalized)) {
            return pipelineContext != null && StringUtils.hasText(pipelineContext.getRequestId())
                ? pipelineContext.getRequestId()
                : context != null ? context.getRequestId() : null;
        }
        if ("position".equals(normalized)) {
            return context != null ? context.getPosition() : null;
        }
        if ("mode".equals(normalized)) {
            return context != null ? context.getMode() : null;
        }
        Object value = valueByCandidateKeys(pipelineContext != null ? pipelineContext.getMetadata() : null, List.of(normalized));
        if (hasMeaningfulActionParamValue(value)) {
            return value;
        }
        return valueByCandidateKeys(context != null ? context.getMetadata() : null, List.of(normalized));
    }

    private Object resolveOwnedResourceParam(String parameter,
                                             Map<String, Object> resolveFrom,
                                             OrchestrationContext context,
                                             PipelineContext pipelineContext) {
        List<String> candidateKeys = resolveParamCandidateKeys(parameter, resolveFrom);
        Object value = valueByCandidateKeys(pipelineContext != null ? pipelineContext.getMetadata() : null, candidateKeys);
        if (hasMeaningfulActionParamValue(value)) {
            return value;
        }
        value = valueByCandidateKeys(context != null ? context.getMetadata() : null, candidateKeys);
        if (hasMeaningfulActionParamValue(value)) {
            return value;
        }
        value = ownedResourceValue(pipelineContext != null ? pipelineContext.getMetadata() : null, resolveFrom, candidateKeys);
        if (hasMeaningfulActionParamValue(value)) {
            return value;
        }
        return ownedResourceValue(context != null ? context.getMetadata() : null, resolveFrom, candidateKeys);
    }

    private Object ownedResourceValue(Map<String, Object> metadata,
                                      Map<String, Object> resolveFrom,
                                      List<String> candidateKeys) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object rawOwned = valueByCandidateKeys(metadata, List.of("ownedResources", "owned_resources"));
        if (rawOwned == null) {
            return null;
        }
        String resourceType = stringObject(resolveFrom.get("resourceType"));
        if (rawOwned instanceof Map<?, ?> map) {
            Object scoped = StringUtils.hasText(resourceType)
                ? valueByCandidateKeys(map, List.of(resourceType.trim()))
                : null;
            if (scoped == null) {
                scoped = map;
            }
            Object value = valueByCandidateKeys(scoped, candidateKeys);
            if (hasMeaningfulActionParamValue(value)) {
                return value;
            }
            if (scoped instanceof Iterable<?> iterable) {
                return ownedResourceValueFromIterable(iterable, resolveFrom, candidateKeys);
            }
        }
        if (rawOwned instanceof Iterable<?> iterable) {
            return ownedResourceValueFromIterable(iterable, resolveFrom, candidateKeys);
        }
        return null;
    }

    private Object ownedResourceValueFromIterable(Iterable<?> iterable,
                                                  Map<String, Object> resolveFrom,
                                                  List<String> candidateKeys) {
        if (iterable == null) {
            return null;
        }
        String resourceType = stringObject(resolveFrom.get("resourceType"));
        String scope = stringObject(resolveFrom.get("scope"));
        for (Object item : iterable) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            if (StringUtils.hasText(resourceType)) {
                Object actualType = valueByCandidateKeys(map, List.of("resourceType", "type"));
                if (actualType != null && !resourceType.trim().equalsIgnoreCase(actualType.toString().trim())) {
                    continue;
                }
            }
            if (StringUtils.hasText(scope)) {
                Object actualScope = valueByCandidateKeys(map, List.of("scope"));
                if (actualScope != null && !scope.trim().equalsIgnoreCase(actualScope.toString().trim())) {
                    continue;
                }
            }
            Object value = valueByCandidateKeys(map, candidateKeys);
            if (hasMeaningfulActionParamValue(value)) {
                return value;
            }
        }
        return null;
    }

    private String resolveAttachmentContextParam(OrchestrationContext context, String required) {
        return resolveAttachmentContextParam(context, required, attachmentContextCandidateKeys(required));
    }

    private String resolveAttachmentContextParam(OrchestrationContext context, String required, List<String> candidateKeys) {
        if (context == null || !StringUtils.hasText(required)) {
            return null;
        }
        List<NormalizedAttachment> attachments = context.getAttachmentsNormalized();
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        List<String> effectiveCandidateKeys = candidateKeys == null || candidateKeys.isEmpty()
            ? attachmentContextCandidateKeys(required)
            : candidateKeys;
        for (NormalizedAttachment attachment : attachments) {
            String value = metadataValueByCandidateKeys(attachment != null ? attachment.getMetadata() : null, effectiveCandidateKeys);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        if (attachments.size() == 1 && required.trim().toLowerCase(Locale.ROOT).endsWith("_id")) {
            NormalizedAttachment only = attachments.get(0);
            if (only != null && StringUtils.hasText(only.getId())) {
                return only.getId();
            }
        }
        return null;
    }

    record ResolvedActionParams(
        Map<String, Object> params,
        Set<String> resolvedParameters,
        BlockingReadActionResult blockingReadActionResult
    ) {
    }

    record BlockingReadActionResult(
        String actionName,
        ActionResult result
    ) {
    }
}
