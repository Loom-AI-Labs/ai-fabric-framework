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
                                    boolean deterministic,
                                    boolean fanoutAllowed,
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
            vectorSpaces = readActionResolution.preferredVectorSpaces();
            vectorSpacesSelectionSource = "READ_ACTION_PLANNER";
            if (intent != null) {
                intent.setVectorSpace(String.join(",", vectorSpaces));
            }
        }

        if (intent != null) {
            intent.setVectorSpace(vectorSpaces.isEmpty() ? null : String.join(",", vectorSpaces));
        }
        if (vectorSpaces.isEmpty()
            && ragBudgets != null
            && ragBudgets.hasVectorSpaceAllowlist()) {
            List<String> allowlist = ragBudgets.retrievalVectorSpacesAllowlist();
            if (vectorSpaceSelectionRequired && allowlist.size() > 1) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("allowedVectorSpaces", allowlist);
                data.put("reason", "VECTOR_SPACE_REQUIRED_BY_POLICY");
                return clarification(
                    "Which knowledge base domain should I search?",
                    data,
                    intent
                );
            }

            vectorSpaces = allowlist;
            if (intent != null) {
                intent.setVectorSpace(String.join(",", vectorSpaces));
            }
            vectorSpacesSelectionSource = allowlist.size() == 1 ? "ALLOWLIST_SINGLETON" : "ALLOWLIST";
        }
        if (vectorSpaces.isEmpty()) {
            List<String> allSpaces = deterministic
                ? vectorSpaceSelectionSupport.resolveDeterministicFallbackVectorSpaces(ragBudgets)
                : vectorSpaceSelectionSupport.resolveAllVectorSpaces();
            if (!allSpaces.isEmpty()) {
                allSpaces = vectorSpaceSelectionSupport.capVectorSpacesToBudget(allSpaces, ragBudgets);
                vectorSpaces = allSpaces;
                if (intent != null) {
                    intent.setVectorSpace(String.join(",", allSpaces));
                }
                vectorSpacesSelectionSource = deterministic ? "DETERMINISTIC_FALLBACK" : "KB_OVERVIEW";
            }
        }

        if (!vectorSpaces.isEmpty()) {
            vectorSpaces = vectorSpaceSelectionSupport.capVectorSpacesToBudget(vectorSpaces, ragBudgets);
            if (vectorSpacesSelectionSource == null) {
                vectorSpacesSelectionSource = "UNKNOWN";
            }
        }
        if (vectorSpaces.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(DATA_KEY_CANDIDATE_VECTOR_SPACES, List.of());
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
            metadata.put("fanoutSuppressed", true);
            metadata.put("fanoutSuppressedReason", "POLICY");
            metadata.put("fanoutSuppressedRequestedSpaces", vectorSpaces);
            vectorSpaces = List.of(vectorSpaces.getFirst());
            if (intent != null) {
                intent.setVectorSpace(vectorSpaces.getFirst());
            }
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
