package com.ai.fabric.realapps.crm.domain;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "crm_contact")
@AICapable(entityType = "contact")
public class CrmContact {

    @Id
    @AIIdentity
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @AISearchable(priority = 100, required = true)
    @Column(nullable = false)
    private String firstName;

    @AISearchable(priority = 100, required = true)
    @Column(nullable = false)
    private String lastName;

    @AIContext
    @Column(nullable = false)
    private String email;

    @AISearchable(priority = 60)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id")
    private CrmAccount account;
}
