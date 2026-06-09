package com.ai.fabric.realapps.privacyfirst.domain;

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
public class SupportMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String customerId;
    private String channel;

    @Column(columnDefinition = "TEXT")
    private String processedSubject;

    @Column(columnDefinition = "TEXT")
    private String processedMessage;

    private boolean piiDetected;
    private String modeApplied;

    private int detectionsCount;

    @Column(columnDefinition = "TEXT")
    private String detectionsSummary;

    @Column(columnDefinition = "TEXT")
    private String subjectEncryptedOriginal;

    private String subjectEncryptionSalt;

    @Column(columnDefinition = "TEXT")
    private String messageEncryptedOriginal;

    private String messageEncryptionSalt;

    private Instant createdAt = Instant.now();
}
