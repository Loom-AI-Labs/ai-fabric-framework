package com.ai.fabric.realapps.mcpserver.repository;

import com.ai.fabric.realapps.mcpserver.domain.SandboxServiceState;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SandboxServiceStateRepository
    extends JpaRepository<SandboxServiceState, String> {

    List<SandboxServiceState> findByLastTouchedAtBefore(Instant cutoff);
}
