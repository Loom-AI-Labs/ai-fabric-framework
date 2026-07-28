package com.ai.fabric.realapps.agenticresolver.repository;

import com.ai.fabric.realapps.agenticresolver.entity.AgenticResolverDemoSession;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgenticResolverDemoSessionRepository
    extends JpaRepository<AgenticResolverDemoSession, String> {

    long countByLastAccessedAtGreaterThanEqual(Instant cutoff);

    List<AgenticResolverDemoSession> findByLastAccessedAtGreaterThanEqual(
        Instant cutoff
    );

    List<AgenticResolverDemoSession> findByLastAccessedAtBefore(Instant cutoff);
}
