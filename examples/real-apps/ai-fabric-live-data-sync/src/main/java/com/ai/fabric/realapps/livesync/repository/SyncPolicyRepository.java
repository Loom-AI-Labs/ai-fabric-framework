package com.ai.fabric.realapps.livesync.repository;

import com.ai.fabric.realapps.livesync.domain.SyncPolicy;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncPolicyRepository extends JpaRepository<SyncPolicy, String> {

    List<SyncPolicy> findAllByWorkspaceIdOrderByRecordKey(String workspaceId);

    Optional<SyncPolicy> findByWorkspaceIdAndRecordKey(String workspaceId, String recordKey);
}
