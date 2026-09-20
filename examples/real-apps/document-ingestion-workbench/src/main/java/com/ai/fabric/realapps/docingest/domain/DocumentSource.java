package com.ai.fabric.realapps.docingest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "document_source")
public class DocumentSource {

    @Id
    private String id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String originalFilename;

    private String contentType;
    private String extension;
    private String tenantId;
    private String visibility;

    @Column(nullable = false)
    private String storagePath;

    @Column(nullable = false, length = 64)
    private String contentHash;

    private long sourceVersion = 1;

    private Long activeVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(length = 128)
    private String lastFailureCode;

    @Column(length = 256)
    private String lastFailureMessage;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @Version
    private long rowVersion;

    public enum Status {
        PENDING,
        INDEXING,
        REPLACING,
        INDEXED,
        DELETING,
        DELETED,
        FAILED
    }
}
