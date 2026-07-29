package com.ai.fabric.realapps.agenticresolver.agentic;

public record AccountHandoffDecision(
    Decision decision,
    String targetSpecialist,
    String reason
) {
    public enum Decision {
        COMPLETE,
        HANDOFF
    }
}
