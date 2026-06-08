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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "patients")
@AICapable(entityType = "patient")
public class PatientEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String fullName;

    private LocalDate dateOfBirth;

    @OneToMany(mappedBy = "patient")
    private List<MedicalCaseEntity> medicalCases = new ArrayList<>();

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
