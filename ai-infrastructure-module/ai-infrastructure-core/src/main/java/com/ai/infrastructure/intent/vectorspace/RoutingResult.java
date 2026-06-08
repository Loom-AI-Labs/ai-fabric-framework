package com.ai.infrastructure.intent.vectorspace;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Result of vectorSpace routing.
 */
@Data
@Builder
public class RoutingResult {

    private boolean success;

    /**
     * Single vectorSpace for simple routing.
     */
    private String vectorSpace;

    /**
     * Candidate spaces for fan-out routing.
     */
    private List<String> candidateSpaces;

    private RoutingStrategy strategy;

    /**
     * Confidence in the routing decision (0.0 to 1.0).
     */
    private double confidence;

    /**
     * Human-readable rationale for observability.
     */
    private String rationale;

    public boolean requiresFanOut() {
        return strategy == RoutingStrategy.FAN_OUT
            && candidateSpaces != null
            && candidateSpaces.size() > 1;
    }
}

