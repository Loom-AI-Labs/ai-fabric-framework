package com.ai.fabric.realapps.livesync.repository;

import com.ai.fabric.realapps.livesync.domain.SyncProduct;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncProductRepository extends JpaRepository<SyncProduct, String> {

    List<SyncProduct> findAllByWorkspaceIdOrderByRecordKey(String workspaceId);

    Optional<SyncProduct> findByWorkspaceIdAndRecordKey(String workspaceId, String recordKey);
}
