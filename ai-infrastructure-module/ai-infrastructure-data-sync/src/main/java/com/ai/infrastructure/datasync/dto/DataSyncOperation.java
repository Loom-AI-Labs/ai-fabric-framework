package com.ai.infrastructure.datasync.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Single operation inside a batch sync request.
 */
public class DataSyncOperation {

    @NotNull
    private DataSyncOperationType type;

    @NotBlank
    private String vectorSpace;

    @NotBlank
    private String id;

    /**
     * Pre-normalized text content for UPSERT operations.
     */
    private String content;

    /**
     * Optional raw entity payload for UPSERT operations.
     */
    private Map<String, Object> entity;

    /**
     * Optional metadata to store alongside the vector.
     */
    private Map<String, Object> metadata;

    /**
     * Optional stable identity hints used for deterministic chunk ids and retry-safe idempotency.
     */
    private DataSyncIdentity identity;

    public DataSyncOperation() {
    }

    public DataSyncOperation(DataSyncOperationType type,
                             String vectorSpace,
                             String id,
                             String content,
                             Map<String, Object> entity,
                             Map<String, Object> metadata,
                             DataSyncIdentity identity) {
        this.type = type;
        this.vectorSpace = vectorSpace;
        this.id = id;
        this.content = content;
        this.entity = entity;
        this.metadata = metadata;
        this.identity = identity;
    }

    public DataSyncOperationType getType() {
        return type;
    }

    public void setType(DataSyncOperationType type) {
        this.type = type;
    }

    public String getVectorSpace() {
        return vectorSpace;
    }

    public void setVectorSpace(String vectorSpace) {
        this.vectorSpace = vectorSpace;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Map<String, Object> getEntity() {
        return entity;
    }

    public void setEntity(Map<String, Object> entity) {
        this.entity = entity;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public DataSyncIdentity getIdentity() {
        return identity;
    }

    public void setIdentity(DataSyncIdentity identity) {
        this.identity = identity;
    }
}
