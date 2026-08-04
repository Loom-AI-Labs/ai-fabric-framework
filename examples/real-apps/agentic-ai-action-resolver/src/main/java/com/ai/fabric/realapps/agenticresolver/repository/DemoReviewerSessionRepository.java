package com.ai.fabric.realapps.agenticresolver.repository;

import com.ai.fabric.realapps.agenticresolver.entity.DemoReviewerSession;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoReviewerSessionRepository
    extends JpaRepository<DemoReviewerSession, String> {

    long deleteByExpiresAtBefore(Instant cutoff);

    long deleteByDemoSessionId(String demoSessionId);
}
