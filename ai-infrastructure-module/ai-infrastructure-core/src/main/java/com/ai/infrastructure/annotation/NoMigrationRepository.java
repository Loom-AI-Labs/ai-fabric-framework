package com.ai.infrastructure.annotation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Marker repository used as a default placeholder for migration.
 */
@NoRepositoryBean
public interface NoMigrationRepository extends JpaRepository<Object, Object> {
}
