package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.manager.ConversationManagerId;

public final class IncidentConversationManagers {

    public static final ConversationManagerId INVESTIGATION =
        ConversationManagerId.of("incident-investigation", "1");

    private IncidentConversationManagers() {}
}
