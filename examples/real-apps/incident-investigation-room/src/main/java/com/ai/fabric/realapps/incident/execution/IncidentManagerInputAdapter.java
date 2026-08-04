package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.manager.ConversationManagerComponentId;
import ai.fabric.execution.manager.ConversationManagerContextValue;
import ai.fabric.execution.manager.ConversationManagerInputAdapter;
import com.ai.fabric.realapps.incident.domain.IncidentManagerRequest;
import java.util.List;

public final class IncidentManagerInputAdapter
    implements ConversationManagerInputAdapter<IncidentManagerRequest> {

    public static final ConversationManagerComponentId ID =
        ConversationManagerComponentId.of("incident-manager-input", "1");

    @Override
    public ConversationManagerComponentId id() {
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
