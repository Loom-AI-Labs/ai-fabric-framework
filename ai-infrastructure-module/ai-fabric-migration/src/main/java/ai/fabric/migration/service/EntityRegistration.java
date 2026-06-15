package ai.fabric.migration.service;

import org.springframework.data.jpa.repository.JpaRepository;

public record EntityRegistration(
    String entityType,
    Class<?> entityClass,
    JpaRepository<?, ?> repository
) { }
