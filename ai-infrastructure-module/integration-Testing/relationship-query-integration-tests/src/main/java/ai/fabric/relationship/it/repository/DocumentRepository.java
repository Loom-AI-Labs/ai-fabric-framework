package ai.fabric.relationship.it.repository;

import ai.fabric.relationship.it.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<DocumentEntity, String> {
}
