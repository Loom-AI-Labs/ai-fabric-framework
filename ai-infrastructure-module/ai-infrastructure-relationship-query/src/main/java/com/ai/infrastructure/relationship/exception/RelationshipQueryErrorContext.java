package com.ai.infrastructure.relationship.exception;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Structured diagnostic context describing the state of a relationship query failure.
 */
public final class RelationshipQueryErrorContext {

    private final String originalQuery;
    private final String executionStage;
    private final String primaryEntityType;
    private final List<String> candidateEntityTypes;
    private final boolean fallbackUsed;
    private final Map<String, Object> attributes;
    private final Instant timestamp;

    private RelationshipQueryErrorContext(Builder builder) {
        this.originalQuery = builder.originalQuery;
        this.executionStage = builder.executionStage;
        this.primaryEntityType = builder.primaryEntityType;
        this.candidateEntityTypes = builder.candidateEntityTypes == null
            ? List.of()
            : List.copyOf(builder.candidateEntityTypes);
        this.fallbackUsed = builder.fallbackUsed;
        this.attributes = builder.attributes == null
            ? Map.of()
            : Collections.unmodifiableMap(builder.attributes);
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
    }

    public String getOriginalQuery() {
        return originalQuery;
    }

    public String getExecutionStage() {
        return executionStage;
    }

    public String getPrimaryEntityType() {
        return primaryEntityType;
    }

    public List<String> getCandidateEntityTypes() {
        return candidateEntityTypes;
    }

    public boolean isFallbackUsed() {
        return fallbackUsed;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return builder()
            .originalQuery(this.originalQuery)
            .executionStage(this.executionStage)
            .primaryEntityType(this.primaryEntityType)
            .candidateEntityTypes(this.candidateEntityTypes)
            .fallbackUsed(this.fallbackUsed)
            .attributes(this.attributes)
            .timestamp(this.timestamp);
    }

    public static final class Builder {
        private String originalQuery;
        private String executionStage;
        private String primaryEntityType;
        private List<String> candidateEntityTypes;
        private boolean fallbackUsed;
        private Map<String, Object> attributes;
        private Instant timestamp;

        private Builder() { }

        public Builder originalQuery(String originalQuery) {
            this.originalQuery = originalQuery;
            return this;
        }

        public Builder executionStage(String executionStage) {
            this.executionStage = executionStage;
            return this;
        }

        public Builder primaryEntityType(String primaryEntityType) {
            this.primaryEntityType = primaryEntityType;
            return this;
        }

        public Builder candidateEntityTypes(List<String> candidateEntityTypes) {
            this.candidateEntityTypes = candidateEntityTypes;
            return this;
        }

        public Builder fallbackUsed(boolean fallbackUsed) {
            this.fallbackUsed = fallbackUsed;
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            this.attributes = attributes;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public RelationshipQueryErrorContext build() {
            return new RelationshipQueryErrorContext(this);
        }
    }
}
