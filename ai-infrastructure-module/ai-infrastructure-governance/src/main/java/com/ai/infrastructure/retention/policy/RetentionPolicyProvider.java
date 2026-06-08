package com.ai.infrastructure.retention.policy;

import com.ai.infrastructure.governance.catalog.IndexCatalogEntry;

/**
 * Infrastructure hook allowing customers to plug in data retention rules for indexed entities.
 */
public interface RetentionPolicyProvider {

    /**
     * Determine how long data with the supplied classification and entity type should be retained.
     *
     * @param classification data classification (PUBLIC, INTERNAL, CONFIDENTIAL, etc.)
     * @param entityType logical entity type
     * @return number of days the entity should be retained (0 = delete immediately, -1 = never delete)
     */
    int getRetentionDays(String classification, String entityType);

    /**
     * Decide if the provided entity should be deleted based on customer logic.
     *
     * @param entry catalog entry under evaluation
     * @return {@code true} when the entity should be deleted
     */
    boolean shouldDelete(IndexCatalogEntry entry);

    /**
     * Execute any required customer side clean-up before deletion occurs.
     *
     * @param entry entry that is about to be deleted
     * @return {@code true} when deletion may proceed
     */
    boolean executeDelete(IndexCatalogEntry entry);
}

