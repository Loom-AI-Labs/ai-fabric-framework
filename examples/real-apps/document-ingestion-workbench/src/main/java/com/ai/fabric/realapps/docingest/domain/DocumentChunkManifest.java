package com.ai.fabric.realapps.docingest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "document_chunk_manifest")
public class DocumentChunkManifest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sourceId;

    private int sourceVersion;

    @Column(nullable = false)
    private String entityType;

    @Column(nullable = false)
    private String entityId;

    private int chunkIndex;
    private int chunkCount;

    @Column(length = 64)
    private String contentFingerprint;

    @Column(columnDefinition = "TEXT")
    private String metadataJson;

    private Instant createdAt = Instant.now();
}
