package ai.fabric.relationship.integration.entity;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
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
    @AIIdentity
    private String id;

    @AISearchable(required = true)
    @Column(nullable = false)
    private String ownerName;

    @AIContext
    @Column(nullable = false)
    private String region;

    @AIContext
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
