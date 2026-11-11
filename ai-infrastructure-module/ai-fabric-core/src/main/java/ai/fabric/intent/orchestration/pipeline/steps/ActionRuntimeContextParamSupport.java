package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

import static ai.fabric.intent.orchestration.pipeline.steps.ActionContextLookupSupport.firstTextObject;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionContextLookupSupport.stringObject;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionContextLookupSupport.valueByCandidateKeys;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionContextSchemaSupport.hasMeaningfulActionParamValue;

/** Shared existing runtime bindings only; never invokes actions or attachment resolution. */
public final class ActionRuntimeContextParamSupport {
    private ActionRuntimeContextParamSupport() {}

    public static Map<String, Object> resolveConfiguredParameters(AIActionMetaData metadata,
                                                                 Map<String, Object> provided,
                                                                 OrchestrationContext context,
                                                                 PipelineContext pipelineContext) {
        Map<String, Object> resolved = new LinkedHashMap<>(provided == null ? Map.of() : provided);
        if (metadata != null && metadata.getParameterSchemas() != null) {
            metadata.getParameterSchemas().forEach((name, schema) -> {
                Map<String, Object> source = schema == null ? null : schema.getResolveFrom();
                if (!StringUtils.hasText(name) || source == null
                    || !"RUNTIME_CONTEXT".equalsIgnoreCase(stringObject(source.get("source")))) {
                    return;
                }
                String parameter = name.trim();
                // A model-provided value never supplies authority for a configured runtime binding.
                resolved.remove(parameter);
                Object value = resolveValue(parameter, source, context, pipelineContext);
                if (hasMeaningfulActionParamValue(value)) {
                    resolved.put(parameter, value);
                }
            });
        }
        return Collections.unmodifiableMap(resolved);
    }

    static Object resolveValue(String parameter, Map<String, Object> resolveFrom,
                               OrchestrationContext context, PipelineContext pipelineContext) {
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
}
