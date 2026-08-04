package com.ai.fabric.realapps.mcpops.repository;

import com.ai.fabric.realapps.mcpops.domain.McpInvocationAudit;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpInvocationAuditRepository
    extends JpaRepository<McpInvocationAudit, String> {

    List<McpInvocationAudit> findTop50BySessionIdOrderByStartedAtDesc(
        String sessionId
    );

    long countBySessionIdAndActionIdAndSuccessTrue(
        String sessionId,
        String actionId
    );

    long deleteBySessionId(String sessionId);

    long deleteByStartedAtBefore(Instant cutoff);
}
