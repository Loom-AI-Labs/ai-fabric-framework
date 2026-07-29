package com.ai.fabric.realapps.agenticresolver.agentic;

public record AccountDelegationDecision(
    Decision decision,
    String targetSpecialist,
    String reason
) {
    public enum Decision {
        COMPLETE,
        DELEGATE
    }
}
