package com.ai.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO for AI Audit Logs
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIAuditLog {
    
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
     * Log timestamp
     */
    private LocalDateTime timestamp;
    
    /**
     * IP address
     */
    private String ipAddress;
    
    /**
     * User agent
     */
    private String userAgent;
    
    /**
     * Session ID
     */
    private String sessionId;
    
    /**
     * Resource type
     */
    private String resourceType;
    
    /**
     * Resource ID
     */
    private String resourceId;
    
    /**
     * Action performed
     */
    private String action;
    
    /**
     * Result of the action
     */
    private String result;
    
    /**
     * Additional details
     */
    private String details;
    
    /**
     * Log metadata
     */
    private Map<String, Object> metadata;
    
    /**
     * Risk level: LOW, MEDIUM, HIGH, CRITICAL
     */
    private String riskLevel;
    
    /**
     * Security level
     */
    private String securityLevel;
    
    /**
     * Compliance status
     */
    private String complianceStatus;
    
    /**
     * Data classification
     */
    private String dataClassification;
    
    /**
     * Purpose of the operation
     */
    private String purpose;
    
    /**
     * Legal basis
     */
    private String legalBasis;
    
    /**
     * Data controller
     */
    private String dataController;
    
    /**
     * Data processor
     */
    private String dataProcessor;
    
    /**
     * Data protection officer
     */
    private String dpo;
    
    /**
     * Supervisory authority
     */
    private String supervisoryAuthority;
    
    /**
     * Consent given
     */
    private Boolean consentGiven;
    
    /**
     * Consent timestamp
     */
    private LocalDateTime consentTimestamp;
    
    /**
     * Data retention period
     */
    private Integer dataRetentionPeriod;
    
    /**
     * Cross-border transfer
     */
    private Boolean crossBorderTransfer;
    
    /**
     * Safeguards
     */
    private List<String> safeguards;
    
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
     * Data subject rights
     */
    private List<String> dataSubjectRights;
    
    /**
     * Processing activities
     */
    private List<String> processingActivities;
    
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
     * Review date
     */
    private LocalDateTime reviewDate;
    
    /**
     * Next review date
     */
    private LocalDateTime nextReviewDate;
    
    /**
     * Log status
     */
    private String status;
    
    /**
     * Log priority
     */
    private String priority;
    
    /**
     * Log severity
     */
    private String severity;
    
    /**
     * Log category
     */
    private String category;
    
    /**
     * Log tags
     */
    private List<String> tags;
    
    /**
     * Log notes
     */
    private String notes;
    
    /**
     * Log attachments
     */
    private List<String> attachments;
    
    /**
     * Log version
     */
    private String version;
    
    /**
     * Log author
     */
    private String author;
    
    /**
     * Log reviewer
     */
    private String reviewer;
    
    /**
     * Log approver
     */
    private String approver;
    
    /**
     * Log approval date
     */
    private LocalDateTime approvalDate;
    
    /**
     * Log distribution list
     */
    private List<String> distributionList;
    
    /**
     * Log confidentiality level
     */
    private String confidentialityLevel;
    
    /**
     * Log retention period
     */
    private Integer logRetentionPeriod;
    
    /**
     * Log archive status
     */
    private Boolean archived;
    
    /**
     * Log archive date
     */
    private LocalDateTime archivedDate;
    
    /**
     * Log archive reason
     */
    private String archiveReason;
    
    /**
     * Log insights
     */
    private List<String> insights;
    
    /**
     * Has anomalies
     */
    private Boolean hasAnomalies;
    
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
    
    /**
     * Violations found
     */
    private List<String> violations;
    
    /**
     * Regulation types
     */
    private List<String> regulationTypes;
    
    // Manual getters/setters for Lombok compatibility
    public Boolean isHasAnomalies() {
        return hasAnomalies;
    }
    
    public void setHasAnomalies(Boolean hasAnomalies) {
        this.hasAnomalies = hasAnomalies;
    }
    
    public List<String> getViolations() {
        return violations;
    }
    
    public void setViolations(List<String> violations) {
        this.violations = violations;
    }
    
    public List<String> getRegulationTypes() {
        return regulationTypes;
    }
    
    public void setRegulationTypes(List<String> regulationTypes) {
        this.regulationTypes = regulationTypes;
    }
}