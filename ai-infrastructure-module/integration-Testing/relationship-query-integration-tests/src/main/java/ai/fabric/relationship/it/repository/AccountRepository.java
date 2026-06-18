package ai.fabric.relationship.it.repository;

import ai.fabric.relationship.it.entity.AccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<AccountEntity, String> {
}
