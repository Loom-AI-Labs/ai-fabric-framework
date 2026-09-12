package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.manager.ConversationManagerId;

public final class IncidentConversationManagers {

    public static final ConversationManagerId INVESTIGATION =
        ConversationManagerId.of("incident-investigation", "1");
    public static final ConversationManagerId INVESTIGATION_V2 =
        ConversationManagerId.of("incident-investigation", "2");

    private IncidentConversationManagers() {}
}
