package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.Intent;
import ai.fabric.dto.NextStepRecommendation;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.information.ReadActionResolutionService;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class InformationVectorSpaceRoutingSupport {

    private static final String DATA_KEY_CANDIDATE_VECTOR_SPACES = "candidateVectorSpaces";

    private InformationVectorSpaceRoutingSupport() {
    }

    static RoutedVectorSpaces route(Intent intent,
                                    Map<String, Object> metadata,
                                    OrchestrationPolicy.RagBudgets ragBudgets,
                                    boolean vectorSpaceSelectionRequired,
                                    boolean fanoutAllowed,
                                    boolean missingSpaceFanoutAllowed,
                                    ReadActionResolutionService.ResolutionOutcome readActionResolution,
                                    AIEntityConfigurationLoader entityConfigurationLoader,
                                    VectorSpaceSelectionSupport vectorSpaceSelectionSupport) {
        List<String> vectorSpacesRaw = RagContextSupport.parseVectorSpaces(intent != null ? intent.getVectorSpace() : null);
        RagContextSupport.VectorSpaceValidation validation =
            RagContextSupport.validateRequestedVectorSpaces(vectorSpacesRaw, entityConfigurationLoader);
        if (validation != null && validation.hasInvalid()) {
            metadata.put("vectorSpacesInvalidRequested", validation.invalid());
        }

        List<String> vectorSpaces = validation != null ? validation.valid() : vectorSpacesRaw;
        String vectorSpacesSelectionSource = !vectorSpaces.isEmpty()
            ? ((validation != null && validation.normalizedOrFiltered()) ? "LLM_VALIDATED" : "LLM")
            : null;

        if (vectorSpaces.isEmpty()
            && readActionResolution != null
            && readActionResolution.attempted()
            && readActionResolution.preferredVectorSpaces() != null
            && !readActionResolution.preferredVectorSpaces().isEmpty()) {
            RagContextSupport.VectorSpaceValidation preferredValidation =
                RagContextSupport.validateRequestedVectorSpaces(
                    readActionResolution.preferredVectorSpaces(),
                    entityConfigurationLoader
                );
            if (preferredValidation != null && preferredValidation.hasInvalid()) {
                metadata.put(
                    "readActionVectorSpacesInvalidRequested",
                    preferredValidation.invalid()
                );
            }
            vectorSpaces = preferredValidation != null
                ? preferredValidation.valid()
                : readActionResolution.preferredVectorSpaces();
            if (!vectorSpaces.isEmpty()) {
                vectorSpacesSelectionSource = preferredVectorSpacesSource(readActionResolution);
            }
        }

        if (intent != null) {
            intent.setVectorSpace(vectorSpaces.isEmpty() ? null : String.join(",", vectorSpaces));
        }
        if (vectorSpaces.isEmpty()) {
            List<String> policySelectedSpaces = List.of();
            if (missingSpaceFanoutAllowed && hasExplicitFanOutBudget(ragBudgets)) {
                policySelectedSpaces = ragBudgets != null && ragBudgets.hasVectorSpaceAllowlist()
                    ? ragBudgets.retrievalVectorSpacesAllowlist()
                    : vectorSpaceSelectionSupport.resolveAllVectorSpaces();
                policySelectedSpaces = vectorSpaceSelectionSupport.capVectorSpacesToBudget(
                    policySelectedSpaces,
                    ragBudgets
                );
                vectorSpacesSelectionSource = "EXPLICIT_BOUNDED_FAN_OUT_RECOVERY";
            }
            if (!policySelectedSpaces.isEmpty()) {
                vectorSpaces = policySelectedSpaces;
                if (intent != null) {
                    intent.setVectorSpace(String.join(",", policySelectedSpaces));
                }
            }
        }

        if (vectorSpaces.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(DATA_KEY_CANDIDATE_VECTOR_SPACES, List.of());
            if (ragBudgets != null && ragBudgets.hasVectorSpaceAllowlist()) {
                data.put("allowedVectorSpaces", ragBudgets.retrievalVectorSpacesAllowlist());
            }
            data.put(
                "reason",
                vectorSpaceSelectionRequired
                    ? "VECTOR_SPACE_REQUIRED_BY_POLICY"
                    : "VECTOR_SPACE_SELECTION_REQUIRED"
            );
            return clarification(
                "Which knowledge base domain should I search?",
                data,
                intent
            );
        }

        if (ragBudgets != null && ragBudgets.hasVectorSpaceAllowlist()) {
            List<String> allowlist = ragBudgets.retrievalVectorSpacesAllowlist();
            List<String> denied = vectorSpaces.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(vs -> !allowlist.contains(vs.toLowerCase(Locale.ROOT)))
                .toList();
            if (!denied.isEmpty()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("allowedVectorSpaces", allowlist);
                data.put("requestedVectorSpaces", vectorSpaces);
                data.put("deniedVectorSpaces", denied);
                data.put("reason", "VECTOR_SPACE_NOT_ALLOWED_BY_POLICY");
                return clarification(
                    "I can answer using the information approved for this assistant, including products, policies, comparisons, cart, and approved order help.",
                    data,
                    intent
                );
            }
        }

        if (!fanoutAllowed && vectorSpaces.size() > 1) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reason", "VECTOR_SPACE_FANOUT_DISABLED");
            data.put("requestedVectorSpaces", List.copyOf(vectorSpaces));
            data.put("selectionSource", vectorSpacesSelectionSource);
            return clarification(
                "This request needs more than one knowledge domain, but this mode permits only one. Please narrow the request or enable approved fan-out.",
                data,
                intent
            );
        }

        Integer maxSpaces = ragBudgets != null ? ragBudgets.maxSpaces() : null;
        if (maxSpaces != null && maxSpaces > 0 && vectorSpaces.size() > maxSpaces) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reason", "VECTOR_SPACE_FANOUT_LIMIT_EXCEEDED");
            data.put("requestedVectorSpaces", List.copyOf(vectorSpaces));
            data.put("maxSpaces", maxSpaces);
            data.put("selectionSource", vectorSpacesSelectionSource);
            return clarification(
                "This request needs more knowledge domains than this mode permits. Please narrow the request.",
                data,
                intent
            );
        }

        if (vectorSpacesSelectionSource == null) {
            vectorSpacesSelectionSource = "UNKNOWN";
        }

        metadata.put("retrievalStrategy", vectorSpaces.size() > 1 ? "FAN_OUT" : "SINGLE_SPACE");
        metadata.put("vectorSpacesSelected", vectorSpaces);
        metadata.put("vectorSpacesSelectionSource", vectorSpacesSelectionSource);
        if (ragBudgets != null && ragBudgets.fanoutEnabled() != null) {
            metadata.put("fanoutEnabledEffective", ragBudgets.fanoutEnabled());
        }
        if (ragBudgets != null && ragBudgets.maxSpaces() != null) {
            metadata.put("ragMaxSpacesEffective", ragBudgets.maxSpaces());
        }

        return new RoutedVectorSpaces(Collections.unmodifiableList(vectorSpaces), null);
    }

    private static boolean hasExplicitFanOutBudget(OrchestrationPolicy.RagBudgets ragBudgets) {
        return ragBudgets != null
            && Boolean.TRUE.equals(ragBudgets.fanoutEnabled())
            && ragBudgets.maxSpaces() != null
            && ragBudgets.maxSpaces() > 0;
    }

    private static String preferredVectorSpacesSource(
        ReadActionResolutionService.ResolutionOutcome readActionResolution
    ) {
        if (readActionResolution != null && readActionResolution.diagnostics() != null) {
            Object value = readActionResolution.diagnostics().get("preferredVectorSpacesSource");
            if (value instanceof String text && StringUtils.hasText(text)) {
                return text.trim();
            }
        }
        return "READ_ACTION_PLANNER";
    }

    private static RoutedVectorSpaces clarification(String message, Map<String, Object> data, Intent intent) {
        return new RoutedVectorSpaces(
            List.of(),
            OrchestrationResult.builder()
                .type(OrchestrationResultType.CLARIFICATION_REQUIRED)
                .success(false)
                .message(message)
                .data(Collections.unmodifiableMap(data))
                .nextSteps(extractNextSteps(intent))
                .build()
        );
    }

    private static List<NextStepRecommendation> extractNextSteps(Intent intent) {
        if (intent == null || intent.getNextStepRecommended() == null) {
            return List.of();
        }
        return List.of(intent.getNextStepRecommended());
    }

    record RoutedVectorSpaces(
        List<String> vectorSpaces,
        OrchestrationResult terminalResult
    ) {
    }
}
