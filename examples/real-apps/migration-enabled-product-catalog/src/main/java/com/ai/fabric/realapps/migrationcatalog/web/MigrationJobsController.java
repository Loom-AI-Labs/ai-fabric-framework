package com.ai.fabric.realapps.migrationcatalog.web;

import ai.fabric.migration.domain.MigrationFilters;
import ai.fabric.migration.domain.MigrationJob;
import ai.fabric.migration.domain.MigrationProgress;
import ai.fabric.migration.domain.MigrationRequest;
import ai.fabric.migration.service.DataMigrationService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

@RestController
@RequestMapping("/api/migration/jobs")
@RequiredArgsConstructor
public class MigrationJobsController {

    private final DataMigrationService dataMigrationService;

    @PostMapping("/products/start")
    public MigrationJob startProductsJob(@RequestParam(value = "batchSize", defaultValue = "500") Integer batchSize,
                                         @RequestParam(value = "rateLimit", defaultValue = "600") Integer rateLimit,
                                         @RequestParam(value = "reindexExisting", defaultValue = "false") Boolean reindexExisting,
                                         @RequestParam(value = "createdBy", defaultValue = "demo") String createdBy,
                                         @RequestBody(required = false) StartFilters filters) {
        MigrationFilters migrationFilters = filters != null ? filters.toFilters() : null;
        MigrationRequest request = MigrationRequest.builder()
            .entityType("product")
            .batchSize(batchSize)
            .rateLimit(rateLimit)
            .reindexExisting(reindexExisting)
            .filters(migrationFilters)
            .createdBy(createdBy)
            .build();
        return dataMigrationService.startMigration(request);
    }

    @GetMapping
    public List<MigrationJob> listJobs() {
        return StreamSupport.stream(dataMigrationService.listJobs().spliterator(), false).toList();
    }

    @GetMapping("/{jobId}/progress")
    public MigrationProgress progress(@PathVariable String jobId) {
        return dataMigrationService.getProgress(jobId);
    }

    @PostMapping("/{jobId}/pause")
    public Map<String, Object> pause(@PathVariable String jobId) {
        dataMigrationService.pauseMigration(jobId);
        return Map.of("status", "PAUSED", "jobId", jobId);
    }

    @PostMapping("/{jobId}/resume")
    public Map<String, Object> resume(@PathVariable String jobId) {
        dataMigrationService.resumeMigration(jobId);
        return Map.of("status", "RUNNING", "jobId", jobId);
    }

    @PostMapping("/{jobId}/cancel")
    public Map<String, Object> cancel(@PathVariable String jobId) {
        dataMigrationService.cancelMigration(jobId);
        return Map.of("status", "CANCELLED", "jobId", jobId);
    }

    public record StartFilters(String createdAfter, String createdBefore, List<@NotBlank String> entityIds) {
        MigrationFilters toFilters() {
            MigrationFilters.MigrationFiltersBuilder builder = MigrationFilters.builder();
            if (createdAfter != null && !createdAfter.isBlank()) {
                builder.createdAfter(java.time.LocalDate.parse(createdAfter));
            }
            if (createdBefore != null && !createdBefore.isBlank()) {
                builder.createdBefore(java.time.LocalDate.parse(createdBefore));
            }
            if (entityIds != null && !entityIds.isEmpty()) {
                builder.entityIds(entityIds);
            }
            return builder.build();
        }
    }
}
