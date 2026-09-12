package com.ai.fabric.realapps.incident.service;

import com.ai.fabric.realapps.incident.domain.IncidentDemoSession;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentDemoSessionRepository
    extends JpaRepository<IncidentDemoSession, String> {

    long countByExpiresAtAfter(Instant now);

    List<IncidentDemoSession> findByExpiresAtLessThanEqual(Instant now);
}
