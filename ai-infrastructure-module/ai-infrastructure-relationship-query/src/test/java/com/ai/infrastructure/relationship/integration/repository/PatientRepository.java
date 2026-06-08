package com.ai.infrastructure.relationship.integration.repository;

import com.ai.infrastructure.relationship.integration.entity.PatientEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatientRepository extends JpaRepository<PatientEntity, String> {
}
