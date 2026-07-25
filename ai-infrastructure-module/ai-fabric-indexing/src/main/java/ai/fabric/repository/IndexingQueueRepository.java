package ai.fabric.repository;

import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.IndexingStatus;
import ai.fabric.indexing.api.IndexingStrategy;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IndexingQueueRepository extends JpaRepository<IndexingQueueEntry, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT entry
        FROM IndexingQueueEntry entry
        WHERE entry.id = :workId
          AND entry.status IN :claimableStatuses
          AND (
              entry.dependsOnWorkId IS NULL
              OR NOT EXISTS (
                  SELECT dependency.id
                  FROM IndexingQueueEntry dependency
                  WHERE dependency.id = entry.dependsOnWorkId
                    AND dependency.status IN :blockingStatuses
              )
          )
          AND NOT EXISTS (
              SELECT older.id
              FROM IndexingQueueEntry older
              WHERE older.entityType = entry.entityType
                AND older.entityId = entry.entityId
                AND older.id < entry.id
                AND older.status IN :blockingStatuses
          )
    """)
    Optional<IndexingQueueEntry> findReadySynchronousForUpdate(
        @Param("workId") long workId,
        @Param("claimableStatuses") Collection<IndexingStatus> claimableStatuses,
        @Param("blockingStatuses") Collection<IndexingStatus> blockingStatuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT entry
        FROM IndexingQueueEntry entry
        WHERE entry.status = :status
          AND entry.strategy = :strategy
          AND entry.scheduledFor <= :now
          AND (
              entry.dependsOnWorkId IS NULL
              OR NOT EXISTS (
                  SELECT dependency.id
                  FROM IndexingQueueEntry dependency
                  WHERE dependency.id = entry.dependsOnWorkId
                    AND dependency.status IN :blockingStatuses
              )
          )
          AND NOT EXISTS (
              SELECT older.id
              FROM IndexingQueueEntry older
              WHERE older.entityType = entry.entityType
                AND older.entityId = entry.entityId
                AND older.id < entry.id
                AND older.status IN :blockingStatuses
          )
        ORDER BY entry.requestedAt ASC, entry.id ASC
    """)
    List<IndexingQueueEntry> leaseReady(
        @Param("status") IndexingStatus status,
        @Param("strategy") IndexingStrategy strategy,
        @Param("blockingStatuses") Collection<IndexingStatus> blockingStatuses,
        @Param("now") LocalDateTime now,
        Pageable pageable
    );

    long countByStatus(IndexingStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT entry
        FROM IndexingQueueEntry entry
        WHERE entry.status = :currentStatus
          AND entry.visibilityTimeoutUntil <= :now
          AND entry.startedAt <= :stuckBefore
        ORDER BY entry.visibilityTimeoutUntil ASC, entry.id ASC
    """)
    List<IndexingQueueEntry> findStuckForUpdate(
        @Param("currentStatus") IndexingStatus currentStatus,
        @Param("now") LocalDateTime now,
        @Param("stuckBefore") LocalDateTime stuckBefore,
        Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT entry
        FROM IndexingQueueEntry entry
        WHERE entry.status = :currentStatus
          AND entry.strategy = :strategy
          AND entry.requestedAt <= :requestedBefore
        ORDER BY entry.requestedAt ASC, entry.id ASC
    """)
    List<IndexingQueueEntry> findCommitPendingForUpdate(
        @Param("currentStatus") IndexingStatus currentStatus,
        @Param("strategy") IndexingStrategy strategy,
        @Param("requestedBefore") LocalDateTime requestedBefore,
        Pageable pageable
    );

    @Modifying
    int deleteByStatusInAndCompletedAtBefore(
        Collection<IndexingStatus> statuses,
        LocalDateTime completedBefore
    );

    @Modifying
    int deleteByStatusAndUpdatedAtBefore(
        IndexingStatus status,
        LocalDateTime updatedBefore
    );
}
