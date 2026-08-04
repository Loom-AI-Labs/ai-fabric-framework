package com.ai.fabric.realapps.mcpops.repository;

import com.ai.fabric.realapps.mcpops.domain.McpDemoSession;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpDemoSessionRepository
    extends JpaRepository<McpDemoSession, String> {

    long deleteByExpiresAtBefore(Instant cutoff);
}
