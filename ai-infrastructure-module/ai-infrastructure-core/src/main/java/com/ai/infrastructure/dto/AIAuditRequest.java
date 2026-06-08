package com.ai.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO for AI Audit Requests
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIAuditRequest {
    
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
     * Request timestamp
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
     * Action to be performed
     */
    private String action;
    
    /**
     * Request context
     */
    private String context;
    
    /**
     * Request metadata
     */
    private Map<String, Object> metadata;
    
    /**
     * Security level
     */
    private String securityLevel;
    
    /**
     * Risk level
     */
    private String riskLevel;
    
    /**
     * Compliance requirements
     */
    private List<String> complianceRequirements;
    
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
     * Request priority
     */
    private String priority;
    
    /**
     * Request severity
     */
    private String severity;
    
    /**
     * Request category
     */
    private String category;
    
    /**
     * Request tags
     */
    private List<String> tags;
    
    /**
     * Request notes
     */
    private String notes;
    
    /**
     * Request attachments
     */
    private List<String> attachments;
    
    /**
     * Request version
     */
    private String version;
    
    /**
     * Request author
     */
    private String author;
    
    /**
     * Request reviewer
     */
    private String reviewer;
    
    /**
     * Request approver
     */
    private String approver;
    
    /**
     * Request approval date
     */
    private LocalDateTime approvalDate;
    
    /**
     * Request distribution list
     */
    private List<String> distributionList;
    
    /**
     * Request confidentiality level
     */
    private String confidentialityLevel;
    
    /**
     * Request retention period
     */
    private Integer requestRetentionPeriod;
    
    /**
     * Request archive status
     */
    private Boolean archived;
    
    /**
     * Request archive date
     */
    private LocalDateTime archivedDate;
    
    /**
     * Request archive reason
     */
    private String archiveReason;
    
    /**
     * Result of the audit
     */
    private String result;
    
    /**
     * Additional details
     */
    private String details;
    
    /**
     * Request description
     */
    private String description;
    
    // Manual getters/setters for Lombok compatibility
    public String getResult() {
        return result;
    }
    
    public void setResult(String result) {
        this.result = result;
    }
    
    public String getDetails() {
        return details;
    }
    
    public void setDetails(String details) {
        this.details = details;
    }
}