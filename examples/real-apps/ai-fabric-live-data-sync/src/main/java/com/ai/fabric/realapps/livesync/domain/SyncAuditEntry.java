package com.ai.fabric.realapps.livesync.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
    name = "sync_audit_entry",
    indexes = @Index(
        name = "idx_sync_audit_workspace_occurred",
        columnList = "workspace_id,occurred_at"
    )
)
public class SyncAuditEntry {

    @Id
    @Column(nullable = false, length = 36)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 80)
    private String workspaceId;

    @Column(nullable = false, length = 48)
    private String operation;

    @Column(nullable = false, length = 24)
    private String kind;

    @Column(name = "entity_type", nullable = false, length = 80)
    private String entityType;

    @Column(name = "record_key", nullable = false, length = 80)
    private String recordKey;

    @Column(nullable = false, length = 200)
    private String title;

    private Integer revision;

    @Column(name = "source_present", nullable = false)
    private boolean sourcePresent;

    @Column(name = "vector_present", nullable = false)
    private boolean vectorPresent;

    @Column(name = "in_sync", nullable = false)
    private boolean inSync;

    @Column(name = "elapsed_ms", nullable = false)
    private long elapsedMs;

    @Column(name = "indexing_work_id", length = 80)
    private String indexingWorkId;

    @Column(name = "indexing_dispatch_status", length = 40)
    private String indexingDispatchStatus;

    @Column(nullable = false, length = 1600)
    private String message;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
