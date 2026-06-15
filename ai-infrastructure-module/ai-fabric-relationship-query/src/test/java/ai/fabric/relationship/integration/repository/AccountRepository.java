package ai.fabric.relationship.integration.repository;

import ai.fabric.relationship.integration.entity.AccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<AccountEntity, String> {
}
