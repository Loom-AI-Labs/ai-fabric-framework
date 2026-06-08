package com.ai.infrastructure.migration.config;

import lombok.Data;

/**
 * Field mapping for default migration filtering.
 */
@Data
public class MigrationFieldConfig {
    /**
     * Name of the field holding created timestamp (LocalDateTime or LocalDate).
     */
    private String createdAtField;
}
