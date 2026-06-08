package com.ai.infrastructure.relationship.integration.repository;

import com.ai.infrastructure.relationship.integration.entity.CandidateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateRepository extends JpaRepository<CandidateEntity, String> {
}
