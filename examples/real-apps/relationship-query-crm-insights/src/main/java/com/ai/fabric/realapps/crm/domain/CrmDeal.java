package com.ai.fabric.realapps.crm.domain;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "crm_deal")
@AICapable(entityType = "deal")
public class CrmDeal {

    @Id
    @AIIdentity
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @AISearchable(priority = 100, required = true)
    @Column(nullable = false)
    private String name;

    @AIContext
    @Enumerated(EnumType.STRING)
    private DealStage stage = DealStage.OPEN;

    @AIContext
    private BigDecimal amount;

    @AIContext
    private LocalDate closeDate;

    @AISearchable(priority = 60)
    private String ownerName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id")
    private CrmAccount account;
}
