package com.ai.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request model for scanning vectors in a paged manner, optionally filtered by metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorScanRequest {

    /**
     * Entity type to scan within.
     */
    private String entityType;

    /**
     * Exact-match metadata filters.
     * Provider support may vary.
     */
    private Map<String, Object> metadataEquals;

    /**
     * Maximum number of items to return.
     */
    @Builder.Default
    private Integer limit = 200;

    /**
     * Opaque cursor returned from a previous scan call.
     */
    private String cursor;

    /**
     * Whether to include content in returned VectorRecords.
     */
    @Builder.Default
    private boolean includeContent = true;

    /**
     * Whether to include embeddings in returned VectorRecords.
     */
    @Builder.Default
    private boolean includeEmbedding = false;

    /**
     * Whether to include metadata in returned VectorRecords.
     */
    @Builder.Default
    private boolean includeMetadata = true;
}

