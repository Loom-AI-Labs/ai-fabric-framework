package ai.fabric.chat.resolver;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.action.PendingActionStore;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Handles compound turns like "yes and show me laptops" when a pending action exists.
 */
public class CompoundConfirmationResolver extends ConfirmationResolverSupport {

    public CompoundConfirmationResolver(PendingActionStore pendingActionStore) {
        super(pendingActionStore);
    }

    @Override
    public boolean canResolve(MultiIntentResponse intentResponse,
                              Map<String, Object> sessionMetadata,
                              PipelineContext context) {
        PendingAction pending = peekPending(context);
        if (pending == null || isExpired(pending)) {
            return false;
        }
        if (intentResponse == null || intentResponse.getIntents() == null || intentResponse.getIntents().isEmpty()) {
            return false;
        }
        if (!hasAnyConfirmationIntent(intentResponse)) {
            return false;
        }
        return intentResponse.getIntents().size() > 1;
    }

    @Override
    public PipelineContext resolve(MultiIntentResponse intentResponse,
                                   Map<String, Object> sessionMetadata,
                                   PipelineContext context) {
        PendingAction pending = peekPending(context);
        if (pending == null || isExpired(pending)) {
            return context;
        }

        boolean positive = hasPositiveConfirmation(intentResponse);
        boolean negative = hasNegativeConfirmation(intentResponse);
        List<Intent> remaining = withoutConfirmationIntents(intentResponse);

        List<Intent> next = new ArrayList<>();

        if (positive) {
            PendingAction confirmed = popPending(context);
            if (confirmed != null && !isExpired(confirmed)) {
                Intent confirmedAction = Intent.builder()
                    .type(IntentType.ACTION)
                    .action(confirmed.action())
                    .actionParams(confirmed.actionParams() != null ? confirmed.actionParams() : Map.of())
                    .confidence(1.0d)
                    .build();
                next.add(confirmedAction);
                context = markConfirmed(context, confirmed);
            }
        } else if (negative) {
            popPending(context);
            // If user cancelled and there are no other intents, keep a single confirmation-negative intent so the
            // orchestration layer can respond deterministically (without executing any action).
            if (remaining.isEmpty()) {
                next.add(Intent.builder()
                    .type(IntentType.CONFIRMATION_NEGATIVE)
                    .confidence(1.0d)
                    .build());
            }
        }

        if (!remaining.isEmpty()) {
            remaining.stream().filter(Objects::nonNull).forEach(next::add);
        }

        MultiIntentResponse updated = MultiIntentResponse.builder()
            .intents(next)
            .orchestrationStrategy(intentResponse.getOrchestrationStrategy())
            .metadata(intentResponse.getMetadata())
            .build();

        return context.toBuilder()
            .intentResponse(updated)
            .build();
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public String getResolverName() {
        return "CompoundConfirmationResolver";
    }
}
