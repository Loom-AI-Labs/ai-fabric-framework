package com.ai.fabric.realapps.livesync.repository;

import com.ai.fabric.realapps.livesync.domain.DemoWorkspace;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoWorkspaceRepository extends JpaRepository<DemoWorkspace, String> {

    List<DemoWorkspace> findAllByLastTouchedAtBefore(Instant cutoff);
}
