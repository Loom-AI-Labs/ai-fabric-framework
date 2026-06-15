package ai.fabric.migration.repository;

import ai.fabric.migration.domain.MigrationJob;
import ai.fabric.migration.domain.MigrationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MigrationJobRepository extends JpaRepository<MigrationJob, String> {

    List<MigrationJob> findByStatusIn(List<MigrationStatus> statuses);

    void deleteByCompletedAtBefore(LocalDateTime cutoff);
}
