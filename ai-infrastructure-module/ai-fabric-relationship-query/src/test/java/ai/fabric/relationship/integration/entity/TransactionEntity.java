package ai.fabric.relationship.integration.entity;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import ai.fabric.indexing.api.AIContextDestination;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "transactions")
@AICapable(entityType = "transaction")
public class TransactionEntity {

    @Id
    @AIIdentity
    private String id;

    @AISearchable(priority = 100, required = true)
    @Column(nullable = false)
    private String title;

    @AIContext
    @Column(nullable = false)
    private BigDecimal amount;

    @AIContext
    @Column(nullable = false)
    private String currency;

    @AIContext
    @Column(nullable = false)
    private String channel;

    @AIContext
    @Column(nullable = false)
    private LocalDateTime occurredAt;

    @AIContext
    @Column(nullable = false)
    private String status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_account_id")
    private AccountEntity sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_account_id")
    private AccountEntity destinationAccount;

    @AIContext(
        key = "sourceAccount",
        destinations = AIContextDestination.API_RESPONSE
    )
    public String getSourceAccountId() {
        return sourceAccount == null ? null : sourceAccount.getId();
    }

    @AIContext(
        key = "destinationAccount",
        destinations = AIContextDestination.API_RESPONSE
    )
    public String getDestinationAccountId() {
        return destinationAccount == null ? null : destinationAccount.getId();
    }

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
