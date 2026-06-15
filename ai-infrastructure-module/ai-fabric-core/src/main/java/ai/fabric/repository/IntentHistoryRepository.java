package ai.fabric.repository;

import ai.fabric.entity.IntentHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface IntentHistoryRepository extends JpaRepository<IntentHistory, String> {

    List<IntentHistory> findByUserIdOrderByCreatedAtDesc(String userId);

    List<IntentHistory> findByUserIdAndCreatedAtBetween(String userId, LocalDateTime start, LocalDateTime end);

    long deleteByExpiresAtBefore(LocalDateTime cutoff);
}
