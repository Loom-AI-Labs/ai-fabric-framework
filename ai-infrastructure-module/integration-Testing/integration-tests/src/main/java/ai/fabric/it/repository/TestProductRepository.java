package ai.fabric.it.repository;

import ai.fabric.it.entity.TestProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Test Product Entity
 *
 * Provides data access methods for testing AI infrastructure integration.
 *
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Repository
public interface TestProductRepository extends JpaRepository<TestProduct, Long> {

    /**
     * Find products by category
     */
    List<TestProduct> findByCategory(String category);

    /**
     * Find products by brand
     */
    List<TestProduct> findByBrand(String brand);

    /**
     * Find active products
     */
    List<TestProduct> findByActiveTrue();

    /**
     * Find products in stock
     */
    @Query("SELECT p FROM TestProduct p WHERE p.stockQuantity > 0")
    List<TestProduct> findInStockProducts();

    /**
     * Find products by price range
     */
    @Query("SELECT p FROM TestProduct p WHERE p.price BETWEEN :minPrice AND :maxPrice")
    List<TestProduct> findByPriceRange(@Param("minPrice") Double minPrice, @Param("maxPrice") Double maxPrice);

    /**
     * Find products by name containing text
     */
    List<TestProduct> findByNameContainingIgnoreCase(String name);

    /**
     * Find products by description containing text
     */
    List<TestProduct> findByDescriptionContainingIgnoreCase(String description);

    /**
     * Find product by SKU
     */
    Optional<TestProduct> findBySku(String sku);

    /**
     * Count products by category
     */
    long countByCategory(String category);

    /**
     * Find products with AI analysis
     */
    @Query("SELECT p FROM TestProduct p WHERE p.name IS NOT NULL AND p.description IS NOT NULL")
    List<TestProduct> findProductsWithContent();
}