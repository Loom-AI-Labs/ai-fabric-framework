package com.ai.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO for AI Security Requests
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AISecurityRequest {
    
    /**
     * Request ID
     */
    private String requestId;
    
    /**
     * Canonical subject context for the evaluated caller.
     */
    private AIAccessSubjectContext authContext;
    
    /**
     * Content to analyze
     */
    private String content;
    
    /**
     * Request context
     */
    private String context;
    
    /**
     * IP address
     */
    private String ipAddress;
    
    /**
     * User agent
     */
    private String userAgent;
    
    /**
     * Request timestamp
     */
    private LocalDateTime timestamp;
    
    /**
     * Operation type
     */
    private String operationType;
    
    /**
     * Required permissions
     */
    private List<String> requiredPermissions;
    
    /**
     * Required roles
     */
    private List<String> requiredRoles;
    
    /**
     * User attributes
     */
    private Map<String, Object> userAttributes;
    
    /**
     * Request metadata
     */
    private Map<String, Object> metadata;
    
    /**
     * Security level
     */
    private String securityLevel;
    
    /**
     * Risk tolerance
     */
    private String riskTolerance;
    
    /**
     * Compliance requirements
     */
    private List<String> complianceRequirements;
    
    /**
     * Data classification
     */
    private String dataClassification;
    
    /**
     * Purpose of the request
     */
    private String purpose;
    
    /**
     * Data retention period
     */
    private Integer dataRetentionPeriod;
    
    /**
     * Consent given
     */
    private Boolean consentGiven;
    
    /**
     * Consent timestamp
     */
    private LocalDateTime consentTimestamp;
    
    /**
     * Consent type
     */
    private String consentType;
    
    /**
     * Data subject rights
     */
    private List<String> dataSubjectRights;
    
    /**
     * Cross-border transfer
     */
    private Boolean crossBorderTransfer;
    
    /**
     * Data processor
     */
    private String dataProcessor;
    
    /**
     * Data controller
     */
    private String dataController;
    
    /**
     * Legal basis
     */
    private String legalBasis;
    
    /**
     * Processing activities
     */
    private List<String> processingActivities;
    
    /**
     * Data categories
     */
    private List<String> dataCategories;
    
    /**
     * Special categories
     */
    private List<String> specialCategories;
    
    /**
     * Recipients
     */
    private List<String> recipients;
    
    /**
     * Third countries
     */
    private List<String> thirdCountries;
    
    /**
     * Safeguards
     */
    private List<String> safeguards;
    
    /**
     * Data protection impact assessment
     */
    private Boolean dpiaRequired;
    
    /**
     * Data protection officer
     */
    private String dpo;
    
    /**
     * Supervisory authority
     */
    private String supervisoryAuthority;
    
    /**
     * Data breach notification
     */
    private Boolean dataBreachNotification;
    
    /**
     * Data portability
     */
    private Boolean dataPortability;
    
    /**
     * Right to erasure
     */
    private Boolean rightToErasure;
    
    /**
     * Right to rectification
     */
    private Boolean rightToRectification;
    
    /**
     * Right to restriction
     */
    private Boolean rightToRestriction;
    
    /**
     * Right to object
     */
    private Boolean rightToObject;
    
    /**
     * Automated decision making
     */
    private Boolean automatedDecisionMaking;
    
    /**
     * Profiling
     */
    private Boolean profiling;
    
    /**
     * Data minimization
     */
    private Boolean dataMinimization;
    
    /**
     * Purpose limitation
     */
    private Boolean purposeLimitation;
    
    /**
     * Storage limitation
     */
    private Boolean storageLimitation;
    
    /**
     * Accuracy
     */
    private Boolean accuracy;
    
    /**
     * Integrity and confidentiality
     */
    private Boolean integrityAndConfidentiality;
    
    /**
     * Accountability
     */
    private Boolean accountability;
    
    /**
     * Transparency
     */
    private Boolean transparency;
    
    /**
     * Fairness
     */
    private Boolean fairness;
    
    /**
     * Lawfulness
     */
    private Boolean lawfulness;
}
