package com.ai.infrastructure.spi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Behavior context DTO passed from behavior module into core.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BehaviorContext {

    private String userId;
    private String sessionId;

    private String sentimentLabel;
    private Double sentimentScore;

    private Double churnRisk;
    private String churnReason;

    private String segment;
    private String trend;
    private List<String> patterns;
    private List<String> recommendations;

    private Double confidence;
    private LocalDateTime analyzedAt;
    private Map<String, Object> insights;

    /**
     * Formats behavior context for prompt inclusion.
     */
    public String toPromptString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[BEHAVIOR CONTEXT]\n");
        if (segment != null) {
            sb.append("segment: ").append(segment).append(" | ");
        }
        if (sentimentLabel != null && sentimentScore != null) {
            sb.append("sentiment: ").append(sentimentLabel)
                .append(" (").append(String.format("%.2f", sentimentScore)).append(") | ");
        }
        if (churnRisk != null) {
            sb.append("churn: ").append(String.format("%.2f", churnRisk));
            if (churnReason != null) {
                sb.append(" (").append(churnReason).append(")");
            }
        }
        sb.append("\n");
        if (trend != null) {
            sb.append("trend: ").append(trend).append(" | ");
        }
        if (confidence != null) {
            sb.append("confidence: ").append(String.format("%.2f", confidence));
        }
        sb.append("\n");
        if (patterns != null && !patterns.isEmpty()) {
            sb.append("patterns: ").append(String.join(", ", patterns)).append("\n");
        }
        if (recommendations != null && !recommendations.isEmpty()) {
            sb.append("recommendations: ").append(String.join(", ", recommendations)).append("\n");
        }
        if (analyzedAt != null) {
            sb.append("analyzedAt: ").append(analyzedAt).append("\n");
        }
        return sb.toString();
    }
}
