package com.ai.fabric.realapps.privacyfirst.domain;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import ai.fabric.indexing.api.AISearchPreprocessing;
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
@Table(name = "support_message")
@AICapable(entityType = "support-message")
public class SupportMessage {

    @Id
    @AIIdentity
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @AIContext
    private String customerId;
    @AISearchable(priority = 40)
    @AIContext
    private String channel;

    @Column(columnDefinition = "TEXT")
    @AISearchable(
        priority = 80,
        preprocessing = AISearchPreprocessing.SANITIZE
    )
    private String processedSubject;

    @Column(columnDefinition = "TEXT")
    @AISearchable(
        priority = 100,
        required = true,
        maxLength = 10000,
        preprocessing = AISearchPreprocessing.SANITIZE
    )
    private String processedMessage;

    @AIContext
    private boolean piiDetected;
    @AIContext
    private String modeApplied;

    @AIContext
    private int detectionsCount;

    @Column(columnDefinition = "TEXT")
    @AISearchable(priority = 50)
    private String detectionsSummary;

    @Column(columnDefinition = "TEXT")
    private String subjectEncryptedOriginal;

    private String subjectEncryptionSalt;

    @Column(columnDefinition = "TEXT")
    private String messageEncryptedOriginal;

    private String messageEncryptionSalt;

    private Instant createdAt = Instant.now();
}
