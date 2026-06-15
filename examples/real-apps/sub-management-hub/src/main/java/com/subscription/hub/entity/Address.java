package com.subscription.hub.entity;

import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AISearchable;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "addresses")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @AISearchable(weight = 1.0)
    @Column(nullable = false)
    private String streetAddress;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false)
    private String state;

    @Column(nullable = false)
    private String postalCode;

    @Column(nullable = false)
    private String country;

    @AIContext(contextKey = "addressType")
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private AddressType type;  // BILLING, SHIPPING

    @AIContext(contextKey = "isValidated")
    @Builder.Default
    private Boolean isValidated = false;

    @AIContext(contextKey = "validationScore", dataType = "decimal")
    private Double validationScore;  // 0.0-1.0 confidence in address validity

    public enum AddressType {
        BILLING, SHIPPING
    }
}
