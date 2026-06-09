package com.ai.fabric.realapps.chat.catalog.repo;

import com.ai.fabric.realapps.chat.catalog.domain.Product;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySku(String sku);

    Optional<Product> findBySkuIgnoreCase(String sku);

    boolean existsBySku(String sku);

    boolean existsBySkuIgnoreCase(String sku);
}
