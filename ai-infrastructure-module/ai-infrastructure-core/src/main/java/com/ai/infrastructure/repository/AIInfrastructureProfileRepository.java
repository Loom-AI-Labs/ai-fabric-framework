package com.ai.infrastructure.repository;

import com.ai.infrastructure.entity.AIInfrastructureProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for AI Profile entities
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Repository
public interface AIInfrastructureProfileRepository extends JpaRepository<AIInfrastructureProfile, UUID> {
    
    /**
     * Find AI profile by user ID
     */
    AIInfrastructureProfile findByUserId(UUID userId);
    
    /**
     * Find AI profiles by status
     */
    List<AIInfrastructureProfile> findByStatus(AIInfrastructureProfile.AIProfileStatus status);
    
    /**
     * Find AI profiles by status with pagination
     */
    Page<AIInfrastructureProfile> findByStatus(AIInfrastructureProfile.AIProfileStatus status, Pageable pageable);
    
    /**
     * Find AI profiles by user ID and status
     */
    List<AIInfrastructureProfile> findByUserIdAndStatus(UUID userId, AIInfrastructureProfile.AIProfileStatus status);
    
    /**
     * Find AI profiles by confidence score range
     */
    @Query("SELECT p FROM AIInfrastructureProfile p WHERE p.confidenceScore BETWEEN :minScore AND :maxScore")
    List<AIInfrastructureProfile> findByConfidenceScoreRange(@Param("minScore") Double minScore, @Param("maxScore") Double maxScore);
    
    /**
     * Find AI profiles by confidence score range with pagination
     */
    @Query("SELECT p FROM AIInfrastructureProfile p WHERE p.confidenceScore BETWEEN :minScore AND :maxScore")
    Page<AIInfrastructureProfile> findByConfidenceScoreRange(@Param("minScore") Double minScore, @Param("maxScore") Double maxScore, Pageable pageable);
    
    /**
     * Find AI profiles by user ID and confidence score range
     */
    @Query("SELECT p FROM AIInfrastructureProfile p WHERE p.userId = :userId AND p.confidenceScore BETWEEN :minScore AND :maxScore")
    List<AIInfrastructureProfile> findByUserIdAndConfidenceScoreRange(@Param("userId") UUID userId, @Param("minScore") Double minScore, @Param("maxScore") Double maxScore);
    
    /**
     * Find AI profiles by user ID and confidence score range with pagination
     */
    @Query("SELECT p FROM AIInfrastructureProfile p WHERE p.userId = :userId AND p.confidenceScore BETWEEN :minScore AND :maxScore")
    Page<AIInfrastructureProfile> findByUserIdAndConfidenceScoreRange(@Param("userId") UUID userId, @Param("minScore") Double minScore, @Param("maxScore") Double maxScore, Pageable pageable);
    
    /**
     * Find AI profiles by user ID with pagination
     */
    Page<AIInfrastructureProfile> findByUserId(UUID userId, Pageable pageable);
    
    /**
     * Find AI profiles by user ID
     */
    List<AIInfrastructureProfile> findByUserIdOrderByCreatedAtDesc(UUID userId);
    
    /**
     * Find AI profiles by user ID with pagination
     */
    Page<AIInfrastructureProfile> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
    
    /**
     * Find AI profiles by status with pagination
     */
    Page<AIInfrastructureProfile> findByStatusOrderByCreatedAtDesc(AIInfrastructureProfile.AIProfileStatus status, Pageable pageable);
    
    /**
     * Find AI profiles by user ID and status with pagination
     */
    Page<AIInfrastructureProfile> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, AIInfrastructureProfile.AIProfileStatus status, Pageable pageable);
    
    /**
     * Count AI profiles by user ID
     */
    long countByUserId(UUID userId);
    
    /**
     * Count AI profiles by status
     */
    long countByStatus(AIInfrastructureProfile.AIProfileStatus status);
    
    /**
     * Count AI profiles by user ID and status
     */
    long countByUserIdAndStatus(UUID userId, AIInfrastructureProfile.AIProfileStatus status);
    
    /**
     * Find AI profiles by confidence score between values
     */
    List<AIInfrastructureProfile> findByConfidenceScoreBetween(Double minScore, Double maxScore);
    
    /**
     * Find AI profiles by version
     */
    List<AIInfrastructureProfile> findByVersion(Integer version);
    
    /**
     * Find AI profiles by user ID and version
     */
    List<AIInfrastructureProfile> findByUserIdAndVersion(UUID userId, Integer version);
    
    /**
     * Find AI profiles by created date range
     */
    List<AIInfrastructureProfile> findByCreatedAtBetween(java.time.LocalDateTime startDate, java.time.LocalDateTime endDate);
    
    /**
     * Find AI profiles by user ID and created date range
     */
    List<AIInfrastructureProfile> findByUserIdAndCreatedAtBetween(UUID userId, java.time.LocalDateTime startDate, java.time.LocalDateTime endDate);
    
    /**
     * Find AI profiles by user ID and created date range with pagination
     */
    Page<AIInfrastructureProfile> findByUserIdAndCreatedAtBetween(UUID userId, java.time.LocalDateTime startDate, java.time.LocalDateTime endDate, Pageable pageable);
    
    /**
     * Find latest profile by user ID
     */
    AIInfrastructureProfile findLatestProfileByUserId(UUID userId);
}