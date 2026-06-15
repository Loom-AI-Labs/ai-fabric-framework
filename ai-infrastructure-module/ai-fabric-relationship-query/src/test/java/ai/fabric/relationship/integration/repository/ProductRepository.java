package ai.fabric.relationship.integration.repository;

import ai.fabric.relationship.integration.entity.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<ProductEntity, String> {
}
