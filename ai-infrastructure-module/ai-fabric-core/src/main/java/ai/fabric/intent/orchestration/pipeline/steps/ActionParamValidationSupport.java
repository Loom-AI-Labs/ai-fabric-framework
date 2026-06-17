package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.steps.ActionExecutableValidationSupport.ActionExecutableValidation;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ai.fabric.intent.orchestration.pipeline.steps.ActionEvidenceSupport.buildEvidenceBundle;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParameterSupport.isConfirmationAcceptedParameter;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParameterSupport.isHiddenActionParameter;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParameterSupport.isPlaceholderOrInstructionEcho;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParameterSupport.isUserVisibleActionParameter;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParameterSupport.normalizeParameterNameSet;

/**
 * Internal helpers for required action parameter validation and user-safe validation metadata.
 */
final class ActionParamValidationSupport {

    private static final String METADATA_KEY_ACTION_PARAM_VALIDATION = "actionParamValidation";

    private ActionParamValidationSupport() {
    }

    record ActionParamValidation(
        List<String> missingRequired,
        List<String> provenanceMissing,
        Map<String, Object> debugMetadata
    ) {}

    static ActionParamValidation validateRequiredActionParams(AIActionMetaData meta,
                                                              Map<String, Object> params,
                                                              PipelineContext pipelineContext) {
        return validateRequiredActionParams(meta, params, pipelineContext, Set.of());
    }

    static ActionParamValidation validateRequiredActionParams(AIActionMetaData meta,
                                                              Map<String, Object> params,
                                                              PipelineContext pipelineContext,
                                                              Set<String> trustedResolvedParameters) {
        if (meta == null || meta.getRequiredParameters() == null || meta.getRequiredParameters().isEmpty()) {
            return null;
        }

        ActionEvidenceSupport.EvidenceBundle evidence = buildEvidenceBundle(pipelineContext);
        String userEvidenceLower = evidence != null && StringUtils.hasText(evidence.userEvidenceLower())
            ? evidence.userEvidenceLower()
            : "";
        String pinnedEvidenceLower = evidence != null && StringUtils.hasText(evidence.pinnedEvidenceLower())
            ? evidence.pinnedEvidenceLower()
            : "";

        String originalQuery = pipelineContext != null ? pipelineContext.getOriginalQuery() : null;
        String normalizedOriginalQuery = StringUtils.hasText(originalQuery) ? originalQuery.trim() : "";

        List<String> missing = new java.util.ArrayList<>();
        List<String> provenanceMissing = new java.util.ArrayList<>();
        Set<String> trustedResolved = normalizeParameterNameSet(trustedResolvedParameters);

        for (String required : meta.getRequiredParameters()) {
            if (!StringUtils.hasText(required)) {
                continue;
            }
            Object value = params != null ? params.get(required) : null;
            if (value == null) {
                missing.add(required);
                continue;
            }

            String raw = value.toString();
            if (!StringUtils.hasText(raw)) {
                missing.add(required);
                continue;
            }

            if (isPlaceholderOrInstructionEcho(required, raw, meta, normalizedOriginalQuery)) {
                missing.add(required);
                continue;
            }

            if (value instanceof String
                && !isHiddenActionParameter(meta, required)
                && !trustedResolved.contains(required.trim().toLowerCase(Locale.ROOT))) {
                String needle = raw.trim().toLowerCase(Locale.ROOT);
                if (StringUtils.hasText(needle)
                    && !userEvidenceLower.contains(needle)
                    && !pinnedEvidenceLower.contains(needle)) {
                    missing.add(required);
                    provenanceMissing.add(required);
                }
            }
        }

        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("missing", List.copyOf(missing));
        debug.put("provenanceMissing", List.copyOf(provenanceMissing));
        debug.put("sourcesUsed", evidence != null ? evidence.sourcesUsed() : Map.of());
        return new ActionParamValidation(
            List.copyOf(missing),
            List.copyOf(provenanceMissing),
            Collections.unmodifiableMap(debug)
        );
    }

    static ActionParamValidation suppressConfirmationGateParameter(ActionParamValidation validation,
                                                                   boolean requiresConfirmation) {
        if (validation == null || !requiresConfirmation) {
            return validation;
        }
        List<String> missingRequired = withoutConfirmationGateParameter(validation.missingRequired());
        List<String> provenanceMissing = withoutConfirmationGateParameter(validation.provenanceMissing());
        if (missingRequired.equals(validation.missingRequired())
            && provenanceMissing.equals(validation.provenanceMissing())) {
            return validation;
        }
        Map<String, Object> debug = new LinkedHashMap<>();
        if (validation.debugMetadata() != null) {
            debug.putAll(validation.debugMetadata());
        }
        debug.put("missing", List.copyOf(missingRequired));
        debug.put("provenanceMissing", List.copyOf(provenanceMissing));
        debug.put("confirmationGateHidden", true);
        return new ActionParamValidation(
            List.copyOf(missingRequired),
            List.copyOf(provenanceMissing),
            Collections.unmodifiableMap(debug)
        );
    }

    static Map<String, Object> publicActionParamValidationMetadata(AIActionMetaData meta,
                                                                   ActionParamValidation validation) {
        if (validation == null) {
            return Map.of();
        }
        Map<String, Object> debug = new LinkedHashMap<>();
        Map<String, Object> rawDebug = validation.debugMetadata();
        if (rawDebug != null) {
            rawDebug.forEach((key, value) -> {
                if (!"missing".equals(key) && !"provenanceMissing".equals(key)) {
                    debug.put(key, value);
                }
            });
        }
        debug.put("missing", publicMissingRequiredParameters(meta, validation.missingRequired()));
        debug.put("provenanceMissing", publicMissingRequiredParameters(meta, validation.provenanceMissing()));
        long hiddenContextMissing = countHiddenContextParameters(meta, validation.missingRequired());
        if (hiddenContextMissing > 0) {
            debug.put("hiddenContextMissingCount", hiddenContextMissing);
        }
        return Map.of(METADATA_KEY_ACTION_PARAM_VALIDATION, Collections.unmodifiableMap(debug));
    }

    static List<String> publicMissingRequiredParameters(AIActionMetaData meta, List<String> missingRequired) {
        if (missingRequired == null || missingRequired.isEmpty()) {
            return List.of();
        }
        return missingRequired.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .filter(parameter -> isUserVisibleActionParameter(meta, parameter))
            .toList();
    }

    static ActionParamValidation mergeExecutableValidation(ActionParamValidation validation,
                                                           ActionExecutableValidation executableValidation) {
        if (executableValidation == null) {
            return validation;
        }
        Map<String, Object> debug = new LinkedHashMap<>();
        if (validation != null && validation.debugMetadata() != null) {
            debug.putAll(validation.debugMetadata());
        }
        debug.put("executableValidation", executableValidation.debugMetadata());
        return new ActionParamValidation(
            validation != null ? validation.missingRequired() : List.of(),
            validation != null ? validation.provenanceMissing() : List.of(),
            Collections.unmodifiableMap(debug)
        );
    }

    static String actionExecutableValidationMessage(ActionExecutableValidation validation) {
        if (validation == null) {
            return "I need complete action details before I can proceed.";
        }
        if (!validation.untrustedArguments().isEmpty()) {
            return "I need a trusted selected item or target before I can perform this action. Please choose it from the assistant results and try again.";
        }
        if (!validation.invalidArguments().isEmpty()) {
            return "I need complete valid action details before I can perform this action.";
        }
        return "I need a specific target or action details before I can perform this action.";
    }

    private static List<String> withoutConfirmationGateParameter(List<String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return List.of();
        }
        return parameters.stream()
            .filter(parameter -> !isConfirmationAcceptedParameter(parameter))
            .toList();
    }

    private static long countHiddenContextParameters(AIActionMetaData meta, List<String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return 0;
        }
        return parameters.stream().filter(parameter -> !isUserVisibleActionParameter(meta, parameter)).count();
    }
}
