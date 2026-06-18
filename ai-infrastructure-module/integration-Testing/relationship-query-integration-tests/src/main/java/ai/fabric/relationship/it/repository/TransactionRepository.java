package ai.fabric.relationship.it.repository;

import ai.fabric.relationship.it.entity.TransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<TransactionEntity, String> {
}
