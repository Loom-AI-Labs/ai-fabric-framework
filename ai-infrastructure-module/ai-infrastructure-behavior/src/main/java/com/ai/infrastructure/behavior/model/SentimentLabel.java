package com.ai.infrastructure.behavior.model;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * User sentiment classification based on behavioral signals.
 */
@Getter
@RequiredArgsConstructor
public enum SentimentLabel {

    DELIGHTED("DELIGHTED", "Extremely positive engagement"),
    SATISFIED("SATISFIED", "Positive experience"),
    NEUTRAL("NEUTRAL", "No strong sentiment"),
    CONFUSED("CONFUSED", "Help-seeking behavior"),
    FRUSTRATED("FRUSTRATED", "Friction detected"),
    CHURNING("CHURNING", "Imminent departure signals");

    @JsonValue
    private final String value;
    private final String description;

    public static SentimentLabel fromString(String value) {
        if (value == null) {
            return NEUTRAL;
        }
        for (SentimentLabel label : values()) {
            if (label.value.equalsIgnoreCase(value)) {
                return label;
            }
        }
        return NEUTRAL;
    }

    public boolean isNegative() {
        return this == FRUSTRATED || this == CHURNING;
    }

    public boolean isPositive() {
        return this == DELIGHTED || this == SATISFIED;
    }
}
