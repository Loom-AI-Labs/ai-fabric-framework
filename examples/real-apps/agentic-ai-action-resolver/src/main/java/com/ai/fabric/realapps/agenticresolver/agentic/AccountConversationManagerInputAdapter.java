package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.manager.ConversationManagerComponentId;
import ai.fabric.execution.manager.ConversationManagerContextValue;
import ai.fabric.execution.manager.ConversationManagerInputAdapter;
import java.util.ArrayList;
import java.util.List;

public final class AccountConversationManagerInputAdapter
    implements ConversationManagerInputAdapter<
        AccountDelegationCoordinatorRequest
    > {

    public static final ConversationManagerComponentId ID =
        ConversationManagerComponentId.of(
            "account-conversation-input",
            "1"
        );

    @Override
    public ConversationManagerComponentId id() {
        return ID;
    }

    @Override
    public Class<AccountDelegationCoordinatorRequest> inputType() {
        return AccountDelegationCoordinatorRequest.class;
    }

    @Override
    public String currentUserMessage(
        AccountDelegationCoordinatorRequest input
    ) {
        return input.question();
    }

    @Override
    public List<ConversationManagerContextValue> applicationContext(
        AccountDelegationCoordinatorRequest input
    ) {
        List<ConversationManagerContextValue> context = new ArrayList<>();
        context.add(new ConversationManagerContextValue(
            "billingInputState",
            billingInputState(input).name()
        ));
        if (input.resolutionType() != null) {
            context.add(new ConversationManagerContextValue(
                "resolutionType",
                input.resolutionType().name()
            ));
        }
        if (input.amount() != null) {
            context.add(new ConversationManagerContextValue(
                "amount",
                input.amount().stripTrailingZeros().toPlainString()
            ));
        }
        return List.copyOf(context);
    }

    private BillingInputState billingInputState(
        AccountDelegationCoordinatorRequest input
    ) {
        if (input.resolutionType() == null && input.amount() == null) {
            return BillingInputState.BOTH_MISSING;
        }
        if (input.resolutionType() == null) {
            return BillingInputState.RESOLUTION_TYPE_MISSING;
        }
        if (input.amount() == null) {
            return BillingInputState.AMOUNT_MISSING;
        }
        return BillingInputState.COMPLETE;
    }

    private enum BillingInputState {
        BOTH_MISSING,
        RESOLUTION_TYPE_MISSING,
        AMOUNT_MISSING,
        COMPLETE
    }
}
