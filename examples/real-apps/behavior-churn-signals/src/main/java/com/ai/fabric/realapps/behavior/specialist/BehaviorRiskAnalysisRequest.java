package com.ai.fabric.realapps.behavior.specialist;

import java.util.List;
import java.util.Map;

public record BehaviorRiskAnalysisRequest(
    String analysisRequest,
    PreviousInsight previousInsight,
    List<EventFact> newEvents,
    Map<String, Object> accountContext
) {
    public BehaviorRiskAnalysisRequest {
        newEvents = newEvents == null ? List.of() : List.copyOf(newEvents);
        accountContext = accountContext == null ? Map.of() : Map.copyOf(accountContext);
    }

    public record PreviousInsight(
        String segment,
        String sentimentLabel,
        Double sentimentScore,
        Double churnRisk,
        String churnReason,
        String trend,
        List<String> patterns,
        List<String> recommendations,
        Double confidence,
        String analyzedAt
    ) {}

    public record EventFact(
        String eventId,
        String eventType,
        String occurredAt,
        String source,
        Map<String, Object> facts
    ) {
        public EventFact {
            facts = facts == null ? Map.of() : Map.copyOf(facts);
        }
    }
}
