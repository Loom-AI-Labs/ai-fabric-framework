package ai.fabric.relationship.integration.repository;

import ai.fabric.relationship.integration.entity.BrandEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandRepository extends JpaRepository<BrandEntity, String> {
}
