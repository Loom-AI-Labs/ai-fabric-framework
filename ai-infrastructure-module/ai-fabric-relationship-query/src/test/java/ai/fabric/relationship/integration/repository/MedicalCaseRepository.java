package ai.fabric.relationship.integration.repository;

import ai.fabric.relationship.integration.entity.MedicalCaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MedicalCaseRepository extends JpaRepository<MedicalCaseEntity, String> {
}
