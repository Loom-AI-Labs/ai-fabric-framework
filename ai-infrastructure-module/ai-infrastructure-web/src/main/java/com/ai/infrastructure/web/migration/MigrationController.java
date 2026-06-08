package com.ai.infrastructure.web.migration;

import com.ai.infrastructure.migration.domain.MigrationJob;
import com.ai.infrastructure.migration.domain.MigrationProgress;
import com.ai.infrastructure.migration.service.DataMigrationService;
import com.ai.infrastructure.web.migration.dto.MigrationJobDTO;
import com.ai.infrastructure.web.migration.dto.MigrationProgressDTO;
import com.ai.infrastructure.web.migration.dto.MigrationRequestDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.StreamSupport;

@Slf4j
@RestController
@RequestMapping("/api/ai/migration")
@ConditionalOnBean(DataMigrationService.class)
@RequiredArgsConstructor
public class MigrationController {

    private final DataMigrationService migrationService;

    @PostMapping("/start")
    public ResponseEntity<MigrationJobDTO> startMigration(@Valid @RequestBody MigrationRequestDTO request) {
        MigrationJob job = migrationService.startMigration(request.toRequest());
        return ResponseEntity.ok(MigrationJobDTO.from(job));
    }

    @GetMapping("/jobs")
    public ResponseEntity<List<MigrationJobDTO>> listJobs() {
        List<MigrationJobDTO> jobs = StreamSupport.stream(migrationService.listJobs().spliterator(), false)
            .map(MigrationJobDTO::from)
            .toList();
        return ResponseEntity.ok(jobs);
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<MigrationProgressDTO> getProgress(@PathVariable String id) {
        MigrationProgress progress = migrationService.getProgress(id);
        return ResponseEntity.ok(MigrationProgressDTO.from(progress));
    }

    @PostMapping("/jobs/{id}/pause")
    public ResponseEntity<Void> pauseJob(@PathVariable String id) {
        migrationService.pauseMigration(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/jobs/{id}/resume")
    public ResponseEntity<Void> resumeJob(@PathVariable String id) {
        migrationService.resumeMigration(id);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/jobs/{id}")
    public ResponseEntity<Void> cancelJob(@PathVariable String id) {
        migrationService.cancelMigration(id);
        return ResponseEntity.noContent().build();
    }
}
