package ai.fabric.repository;

import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.IndexingStatus;
import ai.fabric.indexing.api.IndexingStrategy;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface IndexingQueueRepository extends JpaRepository<IndexingQueueEntry, String> {

    List<IndexingQueueEntry> findByStatusAndStrategyAndScheduledForLessThanEqualOrderByPriorityWeightAscRequestedAtAsc(
        IndexingStatus status,
        IndexingStrategy strategy,
        LocalDateTime scheduledFor,
        Pageable pageable
    );

    long countByStatus(IndexingStatus status);

    @Modifying
    @Query("""
        UPDATE IndexingQueueEntry e
        SET e.status = :newStatus,
            e.processingNode = NULL,
            e.visibilityTimeoutUntil = NULL,
            e.retryCount = e.retryCount + 1,
            e.updatedAt = :now
        WHERE e.status = :currentStatus
          AND e.visibilityTimeoutUntil <= :now
    """)
    int resetExpiredVisibilityTimeouts(
        @Param("currentStatus") IndexingStatus currentStatus,
        @Param("newStatus") IndexingStatus newStatus,
        @Param("now") LocalDateTime now
    );

    @Modifying
    int deleteByStatusAndCompletedAtBefore(IndexingStatus status, LocalDateTime completedBefore);

    @Modifying
    int deleteByStatusAndUpdatedAtBefore(IndexingStatus status, LocalDateTime updatedBefore);
}
