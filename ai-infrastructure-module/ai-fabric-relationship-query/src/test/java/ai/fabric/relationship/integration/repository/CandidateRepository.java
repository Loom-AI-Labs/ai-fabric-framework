package ai.fabric.relationship.integration.repository;

import ai.fabric.relationship.integration.entity.CandidateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateRepository extends JpaRepository<CandidateEntity, String> {
}
