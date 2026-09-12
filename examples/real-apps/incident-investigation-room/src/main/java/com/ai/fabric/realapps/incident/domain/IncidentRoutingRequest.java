package com.ai.fabric.realapps.incident.domain;

public record IncidentRoutingRequest(
    String question,
    String requestedTransition,
    IncidentRoutingScope incident
) {}
