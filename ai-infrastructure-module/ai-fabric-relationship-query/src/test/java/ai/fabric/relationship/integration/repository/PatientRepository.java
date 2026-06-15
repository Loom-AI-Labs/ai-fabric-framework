package ai.fabric.relationship.integration.repository;

import ai.fabric.relationship.integration.entity.PatientEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatientRepository extends JpaRepository<PatientEntity, String> {
}
