package com.ai.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO for AI Compliance Responses
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIComplianceResponse {
    
    /**
     * Request ID
     */
    private String requestId;
    
    /**
     * Canonical subject identifier.
     */
    private String subjectId;
    
    /**
     * Data privacy compliance status
     */
    private Boolean dataPrivacyCompliant;
    
    /**
     * Regulatory compliance status
     */
    private Boolean regulatoryCompliant;
    
    /**
     * Audit compliance status
     */
    private Boolean auditCompliant;
    
    /**
     * Data retention compliance status
     */
    private Boolean retentionCompliant;
    
    /**
     * Overall compliance status
     */
    private Boolean overallCompliant;
    
    /**
     * Compliance score (0-100)
     */
    private Double complianceScore;
    
    /**
     * Compliance violations
     */
    private List<String> violations;
    
    /**
     * Compliance recommendations
     */
    private List<String> recommendations;
    
    /**
     * Compliance report
     */
    private AIComplianceReport report;
    
    /**
     * Processing time in milliseconds
     */
    private Long processingTimeMs;
    
    /**
     * Response timestamp
     */
    private LocalDateTime timestamp;
    
    /**
     * Whether the response was successful
     */
    private Boolean success;
    
    /**
     * Error message if failed
     */
    private String errorMessage;
    
    /**
     * Compliance status details
     */
    private Map<String, Object> complianceDetails;
    
    /**
     * Risk assessment
     */
    private String riskAssessment;
    
    /**
     * Impact assessment
     */
    private String impactAssessment;
    
    /**
     * Mitigation measures
     */
    private List<String> mitigationMeasures;
    
    /**
     * Monitoring measures
     */
    private List<String> monitoringMeasures;
    
    /**
     * Review recommendations
     */
    private List<String> reviewRecommendations;
    
    /**
     * Next review date
     */
    private LocalDateTime nextReviewDate;
    
    /**
     * Compliance metrics
     */
    private Map<String, Object> complianceMetrics;
    
    /**
     * Regulatory requirements
     */
    private List<String> regulatoryRequirements;
    
    /**
     * Data protection requirements
     */
    private List<String> dataProtectionRequirements;
    
    /**
     * Audit requirements
     */
    private List<String> auditRequirements;
    
    /**
     * Retention requirements
     */
    private List<String> retentionRequirements;
}
