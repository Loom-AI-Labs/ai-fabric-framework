package com.ai.fabric.realapps.livesync.repository;

import com.ai.fabric.realapps.livesync.domain.SyncAuditEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncAuditEntryRepository
    extends JpaRepository<SyncAuditEntry, String> {

    List<SyncAuditEntry> findTop30ByWorkspaceIdOrderByOccurredAtDesc(
        String workspaceId
    );

    long deleteByWorkspaceId(String workspaceId);
}
