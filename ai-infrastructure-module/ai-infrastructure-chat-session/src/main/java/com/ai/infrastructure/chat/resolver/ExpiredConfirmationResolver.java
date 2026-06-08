package com.ai.infrastructure.chat.resolver;

import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.intent.action.PendingAction;
import com.ai.infrastructure.intent.action.PendingActionStore;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import java.util.Map;

/**
 * Clears expired pending confirmations.
 */
public class ExpiredConfirmationResolver extends ConfirmationResolverSupport {

    public ExpiredConfirmationResolver(PendingActionStore pendingActionStore) {
        super(pendingActionStore);
    }

    @Override
    public boolean canResolve(MultiIntentResponse intentResponse,
                              Map<String, Object> sessionMetadata,
                              PipelineContext context) {
        PendingAction pending = peekPending(context);
        return pending != null && isExpired(pending);
    }

    @Override
    public PipelineContext resolve(MultiIntentResponse intentResponse,
                                   Map<String, Object> sessionMetadata,
                                   PipelineContext context) {
        // Pop only the current (top) pending action. If there are older entries under it, they remain.
        popPending(context);
        return context;
    }

    @Override
    public int getPriority() {
        return 5;
    }

    @Override
    public String getResolverName() {
        return "ExpiredConfirmationResolver";
    }
}
