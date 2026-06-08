package com.ai.infrastructure.security;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;

/**
 * Application event published whenever response sanitization redacts sensitive information.
 */
@Getter
public class SanitizationEvent extends ApplicationEvent {

    private final String userId;
    private final ResponseSanitizer.RiskLevel riskLevel;
    private final List<String> detectedTypes;
    private final java.time.Instant occurredAt;

    public SanitizationEvent(Object source,
                             String userId,
                             ResponseSanitizer.RiskLevel riskLevel,
                             List<String> detectedTypes) {
        super(source);
        this.userId = userId;
        this.riskLevel = riskLevel;
        this.detectedTypes = detectedTypes == null ? List.of() : List.copyOf(detectedTypes);
        this.occurredAt = java.time.Instant.ofEpochMilli(super.getTimestamp());
    }
}
