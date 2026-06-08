package com.ai.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO for AI Audit Responses
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIAuditResponse {
    
    /**
     * Log ID
     */
    private String logId;
    
    /**
     * Request ID
     */
    private String requestId;
    
    /**
     * User ID
     */
    private String userId;
    
    /**
     * Operation type
     */
    private String operationType;
    
    /**
     * Risk level
     */
    private String riskLevel;
    
    /**
     * Whether anomalies were detected
     */
    private Boolean hasAnomalies;
    
    /**
     * Audit insights
     */
    private List<String> insights;
    
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
     * Audit log details
     */
    private AIAuditLog auditLog;
    
    /**
     * Security assessment
     */
    private String securityAssessment;
    
    /**
     * Compliance assessment
     */
    private String complianceAssessment;
    
    /**
     * Risk assessment
     */
    private String riskAssessment;
    
    /**
     * Impact assessment
     */
    private String impactAssessment;
    
    /**
     * Recommendations
     */
    private List<String> recommendations;
    
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
     * Audit metrics
     */
    private Map<String, Object> auditMetrics;
    
    /**
     * Security metrics
     */
    private Map<String, Object> securityMetrics;
    
    /**
     * Compliance metrics
     */
    private Map<String, Object> complianceMetrics;
    
    /**
     * Risk metrics
     */
    private Map<String, Object> riskMetrics;
    
    /**
     * Impact metrics
     */
    private Map<String, Object> impactMetrics;
    
    /**
     * Anomaly details
     */
    private String anomalyDetails;
    
    /**
     * Anomaly score
     */
    private Double anomalyScore;
    
    /**
     * Anomaly type
     */
    private String anomalyType;
    
    /**
     * Anomaly severity
     */
    private String anomalySeverity;
    
    /**
     * Anomaly status
     */
    private String anomalyStatus;
    
    /**
     * Anomaly resolution
     */
    private String anomalyResolution;
    
    /**
     * Anomaly resolution date
     */
    private LocalDateTime anomalyResolutionDate;
    
    /**
     * Anomaly resolution notes
     */
    private String anomalyResolutionNotes;
    
    /**
     * Anomaly resolution author
     */
    private String anomalyResolutionAuthor;
    
    /**
     * Anomaly resolution reviewer
     */
    private String anomalyResolutionReviewer;
    
    /**
     * Anomaly resolution approver
     */
    private String anomalyResolutionApprover;
    
    /**
     * Anomaly resolution approval date
     */
    private LocalDateTime anomalyResolutionApprovalDate;
    
    /**
     * Anomaly resolution distribution list
     */
    private List<String> anomalyResolutionDistributionList;
    
    /**
     * Anomaly resolution confidentiality level
     */
    private String anomalyResolutionConfidentialityLevel;
    
    /**
     * Anomaly resolution retention period
     */
    private Integer anomalyResolutionRetentionPeriod;
    
    /**
     * Anomaly resolution archive status
     */
    private Boolean anomalyResolutionArchived;
    
    /**
     * Anomaly resolution archive date
     */
    private LocalDateTime anomalyResolutionArchivedDate;
    
    /**
     * Anomaly resolution archive reason
     */
    private String anomalyResolutionArchiveReason;
    
    // Manual getter for success field
    public Boolean isSuccess() {
        return success;
    }
}