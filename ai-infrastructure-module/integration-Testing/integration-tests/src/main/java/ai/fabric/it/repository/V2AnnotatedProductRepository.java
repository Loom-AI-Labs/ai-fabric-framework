package ai.fabric.it.repository;

import ai.fabric.it.entity.V2AnnotatedProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface V2AnnotatedProductRepository extends JpaRepository<V2AnnotatedProduct, Long> {
}
