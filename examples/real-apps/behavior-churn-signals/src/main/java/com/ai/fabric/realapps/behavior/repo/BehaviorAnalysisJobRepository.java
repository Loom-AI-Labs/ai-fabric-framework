package com.ai.fabric.realapps.behavior.repo;

import com.ai.fabric.realapps.behavior.domain.BehaviorAnalysisJob;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BehaviorAnalysisJobRepository extends JpaRepository<BehaviorAnalysisJob, UUID> {

    Optional<BehaviorAnalysisJob> findByInvocationId(String invocationId);

    Optional<BehaviorAnalysisJob> findBySessionIdAndIdempotencyKey(
        String sessionId,
        String idempotencyKey
    );

    List<BehaviorAnalysisJob> findBySessionIdOrderBySubmittedAtDesc(String sessionId);

    void deleteBySessionId(String sessionId);
}
