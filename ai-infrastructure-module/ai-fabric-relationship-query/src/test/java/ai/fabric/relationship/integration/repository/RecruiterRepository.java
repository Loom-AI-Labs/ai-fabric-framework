package ai.fabric.relationship.integration.repository;

import ai.fabric.relationship.integration.entity.RecruiterEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecruiterRepository extends JpaRepository<RecruiterEntity, String> {
}
