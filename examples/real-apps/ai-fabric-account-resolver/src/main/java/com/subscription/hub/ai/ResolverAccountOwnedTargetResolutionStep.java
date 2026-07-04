package com.subscription.hub.ai;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Keeps Account Resolver's current-account actions out of generic target resolution.
 */
@Component
public class ResolverAccountOwnedTargetResolutionStep implements PipelineStep {

    private static final String STEP_NAME = "ResolverAccountOwnedTargetResolution";
    private static final int STEP_ORDER = 51;
    private static final String METADATA_KEY = "resolverAccountOwnedTargetResolution";

    private static final Set<String> ACCOUNT_OWNED_ACTIONS = Set.of(
        "get_account_profile",
        "update_payment_method",
        "update_address",
        "request_refund",
        "cancel_subscription",
        "upgrade_subscription",
        "downgrade_subscription",
        "subscribe"
    );

    @Override
    public PipelineContext process(PipelineContext context) {
        if (context == null || context.isShouldTerminate()) {
            return context;
        }
        MultiIntentResponse response = context.getIntentResponse();
        if (response == null || response.getIntents() == null || response.getIntents().isEmpty()) {
            return context;
        }

        List<Intent> rewritten = new ArrayList<>(response.getIntents().size());
        List<String> adjustedActions = new ArrayList<>();
        boolean changed = false;

        for (Intent intent : response.getIntents()) {
            if (shouldClearTargetResolution(intent)) {
                rewritten.add(copyWithTargetResolution(intent, false));
                adjustedActions.add(canonicalAction(intent));
                changed = true;
            } else {
                rewritten.add(intent);
            }
        }

        if (!changed) {
            return context;
        }

        MultiIntentResponse rewrittenResponse = MultiIntentResponse.builder()
            .intents(rewritten)
            .orchestrationStrategy(response.getOrchestrationStrategy())
            .metadata(response.getMetadata() != null ? response.getMetadata() : Map.of())
            .build();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("adjusted", true);
        metadata.put("actions", List.copyOf(adjustedActions));
        metadata.put("reason", "current_account_action");

        return context.toBuilder()
            .intentResponse(rewrittenResponse)
            .build()
            .withMetadata(METADATA_KEY, Map.copyOf(metadata));
    }

    @Override
    public String getStepName() {
        return STEP_NAME;
    }

    @Override
    public int getOrder() {
        return STEP_ORDER;
    }

    private boolean shouldClearTargetResolution(Intent intent) {
        return intent != null
            && intent.getType() == IntentType.ACTION
            && Boolean.TRUE.equals(intent.getRequiresTargetResolution())
            && ACCOUNT_OWNED_ACTIONS.contains(canonicalAction(intent));
    }

    private String canonicalAction(Intent intent) {
        if (intent == null) {
            return "";
        }
        String value = intent.getAction();
        if (value == null || value.isBlank()) {
            value = intent.getIntent();
        }
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private Intent copyWithTargetResolution(Intent intent, boolean requiresTargetResolution) {
        return Intent.builder()
            .type(intent.getType())
            .intent(intent.getIntent())
            .confidence(intent.getConfidence())
            .action(intent.getAction())
            .actionParams(intent.getActionParams())
            .vectorSpace(intent.getVectorSpace())
            .requiresRetrieval(intent.getRequiresRetrieval())
            .requiresGeneration(intent.getRequiresGeneration())
            .responseProfile(intent.getResponseProfile())
            .requiresTargetResolution(requiresTargetResolution)
            .directAnswer(intent.getDirectAnswer())
            .generationInstructions(intent.getGenerationInstructions())
            .needsAdvancedRAG(intent.getNeedsAdvancedRAG())
            .optimizedQuery(intent.getOptimizedQuery())
            .nextStepRecommended(intent.getNextStepRecommended())
            .build();
    }
}
