package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.KnowledgeBaseOverview;
import ai.fabric.intent.KnowledgeBaseOverviewService;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.SemanticRecoveryPolicy;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import ai.fabric.config.VectorSpaceRoutingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pipeline step that resolves missing vectorSpace for retrieval intents.
 *
 * <p><strong>Order:</strong> 55 (after IntentExtractionStep, before IntentHandlingStep)</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorSpaceResolutionStep implements PipelineStep {

    private static final String STEP_NAME = "VectorSpaceResolution";
    private static final int STEP_ORDER = 55;

    private static final String METADATA_KEY_ROUTING = "vectorSpaceRouting";

    private static final String MSG_CLARIFICATION_PREFIX = "Which knowledge base domain should I search?";

    private final VectorSpaceRoutingProperties vectorSpaceRoutingProperties;
    private final ObjectProvider<KnowledgeBaseOverviewService> knowledgeBaseOverviewServiceProvider;

    @Override
    public String getStepName() {
        return STEP_NAME;
    }

    @Override
    public int getOrder() {
        return STEP_ORDER;
    }

    @Override
    public PipelineContext process(PipelineContext context) {
        MultiIntentResponse intentResponse = context.getIntentResponse();
        if (intentResponse == null || intentResponse.getIntents() == null || intentResponse.getIntents().isEmpty()) {
            return context;
        }

        OrchestrationPolicy policy = context.getOrchestrationPolicy();
        OrchestrationPolicy.RagBudgets ragBudgets = policy != null ? policy.ragBudgets() : null;
        SemanticRecoveryPolicy recoveryPolicy = policy != null
            ? policy.semanticRecoveryPolicy()
            : SemanticRecoveryPolicy.strictDefaults();
        boolean boundedMissingSpaceRecoveryAllowed = recoveryPolicy.missingVectorSpace()
            == OrchestrationProperties.MissingVectorSpaceRecovery.BOUNDED_ALLOWED_FAN_OUT;
        boolean fanoutCapabilityAllowed = ragBudgets == null
            || ragBudgets.fanoutEnabled() == null
            || Boolean.TRUE.equals(ragBudgets.fanoutEnabled());
        boolean boundedRecoveryConfigured = ragBudgets != null
            && Boolean.TRUE.equals(ragBudgets.fanoutEnabled())
            && ragBudgets.maxSpaces() != null
            && ragBudgets.maxSpaces() > 0;
        boolean missingSpaceFanoutAllowed = boundedMissingSpaceRecoveryAllowed
            && boundedRecoveryConfigured;
        int maxSpacesBudget = resolveEffectiveMaxSpaces(ragBudgets, fanoutCapabilityAllowed);

        List<Map<String, Object>> routingEvents = new ArrayList<>();
        boolean anyUpdate = false;

        // Lazy-loaded knowledge base vector spaces for validation and fan-out fallback.
        List<String> availableVectorSpaces = null;
        Map<String, String> canonicalByLower = null;

        List<Intent> intents = intentResponse.getIntents();
        for (int i = 0; i < intents.size(); i++) {
            Intent intent = intents.get(i);
            if (intent == null || intent.getType() != IntentType.INFORMATION) {
                continue;
            }
            if (!Boolean.TRUE.equals(intent.getRequiresRetrieval())) {
                continue;
            }

            List<String> requestHintSpaces = resolveRequestVectorSpaceHints(context);
            if (!requestHintSpaces.isEmpty()) {
                if (availableVectorSpaces == null) {
                    availableVectorSpaces = resolveAllVectorSpaces();
                    canonicalByLower = buildCanonicalByLower(
                        resolvePolicySelectionCandidates(ragBudgets, availableVectorSpaces)
                    );
                }
                VectorSpaceNormalization normalization = canonicalByLower != null && !canonicalByLower.isEmpty()
                    ? normalizeVectorSpaces(String.join(",", requestHintSpaces), canonicalByLower)
                    : new VectorSpaceNormalization(requestHintSpaces, List.of(), false);
                if (normalization != null && !normalization.invalidTokens().isEmpty()) {
                    routingEvents.add(toNormalizationEvent(
                        i,
                        "TRUSTED_SERVER_HINT_REJECTED",
                        String.join(",", requestHintSpaces),
                        null,
                        normalization.invalidTokens(),
                        resolvePolicySelectionCandidates(ragBudgets, availableVectorSpaces)
                    ));
                    return context.terminate(buildInvalidTrustedHintResult(
                        normalization.invalidTokens(),
                        resolvePolicySelectionCandidates(ragBudgets, availableVectorSpaces),
                        context,
                        routingEvents
                    ));
                }
                if (normalization != null && !normalization.normalizedValid().isEmpty()) {
                    String prior = intent.getVectorSpace();
                    String resolved = String.join(",", normalization.normalizedValid());
                    intent.setVectorSpace(resolved);
                    routingEvents.add(toNormalizationEvent(i, "TRUSTED_SERVER_HINT", prior, resolved,
                        List.of(), resolvePolicySelectionCandidates(ragBudgets, availableVectorSpaces)));
                    OrchestrationResult boundsViolation = selectionBoundsViolation(
                        normalization.normalizedValid(),
                        "TRUSTED_SERVER_HINT",
                        fanoutCapabilityAllowed,
                        maxSpacesBudget,
                        context,
                        routingEvents,
                        i
                    );
                    if (boundsViolation != null) {
                        return context.terminate(boundsViolation);
                    }
                    anyUpdate = true;
                    continue;
                }
            }

            // Validate the model-provided vectorSpace against trusted registered spaces. Validation may reject
            // a semantic decision, but it must not invent a replacement.
            if (hasText(intent.getVectorSpace())) {
                if (availableVectorSpaces == null) {
                    availableVectorSpaces = resolveAllVectorSpaces();
                    canonicalByLower = buildCanonicalByLower(
                        resolvePolicySelectionCandidates(ragBudgets, availableVectorSpaces)
                    );
                }
                if (availableVectorSpaces != null && !availableVectorSpaces.isEmpty() && canonicalByLower != null && !canonicalByLower.isEmpty()) {
                    VectorSpaceNormalization normalization = normalizeVectorSpaces(intent.getVectorSpace(), canonicalByLower);
                    if (normalization != null && !normalization.invalidTokens().isEmpty()) {
                        routingEvents.add(toNormalizationEvent(
                            i,
                            "MODEL_DECISION_REJECTED",
                            intent.getVectorSpace(),
                            null,
                            normalization.invalidTokens(),
                            resolvePolicySelectionCandidates(ragBudgets, availableVectorSpaces)
                        ));
                        routingEvents.add(toRejectedDecisionEvent(
                            i,
                            "INVALID_VECTOR_SPACE",
                            intent.getVectorSpace(),
                            recoveryPolicy.invalidVectorSpace().name(),
                            normalization.invalidTokens()
                        ));
                        List<String> candidates = resolvePolicySelectionCandidates(ragBudgets, availableVectorSpaces);
                        if (recoveryPolicy.invalidVectorSpace()
                            == OrchestrationProperties.InvalidVectorSpaceRecovery.LLM_REPAIR_THEN_CLARIFY) {
                            return context.terminate(buildClarificationResult(candidates, context, routingEvents));
                        }
                        return context.terminate(buildInvalidVectorSpaceResult(
                            normalization.invalidTokens(),
                            candidates,
                            context,
                            routingEvents,
                            recoveryPolicy
                        ));
                    } else if (normalization != null && normalization.changed()) {
                        String prior = intent.getVectorSpace();
                        intent.setVectorSpace(String.join(",", normalization.normalizedValid()));
                        routingEvents.add(toNormalizationEvent(i, "NORMALIZED", prior, intent.getVectorSpace(),
                            List.of(), resolvePolicySelectionCandidates(ragBudgets, availableVectorSpaces)));
                        anyUpdate = true;
                    } else if (normalization != null && !normalization.normalizedValid().isEmpty()) {
                        routingEvents.add(toNormalizationEvent(
                            i,
                            "LLM_VALIDATED",
                            intent.getVectorSpace(),
                            intent.getVectorSpace(),
                            List.of(),
                            resolvePolicySelectionCandidates(ragBudgets, availableVectorSpaces)
                        ));
                    }
                }
            }

            if (hasText(intent.getVectorSpace())) {
                List<String> selectedSpaces = parseVectorSpaces(intent.getVectorSpace());
                OrchestrationResult boundsViolation = selectionBoundsViolation(
                    selectedSpaces,
                    "MODEL",
                    fanoutCapabilityAllowed,
                    maxSpacesBudget,
                    context,
                    routingEvents,
                    i
                );
                if (boundsViolation != null) {
                    return context.terminate(boundsViolation);
                }
                continue;
            }

            String targetVectorSpace = resolveTargetVectorSpace(
                intent,
                context,
                ragBudgets,
                availableVectorSpaces
            );
            if (hasText(targetVectorSpace)) {
                intent.setVectorSpace(targetVectorSpace);
                routingEvents.add(toTargetContextEvent(i, targetVectorSpace, context));
                anyUpdate = true;
                continue;
            }

            List<String> candidates = resolvePolicySelectionCandidates(ragBudgets, availableVectorSpaces);
            if (missingSpaceFanoutAllowed) {
                List<String> selected = resolveDeepFallbackVectorSpaces(maxSpacesBudget, ragBudgets);
                if (!selected.isEmpty()) {
                    String resolvedVectorSpace = String.join(",", selected);
                    intent.setVectorSpace(resolvedVectorSpace);
                    routingEvents.add(toExplicitRecoveryEvent(
                        i,
                        "MISSING_VECTOR_SPACE",
                        recoveryPolicy.missingVectorSpace().name(),
                        resolvedVectorSpace,
                        selected
                    ));
                    anyUpdate = true;
                    continue;
                }
            }
            routingEvents.add(toSelectionRequiredEvent(
                i,
                candidates,
                recoveryPolicy.missingVectorSpace().name(),
                boundedMissingSpaceRecoveryAllowed,
                boundedRecoveryConfigured
            ));
            return context.terminate(buildClarificationResult(candidates, context, routingEvents));
        }

        if (routingEvents.isEmpty() && !anyUpdate) {
            return context;
        }

        PipelineContext updated = context;
        if (!routingEvents.isEmpty()) {
            updated = updated.withMetadata(METADATA_KEY_ROUTING, Collections.unmodifiableList(routingEvents));
        }
        if (anyUpdate) {
            updated = updated.toBuilder()
                .intentResponse(intentResponse)
                .build();
        }

	        return updated;
	    }

	    private int resolveEffectiveMaxSpaces(OrchestrationPolicy.RagBudgets ragBudgets, boolean fanoutAllowed) {
	        if (!fanoutAllowed) {
	            return 1;
	        }
	        Integer maxOverride = ragBudgets != null ? ragBudgets.maxSpaces() : null;
	        if (maxOverride != null && maxOverride > 0) {
	            return maxOverride;
	        }
	        int maxDefault = vectorSpaceRoutingProperties != null ? vectorSpaceRoutingProperties.getFanOutMaxSpaces() : 3;
	        return Math.max(1, maxDefault);
	    }

    private List<String> resolvePolicySelectionCandidates(OrchestrationPolicy.RagBudgets ragBudgets,
                                                          List<String> availableVectorSpaces) {
        if (ragBudgets != null && ragBudgets.hasVectorSpaceAllowlist()) {
            return ragBudgets.retrievalVectorSpacesAllowlist();
        }
        List<String> available = availableVectorSpaces;
        if (available == null) {
            available = resolveAllVectorSpaces();
        }
        return available != null ? available : List.of();
    }

    private String resolveTargetVectorSpace(Intent intent,
                                            PipelineContext context,
                                            OrchestrationPolicy.RagBudgets ragBudgets,
                                            List<String> availableVectorSpaces) {
        if (intent == null
            || !Boolean.TRUE.equals(intent.getRequiresTargetResolution())
            || context == null
            || context.getResolvedTargets() == null
            || context.getResolvedTargets().isEmpty()) {
            return null;
        }

        List<String> policyCandidates = resolvePolicySelectionCandidates(ragBudgets, availableVectorSpaces);
        if (policyCandidates.isEmpty()) {
            return null;
        }
        Map<String, String> allowedByLower = new LinkedHashMap<>();
        for (String candidate : policyCandidates) {
            if (hasText(candidate)) {
                allowedByLower.put(candidate.trim().toLowerCase(Locale.ROOT), candidate.trim());
            }
        }
        if (allowedByLower.isEmpty()) {
            return null;
        }

        String selectedLower = null;
        for (ResolvedTarget target : context.getResolvedTargets()) {
            if (target == null || target.getSource() == null || !hasText(target.getVectorSpace())) {
                return null;
            }
            String candidateLower = target.getVectorSpace().trim().toLowerCase(Locale.ROOT);
            if (!allowedByLower.containsKey(candidateLower)) {
                return null;
            }
            if (selectedLower == null) {
                selectedLower = candidateLower;
            } else if (!selectedLower.equals(candidateLower)) {
                return null;
            }
        }
        return selectedLower != null ? allowedByLower.get(selectedLower) : null;
    }

	    private List<String> resolveDeepFallbackVectorSpaces(int maxSpaces,
	                                                        OrchestrationPolicy.RagBudgets ragBudgets) {
	        KnowledgeBaseOverviewService overviewService = knowledgeBaseOverviewServiceProvider != null
	            ? knowledgeBaseOverviewServiceProvider.getIfAvailable()
	            : null;
	        if (overviewService == null) {
	            return List.of();
	        }

	        KnowledgeBaseOverview overview = overviewService.getOverview();
	        if (overview == null) {
	            return List.of();
	        }

	        List<String> entityTypes = overview.getEntityTypes();
	        Map<String, Long> byType = overview.getDocumentsByType();

	        LinkedHashSet<String> ordered = new LinkedHashSet<>();
	        if (byType != null && !byType.isEmpty()) {
	            byType.entrySet().stream()
	                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
	                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.nullsLast(Long::compareTo)).reversed())
	                .map(Map.Entry::getKey)
	                .forEach(ordered::add);
	        }

	        if (entityTypes != null && !entityTypes.isEmpty()) {
	            entityTypes.stream()
	                .filter(this::hasText)
	                .map(String::trim)
	                .forEach(ordered::add);
	        }

	        List<String> out = ordered.stream()
	            .filter(this::hasText)
	            .map(String::trim)
	            .distinct()
	            .toList();

	        if (ragBudgets != null && ragBudgets.hasVectorSpaceAllowlist()) {
	            List<String> allowlist = ragBudgets.retrievalVectorSpacesAllowlist();
	            out = out.stream()
	                .filter(space -> allowlist.contains(space.toLowerCase(Locale.ROOT)))
	                .toList();
	        }

	        int effectiveMax = Math.max(1, maxSpaces);
	        if (out.size() > effectiveMax) {
	            out = out.subList(0, effectiveMax);
	        }
        return out;
    }

    private List<String> resolveRequestVectorSpaceHints(PipelineContext context) {
        Map<String, Object> metadata = context != null
            && context.getOrchestrationContext() != null
            && context.getOrchestrationContext().getMetadata() != null
            ? context.getOrchestrationContext().getMetadata()
            : Map.of();
        if (metadata.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> hints = new LinkedHashSet<>();
        collectHintValues(hints, metadata.get(OrchestrationContextMetadataKeys.RAG_PREFERRED_VECTOR_SPACES));
        collectHintValues(hints, metadata.get(OrchestrationContextMetadataKeys.RAG_VECTOR_SPACE_HINT));

        return hints.stream().toList();
    }

    private void collectHintValues(LinkedHashSet<String> hints, Object raw) {
        if (hints == null || raw == null) {
            return;
        }
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                collectHintValues(hints, item);
            }
            return;
        }
        if (raw instanceof String text) {
            for (String part : text.split(",")) {
                String trimmed = part != null ? part.trim() : null;
                if (hasText(trimmed)) {
                    hints.add(trimmed);
                }
            }
        }
    }

	    private Map<String, Object> toExplicitRecoveryEvent(int intentIndex,
	                                                        String decision,
	                                                        String recoveryPolicy,
	                                                        String resolvedVectorSpace,
	                                                        List<String> selectedSpaces) {
	        Map<String, Object> event = new LinkedHashMap<>();
	        event.put("intentIndex", intentIndex);
	        event.put("success", true);
	        event.put("decision", decision);
	        event.put("originalSource", "MODEL");
	        event.put("originalValue", null);
	        event.put("validationStatus", "MISSING");
	        event.put("recoveryPolicy", recoveryPolicy);
	        event.put("recoveryAttempted", true);
	        event.put("effectiveSource", "POLICY_RECOVERY");
	        event.put("strategy", "EXPLICIT_BOUNDED_FAN_OUT");
	        event.put("vectorSpace", resolvedVectorSpace);
	        event.put("selectedSpaces", selectedSpaces != null ? selectedSpaces : List.of());
	        return Collections.unmodifiableMap(event);
	    }

	    private List<String> resolveAllVectorSpaces() {
	        KnowledgeBaseOverviewService overviewService = knowledgeBaseOverviewServiceProvider != null
	            ? knowledgeBaseOverviewServiceProvider.getIfAvailable()
	            : null;
        if (overviewService == null) {
            return List.of();
        }

        KnowledgeBaseOverview overview = overviewService.getOverview();
        if (overview == null) {
            return List.of();
        }

        List<String> entityTypes = overview.getEntityTypes();
        if (entityTypes == null || entityTypes.isEmpty()) {
            var byType = overview.getDocumentsByType();
            if (byType != null && !byType.isEmpty()) {
                entityTypes = byType.keySet().stream().toList();
            }
        }
        if (entityTypes == null || entityTypes.isEmpty()) {
            return List.of();
        }

        return entityTypes.stream()
            .filter(this::hasText)
            .map(String::trim)
            .distinct()
            .toList();
    }

    private OrchestrationResult buildClarificationResult(List<String> candidates,
                                                         PipelineContext context,
                                                         List<Map<String, Object>> routingEvents) {
        List<String> safeCandidates = candidates != null ? candidates : List.of();
        String message = safeCandidates.isEmpty()
            ? MSG_CLARIFICATION_PREFIX
            : MSG_CLARIFICATION_PREFIX + " Options: " + String.join(", ", safeCandidates);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("candidateVectorSpaces", safeCandidates);

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (context != null && context.getMetadata() != null && !context.getMetadata().isEmpty()) {
            metadata.putAll(context.getMetadata());
        }
        if (routingEvents != null && !routingEvents.isEmpty()) {
            metadata.put(METADATA_KEY_ROUTING, Collections.unmodifiableList(new ArrayList<>(routingEvents)));
        }

        return OrchestrationResult.builder()
            .type(OrchestrationResultType.CLARIFICATION_REQUIRED)
            .success(false)
            .message(message)
            .data(Collections.unmodifiableMap(data))
            .metadata(Collections.unmodifiableMap(metadata))
            .build();
    }

    private OrchestrationResult buildInvalidVectorSpaceResult(
        List<String> invalidSpaces,
        List<String> candidates,
        PipelineContext context,
        List<Map<String, Object>> routingEvents,
        SemanticRecoveryPolicy recoveryPolicy
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("invalidVectorSpaces", invalidSpaces != null ? List.copyOf(invalidSpaces) : List.of());
        data.put("candidateVectorSpaces", candidates != null ? List.copyOf(candidates) : List.of());

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (context != null && context.getMetadata() != null) {
            metadata.putAll(context.getMetadata());
        }
        metadata.put(METADATA_KEY_ROUTING, Collections.unmodifiableList(new ArrayList<>(routingEvents)));
        metadata.put("semanticDecision", Map.of(
            "decision", "VECTOR_SPACE_SELECTION",
            "originalSource", "MODEL",
            "validationStatus", "REJECTED_NOT_REGISTERED",
            "recoveryPolicy", recoveryPolicy.invalidVectorSpace().name(),
            "recoveryAttempted", false,
            "effectiveSource", "NONE"
        ));
        return OrchestrationResult.builder()
            .type(OrchestrationResultType.ERROR)
            .success(false)
            .errorCode("INVALID_VECTOR_SPACE_DECISION")
            .message("The AI-selected knowledge domain was not registered or allowed.")
            .data(Collections.unmodifiableMap(data))
            .metadata(Collections.unmodifiableMap(metadata))
            .build();
    }

    private OrchestrationResult buildInvalidTrustedHintResult(
        List<String> invalidSpaces,
        List<String> candidates,
        PipelineContext context,
        List<Map<String, Object>> routingEvents
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("invalidVectorSpaces", invalidSpaces != null ? List.copyOf(invalidSpaces) : List.of());
        data.put("allowedVectorSpaces", candidates != null ? List.copyOf(candidates) : List.of());

        Map<String, Object> semanticDecision = new LinkedHashMap<>();
        semanticDecision.put("decision", "VECTOR_SPACE_SELECTION");
        semanticDecision.put("originalSource", "TRUSTED_SERVER_HINT");
        semanticDecision.put("originalValue", invalidSpaces != null ? List.copyOf(invalidSpaces) : List.of());
        semanticDecision.put("validationStatus", "REJECTED_NOT_ALLOWED");
        semanticDecision.put("recoveryPolicy", "NONE");
        semanticDecision.put("recoveryAttempted", false);
        semanticDecision.put("effectiveSource", "NONE");
        semanticDecision.put("effectiveValue", "FAILURE");

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (context != null && context.getMetadata() != null) {
            metadata.putAll(context.getMetadata());
        }
        metadata.put(METADATA_KEY_ROUTING, Collections.unmodifiableList(new ArrayList<>(routingEvents)));
        metadata.put("semanticDecision", Collections.unmodifiableMap(semanticDecision));
        return OrchestrationResult.builder()
            .type(OrchestrationResultType.ERROR)
            .success(false)
            .errorCode("INVALID_TRUSTED_VECTOR_SPACE_HINT")
            .message("The server-provided knowledge-domain hint is not registered or allowed by policy.")
            .data(Collections.unmodifiableMap(data))
            .metadata(Collections.unmodifiableMap(metadata))
            .build();
    }

    private OrchestrationResult selectionBoundsViolation(
        List<String> selectedSpaces,
        String originalSource,
        boolean fanoutAllowed,
        int maxSpaces,
        PipelineContext context,
        List<Map<String, Object>> routingEvents,
        int intentIndex
    ) {
        if (selectedSpaces == null || selectedSpaces.size() <= 1) {
            return null;
        }
        String reason = !fanoutAllowed
            ? "VECTOR_SPACE_FANOUT_DISABLED"
            : selectedSpaces.size() > maxSpaces
                ? "VECTOR_SPACE_FANOUT_LIMIT_EXCEEDED"
                : null;
        if (reason == null) {
            return null;
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("intentIndex", intentIndex);
        event.put("success", false);
        event.put("decision", "VECTOR_SPACE_SELECTION");
        event.put("originalSource", originalSource);
        event.put("originalValue", List.copyOf(selectedSpaces));
        event.put("validationStatus", reason);
        event.put("recoveryPolicy", "NONE");
        event.put("recoveryAttempted", false);
        event.put("effectiveSource", "NONE");
        event.put("effectiveValue", "CLARIFICATION_REQUIRED");
        event.put("maxSpaces", maxSpaces);
        routingEvents.add(Collections.unmodifiableMap(event));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason);
        data.put("requestedVectorSpaces", List.copyOf(selectedSpaces));
        data.put("maxSpaces", maxSpaces);

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (context != null && context.getMetadata() != null) {
            metadata.putAll(context.getMetadata());
        }
        metadata.put(METADATA_KEY_ROUTING, Collections.unmodifiableList(new ArrayList<>(routingEvents)));
        metadata.put("semanticDecision", Collections.unmodifiableMap(event));

        boolean serverOwned = "TRUSTED_SERVER_HINT".equals(originalSource);
        return OrchestrationResult.builder()
            .type(serverOwned ? OrchestrationResultType.ERROR : OrchestrationResultType.CLARIFICATION_REQUIRED)
            .success(false)
            .errorCode(serverOwned ? reason : null)
            .message(serverOwned
                ? "The server-provided knowledge-domain selection exceeds the configured retrieval policy."
                : "This request needs more knowledge domains than this mode permits. Please narrow the request.")
            .data(Collections.unmodifiableMap(data))
            .metadata(Collections.unmodifiableMap(metadata))
            .build();
    }

    private List<String> parseVectorSpaces(String value) {
        if (!hasText(value)) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
            .filter(this::hasText)
            .map(String::trim)
            .distinct()
            .toList();
    }

    private Map<String, Object> toSelectionRequiredEvent(int intentIndex,
                                                         List<String> candidates,
                                                         String recoveryPolicy,
                                                         boolean recoveryRequested,
                                                         boolean recoveryConfigured) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("intentIndex", intentIndex);
        event.put("success", false);
        event.put("decision", "MISSING_VECTOR_SPACE");
        event.put("originalSource", "MODEL");
        event.put("validationStatus", "MISSING");
        event.put("recoveryPolicy", recoveryPolicy);
        event.put("recoveryAttempted", false);
        if (recoveryRequested && !recoveryConfigured) {
            event.put("recoveryBlockedReason", "EXPLICIT_FAN_OUT_BUDGET_REQUIRED");
        }
        event.put("effectiveSource", "CLARIFICATION");
        event.put("strategy", "SELECTION_REQUIRED");
        event.put("rationale", "Vector-space selection is required by orchestration policy");
        event.put("candidateSpaces", candidates != null ? List.copyOf(candidates) : List.of());
        return Collections.unmodifiableMap(event);
    }

    private Map<String, Object> toRejectedDecisionEvent(int intentIndex,
                                                        String decision,
                                                        String originalValue,
                                                        String recoveryPolicy,
                                                        List<String> invalidTokens) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("intentIndex", intentIndex);
        event.put("success", false);
        event.put("decision", decision);
        event.put("originalSource", "MODEL");
        event.put("originalValue", originalValue);
        event.put("validationStatus", "REJECTED_NOT_REGISTERED");
        event.put("recoveryPolicy", recoveryPolicy);
        event.put("recoveryAttempted", false);
        event.put("effectiveSource", "NONE");
        event.put("invalidTokens", invalidTokens != null ? List.copyOf(invalidTokens) : List.of());
        return Collections.unmodifiableMap(event);
    }

    private Map<String, Object> toTargetContextEvent(int intentIndex,
                                                     String vectorSpace,
                                                     PipelineContext context) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("intentIndex", intentIndex);
        event.put("success", true);
        event.put("strategy", "TARGET_CONTEXT");
        event.put("rationale", "All resolved targets agree on one server-allowed vector space");
        event.put("vectorSpace", vectorSpace);
        event.put(
            "targetCount",
            context != null && context.getResolvedTargets() != null
                ? context.getResolvedTargets().size()
                : 0
        );
        return Collections.unmodifiableMap(event);
    }

    private Map<String, Object> toNormalizationEvent(int intentIndex,
                                                     String strategy,
                                                     String priorVectorSpace,
                                                     String resolvedVectorSpace,
                                                     List<String> invalidTokens,
                                                     List<String> availableSpaces) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("intentIndex", intentIndex);
        event.put("success", true);
        event.put("strategy", strategy);
        event.put("priorVectorSpace", priorVectorSpace);
        event.put("vectorSpace", resolvedVectorSpace);
        event.put("invalidTokens", invalidTokens != null ? invalidTokens : List.of());
        // Keep the list short to avoid bloating metadata; callers can inspect KB overview if needed.
        if (availableSpaces != null && !availableSpaces.isEmpty()) {
            event.put("availableSpacesCount", availableSpaces.size());
        }
        return Collections.unmodifiableMap(event);
    }

    private Map<String, String> buildCanonicalByLower(List<String> availableSpaces) {
        if (availableSpaces == null || availableSpaces.isEmpty()) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String space : availableSpaces) {
            if (!hasText(space)) {
                continue;
            }
            String canonical = space.trim();
            map.put(canonical.toLowerCase(Locale.ROOT), canonical);
        }
        return map;
    }

    private VectorSpaceNormalization normalizeVectorSpaces(String rawVectorSpace, Map<String, String> canonicalByLower) {
        if (!hasText(rawVectorSpace)) {
            return new VectorSpaceNormalization(List.of(), List.of(), false);
        }
        if (canonicalByLower == null || canonicalByLower.isEmpty()) {
            return new VectorSpaceNormalization(List.of(rawVectorSpace.trim()), List.of(), false);
        }

        String[] parts = rawVectorSpace.split(",");
        Set<String> normalized = new LinkedHashSet<>();
        List<String> invalid = new ArrayList<>();
        boolean changed = false;

        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String canonical = canonicalByLower.get(trimmed.toLowerCase(Locale.ROOT));
            if (canonical == null) {
                invalid.add(trimmed);
                continue;
            }
            normalized.add(canonical);
            if (!canonical.equals(trimmed)) {
                changed = true;
            }
        }

        // Consider it changed if we dropped empty tokens or removed duplicates.
        int rawNonEmptyCount = 0;
        for (String part : parts) {
            if (part != null && !part.trim().isEmpty()) {
                rawNonEmptyCount++;
            }
        }
        if (normalized.size() != rawNonEmptyCount) {
            changed = true;
        }

        return new VectorSpaceNormalization(List.copyOf(normalized), List.copyOf(invalid), changed);
    }

    private record VectorSpaceNormalization(List<String> normalizedValid, List<String> invalidTokens, boolean changed) {
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
