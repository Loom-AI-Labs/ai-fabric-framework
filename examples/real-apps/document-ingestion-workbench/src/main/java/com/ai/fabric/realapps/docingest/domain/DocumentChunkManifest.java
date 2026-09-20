package com.ai.fabric.realapps.docingest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
    name = "document_chunk_manifest",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_document_chunk_source_version_entity",
        columnNames = {"source_id", "source_version", "entity_id"}
    ),
    indexes = {
        @Index(
            name = "idx_document_chunk_source_state",
            columnList = "source_id,lifecycle_state"
        ),
        @Index(
            name = "idx_document_chunk_manifest",
            columnList = "manifest_id"
        )
    }
)
public class DocumentChunkManifest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manifest_id", nullable = false, length = 64)
    private String manifestId;

    @Column(name = "plan_id", nullable = false, length = 64)
    private String planId;

    @Column(name = "source_id", nullable = false, length = 256)
    private String sourceId;

    @Column(name = "source_version", nullable = false)
    private long sourceVersion;

    @Column(name = "source_name", nullable = false, length = 512)
    private String sourceName;

    @Column(name = "entity_type", nullable = false, length = 128)
    private String entityType;

    @Column(name = "tenant_id", length = 256)
    private String tenantId;

    @Column(length = 128)
    private String visibility;

    @Column(name = "source_document_id", nullable = false, length = 256)
    private String sourceDocumentId;

    @Column(name = "chunk_id", nullable = false, length = 64)
    private String chunkId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "entity_id", nullable = false, length = 512)
    private String entityId;

    @Column(name = "content_fingerprint", nullable = false, length = 64)
    private String contentFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_state", nullable = false, length = 32)
    private LifecycleState state = LifecycleState.DRAFT;

    @Column(name = "index_work_id", length = 64)
    private String indexWorkId;

    @Column(name = "delete_work_id", length = 64)
    private String deleteWorkId;

    @Column(name = "failure_code", length = 128)
    private String failureCode;

    @Column(name = "failure_message", length = 256)
    private String failureMessage;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @Version
    private long rowVersion;

    public enum LifecycleState {
        DRAFT,
        INDEXING,
        ACTIVE,
        SUPERSEDED,
        DELETING,
        DELETED,
        FAILED
    }
}
