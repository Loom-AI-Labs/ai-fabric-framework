package com.ai.infrastructure.migration.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * Optional filters to narrow which entities are migrated.
 * Kept intentionally generic so it can be mapped to JPA entities
 * with common timestamp/id field names.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationFilters {

    private LocalDate createdBefore;
    private LocalDate createdAfter;
    private List<String> entityIds;

    public List<String> safeEntityIds() {
        return entityIds == null ? Collections.emptyList() : entityIds;
    }

    public boolean isEmpty() {
        return createdBefore == null && createdAfter == null && safeEntityIds().isEmpty();
    }
}
