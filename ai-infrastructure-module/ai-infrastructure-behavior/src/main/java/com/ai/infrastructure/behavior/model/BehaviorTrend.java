package com.ai.infrastructure.behavior.model;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Overall behavioral trend direction.
 */
@Getter
@RequiredArgsConstructor
public enum BehaviorTrend {

    RAPIDLY_IMPROVING("RAPIDLY_IMPROVING", "Major positive shift", 5),
    IMPROVING("IMPROVING", "Positive shift", 4),
    STABLE("STABLE", "No significant change", 3),
    DECLINING("DECLINING", "Negative shift", 2),
    RAPIDLY_DECLINING("RAPIDLY_DECLINING", "Major negative shift - ALERT", 1),
    NEW_USER("NEW_USER", "Baseline analysis", 0);

    @JsonValue
    private final String value;
    private final String description;
    private final int severity;

    public static BehaviorTrend fromString(String value) {
        if (value == null) {
            return STABLE;
        }
        for (BehaviorTrend trend : values()) {
            if (trend.value.equalsIgnoreCase(value)) {
                return trend;
            }
        }
        return STABLE;
    }

    public static BehaviorTrend fromDeltas(Double sentimentDelta, Double churnDelta, boolean isNewUser) {
        if (isNewUser) {
            return NEW_USER;
        }
        if (sentimentDelta == null && churnDelta == null) {
            return STABLE;
        }
        double sDelta = sentimentDelta != null ? sentimentDelta : 0.0;
        double cDelta = churnDelta != null ? churnDelta : 0.0;

        if (sDelta < -0.4 || cDelta > 0.4) return RAPIDLY_DECLINING;
        if (sDelta < -0.2 || cDelta > 0.2) return DECLINING;
        if (sDelta > 0.4 || cDelta < -0.4) return RAPIDLY_IMPROVING;
        if (sDelta > 0.2 || cDelta < -0.2) return IMPROVING;
        return STABLE;
    }

    public boolean requiresIntervention() {
        return this == RAPIDLY_DECLINING;
    }

    public boolean isNegative() {
        return this == DECLINING || this == RAPIDLY_DECLINING;
    }

    public boolean isPositive() {
        return this == IMPROVING || this == RAPIDLY_IMPROVING;
    }
}
