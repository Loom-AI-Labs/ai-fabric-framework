package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Internal helpers for action parameter visibility and LLM placeholder rejection.
 */
final class ActionParameterSupport {

    static final String CONFIRMATION_ACCEPTED_PARAMETER = "confirmationAccepted";

    private static final Set<String> SYSTEM_CONTEXT_PARAMETER_NAMES = Set.of(
        "shopperSessionId",
        CONFIRMATION_ACCEPTED_PARAMETER
    );
    private static final String PARAM_VISIBILITY_INTERNAL = "INTERNAL";
    private static final String PARAM_VISIBILITY_SECRET = "SECRET";
    private static final String PARAM_VISIBILITY_SYSTEM = "SYSTEM";

    private ActionParameterSupport() {
    }

    static Set<String> normalizeParameterNameSet(Set<String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Set.of();
        }
        return parameters.stream()
            .filter(StringUtils::hasText)
            .map(parameter -> parameter.trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    }

    static boolean isSystemContextParameter(String required) {
        return StringUtils.hasText(required) && SYSTEM_CONTEXT_PARAMETER_NAMES.contains(required.trim());
    }

    static AIActionParamSchema paramSchema(AIActionMetaData meta, String parameter) {
        if (meta == null || meta.getParameterSchemas() == null || meta.getParameterSchemas().isEmpty() || !StringUtils.hasText(parameter)) {
            return null;
        }
        String normalized = parameter.trim();
        AIActionParamSchema exact = meta.getParameterSchemas().get(normalized);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, AIActionParamSchema> entry : meta.getParameterSchemas().entrySet()) {
            if (entry != null && StringUtils.hasText(entry.getKey()) && normalized.equalsIgnoreCase(entry.getKey().trim())) {
                return entry.getValue();
            }
        }
        return null;
    }

    static boolean isUserVisibleActionParameter(AIActionMetaData meta, String parameter) {
        return !isHiddenActionParameter(meta, parameter);
    }

    static boolean isHiddenActionParameter(AIActionMetaData meta, String parameter) {
        if (!StringUtils.hasText(parameter)) {
            return true;
        }
        if (isSystemContextParameter(parameter)) {
            return true;
        }
        AIActionParamSchema schema = paramSchema(meta, parameter);
        if (schema == null) {
            return false;
        }
        if (Boolean.FALSE.equals(schema.getAskUser())) {
            return true;
        }
        String visibility = schema.getVisibility();
        if (!StringUtils.hasText(visibility)) {
            return false;
        }
        String normalized = visibility.trim().toUpperCase(Locale.ROOT);
        return PARAM_VISIBILITY_INTERNAL.equals(normalized)
            || PARAM_VISIBILITY_SECRET.equals(normalized)
            || PARAM_VISIBILITY_SYSTEM.equals(normalized);
    }

    static boolean isConfirmationAcceptedParameter(String required) {
        return StringUtils.hasText(required) && CONFIRMATION_ACCEPTED_PARAMETER.equals(required.trim());
    }

    static boolean isPlaceholderOrInstructionEcho(String requiredParamName,
                                                  String rawValue,
                                                  AIActionMetaData meta,
                                                  String normalizedOriginalQuery) {
        String raw = rawValue != null ? rawValue.trim() : "";
        if (!StringUtils.hasText(raw)) {
            return true;
        }

        String lowered = raw.toLowerCase(Locale.ROOT);
        if (lowered.contains("required") || lowered.contains("optional") || lowered.contains("example") || lowered.contains("e.g")) {
            return true;
        }

        Map<String, String> descriptions = meta != null ? meta.getParameters() : null;
        String description = descriptions != null ? descriptions.get(requiredParamName) : null;
        if (StringUtils.hasText(description) && raw.equalsIgnoreCase(description.trim())) {
            return true;
        }

        if (StringUtils.hasText(requiredParamName) && raw.equalsIgnoreCase(requiredParamName.trim())) {
            return true;
        }

        if (StringUtils.hasText(normalizedOriginalQuery) && raw.equalsIgnoreCase(normalizedOriginalQuery)) {
            String originalLower = normalizedOriginalQuery.toLowerCase(Locale.ROOT);
            boolean looksLikeInstruction = false;
            if (meta != null && StringUtils.hasText(meta.getName())
                && originalLower.contains(meta.getName().toLowerCase(Locale.ROOT))) {
                looksLikeInstruction = true;
            }
            if (!looksLikeInstruction && descriptions != null && !descriptions.isEmpty()) {
                for (String key : descriptions.keySet()) {
                    if (StringUtils.hasText(key) && originalLower.contains(key.toLowerCase(Locale.ROOT))) {
                        looksLikeInstruction = true;
                        break;
                    }
                }
            }
            return looksLikeInstruction;
        }

        return false;
    }
}
