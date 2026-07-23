package com.ai.fabric.realapps.livesync.repository;

import com.ai.fabric.realapps.livesync.domain.SyncGuide;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncGuideRepository extends JpaRepository<SyncGuide, String> {

    List<SyncGuide> findAllByWorkspaceIdOrderByRecordKey(String workspaceId);

    Optional<SyncGuide> findByWorkspaceIdAndRecordKey(String workspaceId, String recordKey);
}
