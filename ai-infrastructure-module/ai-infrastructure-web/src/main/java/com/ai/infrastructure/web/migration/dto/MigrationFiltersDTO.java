package com.ai.infrastructure.web.migration.dto;

import com.ai.infrastructure.migration.domain.MigrationFilters;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.PastOrPresent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MigrationFiltersDTO {
    @PastOrPresent
    private LocalDate createdBefore;
    @PastOrPresent
    private LocalDate createdAfter;
    private List<String> entityIds;

    public MigrationFilters toFilters() {
        return MigrationFilters.builder()
            .createdBefore(createdBefore)
            .createdAfter(createdAfter)
            .entityIds(entityIds)
            .build();
    }
}
