package com.ai.infrastructure.relationship.model;

import lombok.Builder;
import lombok.Data;

/**
 * Runtime options controlling how a relationship query should be executed.
 * 
 * <p>These options are APPLICATION-LEVEL parameters, NOT extracted by the LLM.
 * They control output format and result pagination.</p>
 */
@Data
@Builder
public class QueryOptions {
    /**
     * Return mode: IDS (entity IDs only) or FULL (complete content).
     * Default: IDS
     */
    private ReturnMode returnMode;
    
    /**
     * Vector similarity threshold (0.0-1.0) for semantic search.
     * Only used when ENHANCED mode is active.
     */
    private Double similarityThreshold;
    
    /**
     * Maximum number of results to return.
     * Default: configured in RelationshipQueryProperties
     */
    private Integer limit;
    
    @Builder.Default
    private boolean includeVectorScores = false;

    public static QueryOptions defaults() {
        return QueryOptions.builder()
            .returnMode(ReturnMode.IDS)
            .build();
    }
}
