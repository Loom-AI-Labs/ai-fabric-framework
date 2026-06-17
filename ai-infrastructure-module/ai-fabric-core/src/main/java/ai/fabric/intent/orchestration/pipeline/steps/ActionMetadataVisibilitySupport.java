package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static ai.fabric.intent.orchestration.pipeline.steps.ActionParameterSupport.isUserVisibleActionParameter;

final class ActionMetadataVisibilitySupport {

    private ActionMetadataVisibilitySupport() {
    }

    static AIActionMetaData publicActionMetadata(AIActionMetaData metadata) {
        if (metadata == null) {
            return null;
        }
        Map<String, String> publicParameters = filterPublicParameterMap(metadata, metadata.getParameters());
        Map<String, AIActionParamSchema> publicSchemas =
            filterPublicParameterMap(metadata, metadata.getParameterSchemas());
        Set<String> publicRequired = metadata.getRequiredParameters() == null
            ? Set.of()
            : metadata.getRequiredParameters().stream()
                .filter(parameter -> isUserVisibleActionParameter(metadata, parameter))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return AIActionMetaData.builder()
            .name(metadata.getName())
            .displayName(metadata.getDisplayName())
            .description(metadata.getDescription())
            .category(metadata.getCategory())
            .accessMode(metadata.getAccessMode())
            .anonymousAllowed(metadata.isAnonymousAllowed())
            .confirmationRequired(metadata.isConfirmationRequired())
            .groundingEligible(metadata.isGroundingEligible())
            .readActionResolutionEligible(metadata.isReadActionResolutionEligible())
            .sideEffectLevel(metadata.getSideEffectLevel())
            .resultPresentationHint(metadata.getResultPresentationHint())
            .builtInModuleId(metadata.getBuiltInModuleId())
            .builtInCardId(metadata.getBuiltInCardId())
            .provenance(metadata.getProvenance())
            .parameters(publicParameters)
            .parameterSchemas(publicSchemas)
            .requiredParameters(publicRequired)
            .build();
    }

    static <T> Map<String, T> filterPublicParameterMap(AIActionMetaData metadata, Map<String, T> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, T> out = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (StringUtils.hasText(key) && isUserVisibleActionParameter(metadata, key)) {
                out.put(key, value);
            }
        });
        return Collections.unmodifiableMap(out);
    }

    static Map<String, Object> publicProvidedParameters(AIActionMetaData metadata, Map<String, Object> values) {
        return filterPublicParameterMap(metadata, values);
    }

    static boolean hasParamValue(Map<String, Object> params, String key) {
        if (params == null || !StringUtils.hasText(key)) {
            return false;
        }
        Object value = params.get(key);
        return ActionBatchSupport.hasMeaningfulBatchValue(value);
    }

    static boolean hasActionParameter(AIActionMetaData meta, String key) {
        if (meta == null || !StringUtils.hasText(key)) {
            return false;
        }
        String normalized = key.trim();
        if (meta.getRequiredParameters() != null
            && meta.getRequiredParameters().stream().anyMatch(parameter -> normalized.equals(parameter))) {
            return true;
        }
        return meta.getParameters() != null && meta.getParameters().containsKey(normalized);
    }

    static String getMetadataValueIgnoreCase(Map<String, String> metadata, String key) {
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
}
