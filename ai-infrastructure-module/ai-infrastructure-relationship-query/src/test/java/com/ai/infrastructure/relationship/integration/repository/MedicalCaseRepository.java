package com.ai.infrastructure.relationship.integration.repository;

import com.ai.infrastructure.relationship.integration.entity.MedicalCaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MedicalCaseRepository extends JpaRepository<MedicalCaseEntity, String> {
}
