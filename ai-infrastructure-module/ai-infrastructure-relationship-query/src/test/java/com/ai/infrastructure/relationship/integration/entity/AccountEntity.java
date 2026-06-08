package com.ai.infrastructure.relationship.integration.entity;

import com.ai.infrastructure.annotation.AICapable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "accounts")
@AICapable(entityType = "account")
public class AccountEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String ownerName;

    @Column(nullable = false)
    private String region;

    @Column(nullable = false)
    private BigDecimal riskScore;

    @OneToMany(mappedBy = "sourceAccount")
    private List<TransactionEntity> outgoingTransactions = new ArrayList<>();

    @OneToMany(mappedBy = "destinationAccount")
    private List<TransactionEntity> incomingTransactions = new ArrayList<>();

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
