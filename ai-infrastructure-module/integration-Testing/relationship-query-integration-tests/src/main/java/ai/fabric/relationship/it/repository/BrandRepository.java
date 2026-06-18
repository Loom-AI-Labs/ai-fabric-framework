package ai.fabric.relationship.it.repository;

import ai.fabric.relationship.it.entity.BrandEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandRepository extends JpaRepository<BrandEntity, String> {
}
