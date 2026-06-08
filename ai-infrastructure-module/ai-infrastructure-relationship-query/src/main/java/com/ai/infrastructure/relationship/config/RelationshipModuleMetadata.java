package com.ai.infrastructure.relationship.config;

import com.ai.infrastructure.relationship.model.QueryMode;
import com.ai.infrastructure.relationship.model.ReturnMode;

import java.time.Duration;

/**
 * Snapshot of derived settings that other beans can consume without coupling directly to the mutable
 * {@link RelationshipQueryProperties} instance.
 */
public record RelationshipModuleMetadata(
    boolean vectorSearchEnabled,
    boolean queryCachingEnabled,
    long queryCacheTtlSeconds,
    int maxTraversalDepth,
    double similarityThreshold,
    boolean fallbackToMetadata,
    boolean fallbackToVectorSearch,
    ReturnMode defaultReturnMode,
    QueryMode defaultQueryMode
) {

    public static RelationshipModuleMetadata from(RelationshipQueryProperties properties) {
        return new RelationshipModuleMetadata(
            properties.isEnableVectorSearch(),
            properties.isEnableQueryCaching(),
            properties.getQueryCacheTtlSeconds(),
            properties.getMaxTraversalDepth(),
            properties.getDefaultSimilarityThreshold(),
            properties.isFallbackToMetadata(),
            properties.isFallbackToVectorSearch(),
            properties.getDefaultReturnMode(),
            properties.getDefaultQueryMode()
        );
    }

    public Duration cacheTtl() {
        return Duration.ofSeconds(queryCacheTtlSeconds);
    }
}
