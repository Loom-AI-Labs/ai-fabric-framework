package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.chain.SpecialistChainComponentId;
import ai.fabric.execution.chain.SpecialistChainInputAdapter;
import ai.fabric.execution.manager.ConversationManagerContextValue;
import java.util.ArrayList;
import java.util.List;

public final class AccountChainInputAdapter
    implements SpecialistChainInputAdapter<AccountDelegationCoordinatorRequest> {

    public static final SpecialistChainComponentId ID =
        SpecialistChainComponentId.of("account-chain-input", "1");

    @Override
    public SpecialistChainComponentId id() {
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
            billingInputState(input)
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

    private String billingInputState(
        AccountDelegationCoordinatorRequest input
    ) {
        if (input.resolutionType() == null && input.amount() == null) {
            return "BOTH_MISSING";
        }
        if (input.resolutionType() == null) {
            return "RESOLUTION_TYPE_MISSING";
        }
        if (input.amount() == null) {
            return "AMOUNT_MISSING";
        }
        return "COMPLETE";
    }
}
