package com.ai.infrastructure.migration.domain;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class MigrationRequest {

    @NotBlank
    String entityType;

    @Min(1)
    @Builder.Default
    Integer batchSize = 500;

    @Builder.Default
    Integer rateLimit = null;

    @Builder.Default
    Boolean reindexExisting = Boolean.FALSE;

    MigrationFilters filters;

    String createdBy;
}
