package com.ai.infrastructure.migration.repository;

import com.ai.infrastructure.migration.domain.MigrationJob;
import com.ai.infrastructure.migration.domain.MigrationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MigrationJobRepository extends JpaRepository<MigrationJob, String> {

    List<MigrationJob> findByStatusIn(List<MigrationStatus> statuses);

    void deleteByCompletedAtBefore(LocalDateTime cutoff);
}
