package com.ai.infrastructure.datasync;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for push-based data sync (ingestion) into a managed vector database.
 *
 * <p>This module is opt-in and is enabled only when {@code ai.data-sync.enabled=true}.</p>
 */
@Validated
@ConfigurationProperties(prefix = "ai.data-sync")
public class AIDataSyncProperties {

    /**
     * Master switch for the data sync API.
     */
    private boolean enabled = false;

    /**
     * Max number of operations allowed in a single batch request.
     */
    @Min(1)
    @Max(5_000)
    private int maxBatchSize = 200;

    /**
     * Max chars allowed for normalized content passed to embedding generation.
     *
     * <p>This should not exceed the limit enforced by {@code AIEmbeddingRequest}.</p>
     */
    @Min(1)
    @Max(8_000)
    private int maxContentChars = 8_000;

    /**
     * Max chars allowed per normalized field value when building content from an entity payload.
     */
    @Min(1)
    @Max(4_000)
    private int maxFieldValueChars = 2_000;

    /**
     * Max number of metadata keys allowed after normalization (defense in depth).
     */
    @Min(0)
    @Max(500)
    private int maxMetadataKeys = 75;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    public int getMaxContentChars() {
        return maxContentChars;
    }

    public void setMaxContentChars(int maxContentChars) {
        this.maxContentChars = maxContentChars;
    }

    public int getMaxFieldValueChars() {
        return maxFieldValueChars;
    }

    public void setMaxFieldValueChars(int maxFieldValueChars) {
        this.maxFieldValueChars = maxFieldValueChars;
    }

    public int getMaxMetadataKeys() {
        return maxMetadataKeys;
    }

    public void setMaxMetadataKeys(int maxMetadataKeys) {
        this.maxMetadataKeys = maxMetadataKeys;
    }
}
