package com.ai.infrastructure.security.policy;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Value object returned from {@link SecurityAnalysisPolicy}.
 */
public final class SecurityAnalysisResult {

    private final List<String> threats;
    private final Double score;
    private final List<String> recommendations;
    private final LocalDateTime timestamp;

    private SecurityAnalysisResult(Builder builder) {
        this.threats = builder.threats == null
            ? List.of()
            : List.copyOf(builder.threats);
        this.score = builder.score;
        this.recommendations = builder.recommendations == null
            ? List.of()
            : List.copyOf(builder.recommendations);
        this.timestamp = builder.timestamp != null ? builder.timestamp : LocalDateTime.now();
    }

    public List<String> getThreats() {
        return threats;
    }

    public Double getScore() {
        return score;
    }

    public List<String> getRecommendations() {
        return recommendations;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder from(SecurityAnalysisResult result) {
        return builder()
            .threats(result.threats)
            .score(result.score)
            .recommendations(result.recommendations)
            .timestamp(result.timestamp);
    }

    public static final class Builder {
        private List<String> threats = Collections.emptyList();
        private Double score;
        private List<String> recommendations = Collections.emptyList();
        private LocalDateTime timestamp;

        private Builder() {
        }

        public Builder threats(List<String> threats) {
            this.threats = threats;
            return this;
        }

        public Builder score(Double score) {
            this.score = score;
            return this;
        }

        public Builder recommendations(List<String> recommendations) {
            this.recommendations = recommendations;
            return this;
        }

        public Builder timestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public SecurityAnalysisResult build() {
            Objects.requireNonNull(threats, "threats");
            return new SecurityAnalysisResult(this);
        }
    }
}
