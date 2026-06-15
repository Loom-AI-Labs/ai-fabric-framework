package ai.fabric.relationship.integration.repository;

import ai.fabric.relationship.integration.entity.TransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<TransactionEntity, String> {
}
