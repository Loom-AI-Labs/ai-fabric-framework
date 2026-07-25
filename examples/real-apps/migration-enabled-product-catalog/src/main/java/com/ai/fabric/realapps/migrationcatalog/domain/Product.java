package com.ai.fabric.realapps.migrationcatalog.domain;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "product")
@AICapable(entityType = "product")
public class Product {

    @Id
    @AIIdentity
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @AIContext
    @Column(nullable = false, unique = true)
    private String sku;

    @AISearchable(priority = 100, required = true)
    @Column(nullable = false)
    private String name;

    @AISearchable(priority = 90)
    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    @AISearchable(priority = 60)
    @AIContext
    private String category;

    @AIContext
    @Column(precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
