package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.config.OrchestrationProperties;
import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.intent.KnowledgeBaseOverview;
import com.ai.infrastructure.intent.KnowledgeBaseOverviewService;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.intent.orchestration.OrchestrationResultType;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineStep;
import com.ai.infrastructure.intent.orchestration.policy.OrchestrationPolicy;
import com.ai.infrastructure.config.VectorSpaceRoutingProperties;
import com.ai.infrastructure.intent.vectorspace.RoutingResult;
import com.ai.infrastructure.intent.vectorspace.VectorSpaceRouter;
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

    private final VectorSpaceRouter vectorSpaceRouter;
    private final OrchestrationProperties orchestrationProperties;
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
        OrchestrationPolicy.OrchestrationCapabilities capabilities = policy != null ? policy.capabilities() : null;
        OrchestrationPolicy.RagBudgets ragBudgets = policy != null ? policy.ragBudgets() : null;
        boolean deepRetrievalEnabled = capabilities != null && capabilities.deepRetrievalEnabled();
        boolean fanoutAllowed = ragBudgets == null
            || ragBudgets.fanoutEnabled() == null
            || Boolean.TRUE.equals(ragBudgets.fanoutEnabled());
        int maxSpacesBudget = resolveEffectiveMaxSpaces(ragBudgets, fanoutAllowed);
        boolean deterministic = policy != null
            ? policy.informationMode() == OrchestrationProperties.InformationMode.DETERMINISTIC_RAG_GENERATE
            : (orchestrationProperties != null
                && orchestrationProperties.getInformationMode() == OrchestrationProperties.InformationMode.DETERMINISTIC_RAG_GENERATE);

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

            // Validate LLM-provided vectorSpace against currently available knowledge base spaces.
            // If the provided value is invalid, fall back to fan-out across all available spaces.
            if (hasText(intent.getVectorSpace())) {
                if (availableVectorSpaces == null) {
                    availableVectorSpaces = resolveAllVectorSpaces();
                    canonicalByLower = buildCanonicalByLower(availableVectorSpaces);
                }
                if (availableVectorSpaces != null && !availableVectorSpaces.isEmpty() && canonicalByLower != null && !canonicalByLower.isEmpty()) {
                    VectorSpaceNormalization normalization = normalizeVectorSpaces(intent.getVectorSpace(), canonicalByLower);
	                    if (normalization != null && !normalization.invalidTokens().isEmpty()) {
	                        String prior = intent.getVectorSpace();
	                        if (normalization.normalizedValid().isEmpty()) {
	                            List<String> fallbackSpaces = availableVectorSpaces;
                                if (ragBudgets != null && ragBudgets.hasVectorSpaceAllowlist()) {
                                    List<String> allowlist = ragBudgets.retrievalVectorSpacesAllowlist();
                                    fallbackSpaces = fallbackSpaces.stream()
                                        .filter(space -> allowlist.contains(space.toLowerCase(Locale.ROOT)))
                                        .toList();
                                }
	                            if (ragBudgets != null
	                                && ragBudgets.maxSpaces() != null
	                                && ragBudgets.maxSpaces() > 0
	                                && fallbackSpaces.size() > ragBudgets.maxSpaces()) {
	                                fallbackSpaces = fallbackSpaces.subList(0, ragBudgets.maxSpaces());
	                            }
	                            intent.setVectorSpace(String.join(",", fallbackSpaces));
	                            routingEvents.add(toNormalizationEvent(i, "INVALID_FALLBACK_FAN_OUT", prior, intent.getVectorSpace(),
	                                normalization.invalidTokens(), availableVectorSpaces));
	                        } else {
	                            intent.setVectorSpace(String.join(",", normalization.normalizedValid()));
	                            routingEvents.add(toNormalizationEvent(i, "INVALID_FILTERED", prior, intent.getVectorSpace(),
                                normalization.invalidTokens(), availableVectorSpaces));
                        }
                        anyUpdate = true;
                    } else if (normalization != null && normalization.changed()) {
                        String prior = intent.getVectorSpace();
                        intent.setVectorSpace(String.join(",", normalization.normalizedValid()));
                        routingEvents.add(toNormalizationEvent(i, "NORMALIZED", prior, intent.getVectorSpace(),
                            List.of(), availableVectorSpaces));
                        anyUpdate = true;
                    }
                }
            }

            if (deterministic) {
                if (!hasText(intent.getVectorSpace())) {
	                    RoutingResult routing = vectorSpaceRouter.route(intent, context.getOriginalQuery());
	                    routingEvents.add(toRoutingEvent(i, routing));

	                    String resolvedVectorSpace = resolveVectorSpaceString(routing);
	                    if (deepRetrievalEnabled && (routing == null || routing.requiresFanOut() || !hasText(resolvedVectorSpace))) {
	                        List<String> fallback = resolveDeepFallbackVectorSpaces(maxSpacesBudget, ragBudgets);
	                        if (!fallback.isEmpty()) {
	                            resolvedVectorSpace = String.join(",", fallback);
	                            routingEvents.add(toDeepFallbackEvent(i, resolvedVectorSpace, fallback));
	                        }
	                    }
	                    if (!hasText(resolvedVectorSpace)
	                        && routing != null
	                        && routing.getCandidateSpaces() != null
	                        && !routing.getCandidateSpaces().isEmpty()) {
	                        // Deterministic mode: use router candidates instead of terminating for clarification.
                        resolvedVectorSpace = String.join(",", routing.getCandidateSpaces());
                    }

                    if (hasText(resolvedVectorSpace)) {
                        intent.setVectorSpace(resolvedVectorSpace);
                        anyUpdate = true;
                    }
                    // Deterministic mode must not terminate with clarification; leave vectorSpace blank if unknown.
                }
                continue;
            }

            if (hasText(intent.getVectorSpace())) {
                // vectorSpace provided (and considered valid/normalized when knowledge base spaces are available).
                continue;
            }

	            RoutingResult routing = vectorSpaceRouter.route(intent, context.getOriginalQuery());
	            routingEvents.add(toRoutingEvent(i, routing));

	            if (routing == null || !routing.isSuccess()) {
	                if (deepRetrievalEnabled) {
	                    List<String> fallback = resolveDeepFallbackVectorSpaces(maxSpacesBudget, ragBudgets);
	                    if (!fallback.isEmpty()) {
	                        String resolvedVectorSpace = String.join(",", fallback);
	                        intent.setVectorSpace(resolvedVectorSpace);
	                        routingEvents.add(toDeepFallbackEvent(i, resolvedVectorSpace, fallback));
	                        anyUpdate = true;
	                        continue;
	                    }
	                }
	                List<String> candidates = routing != null ? routing.getCandidateSpaces() : List.of();
	                return context.terminate(buildClarificationResult(candidates, context, routingEvents));
	            }

	            String resolvedVectorSpace = resolveVectorSpaceString(routing);
	            if (deepRetrievalEnabled && routing != null && routing.requiresFanOut()) {
	                List<String> fallback = resolveDeepFallbackVectorSpaces(maxSpacesBudget, ragBudgets);
	                if (!fallback.isEmpty()) {
	                    resolvedVectorSpace = String.join(",", fallback);
	                    routingEvents.add(toDeepFallbackEvent(i, resolvedVectorSpace, fallback));
	                }
	            }
	            if (!hasText(resolvedVectorSpace)) {
	                if (deepRetrievalEnabled) {
	                    List<String> fallback = resolveDeepFallbackVectorSpaces(maxSpacesBudget, ragBudgets);
	                    if (!fallback.isEmpty()) {
	                        resolvedVectorSpace = String.join(",", fallback);
	                        routingEvents.add(toDeepFallbackEvent(i, resolvedVectorSpace, fallback));
	                    }
	                }
	                if (!hasText(resolvedVectorSpace)) {
	                    return context.terminate(buildClarificationResult(List.of(), context, routingEvents));
	                }
	            }

            intent.setVectorSpace(resolvedVectorSpace);
            anyUpdate = true;
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

	    private Map<String, Object> toDeepFallbackEvent(int intentIndex,
	                                                    String resolvedVectorSpace,
	                                                    List<String> selectedSpaces) {
	        Map<String, Object> event = new LinkedHashMap<>();
	        event.put("intentIndex", intentIndex);
	        event.put("success", true);
	        event.put("strategy", "DEEP_FALLBACK");
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

    private String resolveVectorSpaceString(RoutingResult routing) {
        if (routing == null) {
            return null;
        }
        if (routing.requiresFanOut()) {
            return String.join(",", routing.getCandidateSpaces());
        }

        // Provider-agnostic robustness: some routers return FAN_OUT strategy with a single candidate when fan-out is
        // configured to max 1 space. Treat that single candidate as a resolved vectorSpace rather than forcing
        // clarification due to routing.getVectorSpace() being null.
        if (routing.getStrategy() == com.ai.infrastructure.intent.vectorspace.RoutingStrategy.FAN_OUT
            && routing.getCandidateSpaces() != null
            && routing.getCandidateSpaces().size() == 1) {
            String only = routing.getCandidateSpaces().getFirst();
            return hasText(only) ? only.trim() : null;
        }

        return routing.getVectorSpace();
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

    private Map<String, Object> toRoutingEvent(int intentIndex, RoutingResult routing) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("intentIndex", intentIndex);
        if (routing == null) {
            event.put("success", false);
            event.put("strategy", null);
            event.put("confidence", 0.0d);
            event.put("rationale", "Router returned null result");
            return Collections.unmodifiableMap(event);
        }
        event.put("success", routing.isSuccess());
        event.put("strategy", routing.getStrategy() != null ? routing.getStrategy().name() : null);
        event.put("confidence", routing.getConfidence());
        event.put("rationale", routing.getRationale());
        event.put("vectorSpace", routing.getVectorSpace());
        event.put("candidateSpaces", routing.getCandidateSpaces());
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
