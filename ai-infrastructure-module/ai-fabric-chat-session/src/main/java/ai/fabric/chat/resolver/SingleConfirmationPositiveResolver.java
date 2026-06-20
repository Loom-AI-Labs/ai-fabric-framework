package ai.fabric.chat.resolver;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.action.PendingActionStore;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import java.util.List;
import java.util.Map;

/**
 * Resolves a single positive confirmation ("yes") into the pending ACTION.
 */
public class SingleConfirmationPositiveResolver extends ConfirmationResolverSupport {

    public SingleConfirmationPositiveResolver(PendingActionStore pendingActionStore) {
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
        if (!hasPositiveConfirmation(intentResponse)) {
            return false;
        }
        return intentResponse.getIntents().size() == 1;
    }

    @Override
    public PipelineContext resolve(MultiIntentResponse intentResponse,
                                   Map<String, Object> sessionMetadata,
                                   PipelineContext context) {
        PendingAction pending = peekPending(context);
        if (pending == null || isExpired(pending)) {
            return context;
        }

        PendingAction confirmed = popPending(context);
        if (confirmed == null || isExpired(confirmed)) {
            return context;
        }

        Intent confirmedAction = Intent.builder()
            .type(IntentType.ACTION)
            .action(confirmed.action())
            .actionParams(confirmed.actionParams() != null ? confirmed.actionParams() : Map.of())
            .confidence(1.0d)
            .build();

        MultiIntentResponse updated = MultiIntentResponse.builder()
            .intents(List.of(confirmedAction))
            .orchestrationStrategy(intentResponse != null ? intentResponse.getOrchestrationStrategy() : null)
            .metadata(intentResponse != null ? intentResponse.getMetadata() : Map.of())
            .build();

        PipelineContext marked = markConfirmed(context, confirmed);
        return marked.toBuilder()
            .intentResponse(updated)
            .build();
    }

    @Override
    public int getPriority() {
        return 50;
    }

    @Override
    public String getResolverName() {
        return "SingleConfirmationPositiveResolver";
    }
}
