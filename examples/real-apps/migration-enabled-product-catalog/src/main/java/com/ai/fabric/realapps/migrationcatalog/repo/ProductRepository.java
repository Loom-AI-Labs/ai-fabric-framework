package com.ai.fabric.realapps.migrationcatalog.repo;

import com.ai.fabric.realapps.migrationcatalog.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {}
