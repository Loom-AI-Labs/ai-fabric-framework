package ai.fabric.relationship.it.repository;

import ai.fabric.relationship.it.entity.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<ProductEntity, String> {
}
