package ai.fabric.repository;

import ai.fabric.entity.RegisteredConnectorAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RegisteredConnectorActionRepository extends JpaRepository<RegisteredConnectorAction, UUID> {
    Optional<RegisteredConnectorAction> findByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCase(String name);
}

