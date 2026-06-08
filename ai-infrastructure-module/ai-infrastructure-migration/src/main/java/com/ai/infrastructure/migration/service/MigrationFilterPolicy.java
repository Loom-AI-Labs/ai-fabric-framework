package com.ai.infrastructure.migration.service;

import com.ai.infrastructure.dto.AIEntityConfig;
import com.ai.infrastructure.migration.domain.MigrationRequest;

/**
 * Pluggable policy to decide whether a given entity should be migrated.
 */
public interface MigrationFilterPolicy {

    /**
     * Whether this policy applies to the given entity type.
     */
    boolean supports(String entityType);

    /**
     * Return true if the entity should be migrated.
     */
    boolean shouldMigrate(Object entity, MigrationRequest request, AIEntityConfig config);
}
