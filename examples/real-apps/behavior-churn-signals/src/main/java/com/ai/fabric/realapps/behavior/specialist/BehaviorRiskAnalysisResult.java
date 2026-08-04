package com.ai.fabric.realapps.behavior.specialist;

import java.util.List;
import java.util.Map;

public record BehaviorRiskAnalysisResult(
    String segment,
    List<String> patterns,
    Sentiment sentiment,
    Churn churn,
    String trend,
    List<String> recommendations,
    Map<String, Object> insights,
    Double confidence
) {
    public BehaviorRiskAnalysisResult {
        patterns = patterns == null ? List.of() : List.copyOf(patterns);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        insights = insights == null ? Map.of() : Map.copyOf(insights);
    }

    public record Sentiment(Double score, String label) {}

    public record Churn(Double risk, String reason) {}
}
