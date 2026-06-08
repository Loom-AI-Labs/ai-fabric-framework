package com.ai.infrastructure.web.migration.dto;

import com.ai.infrastructure.migration.domain.MigrationRequest;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MigrationRequestDTO {

    @NotBlank
    private String entityType;

    @Min(1)
    private Integer batchSize;

    private Integer rateLimit;

    private Boolean reindexExisting;

    @Valid
    private MigrationFiltersDTO filters;

    private String createdBy;

    public MigrationRequest toRequest() {
        return MigrationRequest.builder()
            .entityType(entityType)
            .batchSize(batchSize)
            .rateLimit(rateLimit)
            .reindexExisting(reindexExisting)
            .filters(filters != null ? filters.toFilters() : null)
            .createdBy(createdBy)
            .build();
    }
}
