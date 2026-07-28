package com.ai.fabric.realapps.agenticresolver.repository;

import com.ai.fabric.realapps.agenticresolver.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUserId(Long userId);

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    List<User> findByUserIdGreaterThanAndUsernameStartingWithAndCreatedAtBefore(
        Long minimumUserId,
        String usernamePrefix,
        LocalDateTime createdBefore
    );

    boolean existsByUserId(Long userId);
}
