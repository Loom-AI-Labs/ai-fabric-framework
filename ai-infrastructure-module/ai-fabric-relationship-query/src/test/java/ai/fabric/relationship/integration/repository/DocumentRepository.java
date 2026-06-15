package ai.fabric.relationship.integration.repository;

import ai.fabric.relationship.integration.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DocumentRepository extends JpaRepository<DocumentEntity, String> {
}
