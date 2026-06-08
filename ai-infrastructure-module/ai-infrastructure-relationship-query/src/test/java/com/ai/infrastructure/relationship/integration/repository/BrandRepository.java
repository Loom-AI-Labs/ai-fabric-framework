package com.ai.infrastructure.relationship.integration.repository;

import com.ai.infrastructure.relationship.integration.entity.BrandEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandRepository extends JpaRepository<BrandEntity, String> {
}
