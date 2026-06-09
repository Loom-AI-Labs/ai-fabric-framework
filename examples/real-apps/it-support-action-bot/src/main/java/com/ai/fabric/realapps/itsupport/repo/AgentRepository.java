package com.ai.fabric.realapps.itsupport.repo;

import com.ai.fabric.realapps.itsupport.domain.Agent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AgentRepository extends JpaRepository<Agent, UUID> {
    Optional<Agent> findByUsername(String username);
    boolean existsByUsername(String username);
}
