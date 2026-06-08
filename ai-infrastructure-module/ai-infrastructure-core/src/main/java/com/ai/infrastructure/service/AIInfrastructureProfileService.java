package com.ai.infrastructure.service;

import com.ai.infrastructure.dto.AIProfileRequest;
import com.ai.infrastructure.dto.AIProfileResponse;
import com.ai.infrastructure.entity.AIInfrastructureProfile;
import com.ai.infrastructure.repository.AIInfrastructureProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Generic AI Profile Service
 * 
 * Service for AI profile operations.
 * This service is domain-agnostic and can be used across different applications.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@RequiredArgsConstructor
@Slf4j
@Transactional
public class AIInfrastructureProfileService {
    
    private final AIInfrastructureProfileRepository aiProfileRepository;
    private final AICapabilityService aiCapabilityService;
    
    /**
     * Create a new AI profile
     */
    public AIProfileResponse createAIInfrastructureProfile(AIProfileRequest request) {
        log.info("Creating AI profile for user: {}", request.getUserId());
        
        AIInfrastructureProfile aiProfile = AIInfrastructureProfile.builder()
                .userId(UUID.fromString(request.getUserId()))
                .preferences(request.getPreferences())
                .interests(request.getInterests())
                .behaviorPatterns(request.getBehaviorPatterns())
                .cvFileUrl(request.getCvFileUrl())
                .status(AIInfrastructureProfile.AIProfileStatus.valueOf(request.getStatus() != null ? request.getStatus() : "ACTIVE"))
                .confidenceScore(request.getConfidenceScore())
                .version(request.getVersion() != null ? request.getVersion() : 1)
                .build();
        
        AIInfrastructureProfile savedProfile = aiProfileRepository.save(aiProfile);
        
        // Process with AI capabilities
        aiCapabilityService.processEntityForAI(savedProfile, "ai_profile");
        
        return mapToResponse(savedProfile);
    }
    
    /**
     * Get AI profile by ID
     */
    @Transactional(readOnly = true)
    public AIProfileResponse getAIInfrastructureProfileById(UUID id) {
        log.info("Getting AI profile by ID: {}", id);
        
        AIInfrastructureProfile aiProfile = aiProfileRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("AI Profile not found with ID: " + id));
        
        return mapToResponse(aiProfile);
    }
    
    /**
     * Get AI profile by user ID
     */
    @Transactional(readOnly = true)
    public AIProfileResponse getAIInfrastructureProfileByUserId(UUID userId) {
        log.info("Getting AI profile for user: {}", userId);
        
        AIInfrastructureProfile aiProfile = aiProfileRepository.findByUserId(userId);
        if (aiProfile == null) {
            throw new RuntimeException("AI Profile not found for user: " + userId);
        }
        
        return mapToResponse(aiProfile);
    }
    
    /**
     * Get AI profiles by status
     */
    @Transactional(readOnly = true)
    public List<AIProfileResponse> getAIInfrastructureProfilesByStatus(AIInfrastructureProfile.AIProfileStatus status) {
        log.info("Getting AI profiles by status: {}", status);
        
        List<AIInfrastructureProfile> profiles = aiProfileRepository.findByStatus(status);
        return profiles.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }
    
    /**
     * Get AI profiles by status with pagination
     */
    @Transactional(readOnly = true)
    public Page<AIProfileResponse> getAIInfrastructureProfilesByStatus(AIInfrastructureProfile.AIProfileStatus status, Pageable pageable) {
        log.info("Getting AI profiles by status: {} with pagination", status);
        
        Page<AIInfrastructureProfile> profiles = aiProfileRepository.findByStatus(status, pageable);
        return profiles.map(this::mapToResponse);
    }
    
    /**
     * Get AI profiles by user ID and status
     */
    @Transactional(readOnly = true)
    public List<AIProfileResponse> getAIInfrastructureProfilesByUserIdAndStatus(UUID userId, AIInfrastructureProfile.AIProfileStatus status) {
        log.info("Getting AI profiles for user: {} and status: {}", userId, status);
        
        List<AIInfrastructureProfile> profiles = aiProfileRepository.findByUserIdAndStatus(userId, status);
        return profiles.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }
    
    /**
     * Get AI profiles by confidence score range
     */
    @Transactional(readOnly = true)
    public List<AIProfileResponse> getAIInfrastructureProfilesByConfidenceScoreRange(Double minScore, Double maxScore) {
        log.info("Getting AI profiles by confidence score range: {} - {}", minScore, maxScore);
        
        List<AIInfrastructureProfile> profiles = aiProfileRepository.findByConfidenceScoreBetween(minScore, maxScore);
        return profiles.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }
    
    /**
     * Get AI profiles by version
     */
    @Transactional(readOnly = true)
    public List<AIProfileResponse> getAIInfrastructureProfilesByVersion(Integer version) {
        log.info("Getting AI profiles by version: {}", version);
        
        List<AIInfrastructureProfile> profiles = aiProfileRepository.findByVersion(version);
        return profiles.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }
    
    /**
     * Get AI profiles by user ID and version
     */
    @Transactional(readOnly = true)
    public List<AIProfileResponse> getAIInfrastructureProfilesByUserIdAndVersion(UUID userId, Integer version) {
        log.info("Getting AI profiles for user: {} and version: {}", userId, version);
        
        List<AIInfrastructureProfile> profiles = aiProfileRepository.findByUserIdAndVersion(userId, version);
        return profiles.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }
    
    /**
     * Get AI profiles by date range
     */
    @Transactional(readOnly = true)
    public List<AIProfileResponse> getAIInfrastructureProfilesByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        log.info("Getting AI profiles from {} to {}", startDate, endDate);
        
        List<AIInfrastructureProfile> profiles = aiProfileRepository.findByCreatedAtBetween(startDate, endDate);
        return profiles.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }
    
    /**
     * Get AI profiles by user ID and date range
     */
    @Transactional(readOnly = true)
    public List<AIProfileResponse> getAIInfrastructureProfilesByUserIdAndDateRange(UUID userId, LocalDateTime startDate, LocalDateTime endDate) {
        log.info("Getting AI profiles for user: {} from {} to {}", userId, startDate, endDate);
        
        List<AIInfrastructureProfile> profiles = aiProfileRepository.findByUserIdAndCreatedAtBetween(userId, startDate, endDate);
        return profiles.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }
    
    /**
     * Get AI profiles by user ID and date range with pagination
     */
    @Transactional(readOnly = true)
    public Page<AIProfileResponse> getAIInfrastructureProfilesByUserIdAndDateRange(UUID userId, LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        log.info("Getting AI profiles for user: {} from {} to {} with pagination", userId, startDate, endDate);
        
        Page<AIInfrastructureProfile> profiles = aiProfileRepository.findByUserIdAndCreatedAtBetween(userId, startDate, endDate, pageable);
        return profiles.map(this::mapToResponse);
    }
    
    /**
     * Get latest AI profile by user ID
     */
    @Transactional(readOnly = true)
    public AIProfileResponse getLatestAIInfrastructureProfileByUserId(UUID userId) {
        log.info("Getting latest AI profile for user: {}", userId);
        
        AIInfrastructureProfile aiProfile = aiProfileRepository.findLatestProfileByUserId(userId);
        if (aiProfile == null) {
            throw new RuntimeException("No AI Profile found for user: " + userId);
        }
        
        return mapToResponse(aiProfile);
    }
    
    /**
     * Update AI profile
     */
    public AIProfileResponse updateAIInfrastructureProfile(UUID id, AIProfileRequest request) {
        log.info("Updating AI profile: {}", id);
        
        AIInfrastructureProfile aiProfile = aiProfileRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("AI Profile not found with ID: " + id));
        
        aiProfile.setPreferences(request.getPreferences());
        aiProfile.setInterests(request.getInterests());
        aiProfile.setBehaviorPatterns(request.getBehaviorPatterns());
        aiProfile.setCvFileUrl(request.getCvFileUrl());
        if (request.getStatus() != null) {
            aiProfile.setStatus(AIInfrastructureProfile.AIProfileStatus.valueOf(request.getStatus()));
        }
        aiProfile.setConfidenceScore(request.getConfidenceScore());
        if (request.getVersion() != null) {
            aiProfile.setVersion(request.getVersion());
        }
        
        AIInfrastructureProfile savedProfile = aiProfileRepository.save(aiProfile);
        
        // Process with AI capabilities
        aiCapabilityService.processEntityForAI(savedProfile, "ai_profile");
        
        return mapToResponse(savedProfile);
    }
    
    /**
     * Update AI profile by user ID
     */
    public AIProfileResponse updateAIInfrastructureProfileByUserId(UUID userId, AIProfileRequest request) {
        log.info("Updating AI profile for user: {}", userId);
        
        AIInfrastructureProfile aiProfile = aiProfileRepository.findByUserId(userId);
        if (aiProfile == null) {
            throw new RuntimeException("AI Profile not found for user: " + userId);
        }
        
        aiProfile.setPreferences(request.getPreferences());
        aiProfile.setInterests(request.getInterests());
        aiProfile.setBehaviorPatterns(request.getBehaviorPatterns());
        aiProfile.setCvFileUrl(request.getCvFileUrl());
        if (request.getStatus() != null) {
            aiProfile.setStatus(AIInfrastructureProfile.AIProfileStatus.valueOf(request.getStatus()));
        }
        aiProfile.setConfidenceScore(request.getConfidenceScore());
        if (request.getVersion() != null) {
            aiProfile.setVersion(request.getVersion());
        }
        
        AIInfrastructureProfile savedProfile = aiProfileRepository.save(aiProfile);
        
        // Process with AI capabilities
        aiCapabilityService.processEntityForAI(savedProfile, "ai_profile");
        
        return mapToResponse(savedProfile);
    }
    
    /**
     * Delete AI profile
     */
    public void deleteAIInfrastructureProfile(UUID id) {
        log.info("Deleting AI profile: {}", id);
        
        AIInfrastructureProfile aiProfile = aiProfileRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("AI Profile not found with ID: " + id));
        
        aiProfileRepository.delete(aiProfile);
    }
    
    /**
     * Delete AI profile by user ID
     */
    public void deleteAIInfrastructureProfileByUserId(UUID userId) {
        log.info("Deleting AI profile for user: {}", userId);
        
        AIInfrastructureProfile aiProfile = aiProfileRepository.findByUserId(userId);
        if (aiProfile == null) {
            throw new RuntimeException("AI Profile not found for user: " + userId);
        }
        
        aiProfileRepository.delete(aiProfile);
    }
    
    /**
     * Map entity to response DTO
     */
    private AIProfileResponse mapToResponse(AIInfrastructureProfile aiProfile) {
        return AIProfileResponse.builder()
                .id(aiProfile.getId().toString())
                .userId(aiProfile.getUserId().toString())
                .preferences(aiProfile.getPreferences())
                .interests(aiProfile.getInterests())
                .behaviorPatterns(aiProfile.getBehaviorPatterns())
                .cvFileUrl(aiProfile.getCvFileUrl())
                .status(aiProfile.getStatus().toString())
                .confidenceScore(aiProfile.getConfidenceScore())
                .version(aiProfile.getVersion())
                .createdAt(aiProfile.getCreatedAt())
                .updatedAt(aiProfile.getUpdatedAt())
                .build();
    }
}
