package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.chain.SpecialistChainComponentId;
import ai.fabric.execution.chain.SpecialistChainInputAdapter;
import ai.fabric.execution.manager.ConversationManagerContextValue;
import com.ai.fabric.realapps.incident.domain.IncidentManagerRequest;
import java.util.List;

public final class IncidentChainInputAdapter
    implements SpecialistChainInputAdapter<IncidentManagerRequest> {

    public static final SpecialistChainComponentId ID =
        SpecialistChainComponentId.of("incident-chain-input", "1");

    @Override
    public SpecialistChainComponentId id() {
        return ID;
    }

    @Override
    public Class<IncidentManagerRequest> inputType() {
        return IncidentManagerRequest.class;
    }

    @Override
    public String currentUserMessage(IncidentManagerRequest input) {
        return input.question();
    }

    @Override
    public List<ConversationManagerContextValue> applicationContext(
        IncidentManagerRequest input
    ) {
        return List.of(
            new ConversationManagerContextValue(
                "incidentId",
                input.incident().incidentId()
            ),
            new ConversationManagerContextValue(
                "deploymentId",
                input.incident().deploymentId()
            ),
            new ConversationManagerContextValue(
                "sourceRevision",
                input.incident().sourceRevision()
            )
        );
    }
}
