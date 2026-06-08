package com.ai.infrastructure.relationship.integration.repository;

import com.ai.infrastructure.relationship.integration.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, String> {
}
