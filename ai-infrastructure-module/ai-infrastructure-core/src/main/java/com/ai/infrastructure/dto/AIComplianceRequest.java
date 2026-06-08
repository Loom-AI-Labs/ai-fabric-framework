package com.ai.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO for AI Compliance Requests
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIComplianceRequest {
    
    /**
     * Request ID
     */
    private String requestId;
    
    /**
     * Canonical subject context for the evaluated caller.
     */
    private AIAccessSubjectContext authContext;
    
    /**
     * Content to check
     */
    private String content;
    
    /**
     * Request context
     */
    private String context;
    
    /**
     * Regulation types to check
     */
    private List<String> regulationTypes;
    
    /**
     * Data classification
     */
    private String dataClassification;
    
    /**
     * Purpose of processing
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
     * Request metadata
     */
    private Map<String, Object> metadata;
    
    /**
     * Request timestamp
     */
    private LocalDateTime timestamp;
    
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
     * Compliance framework
     */
    private String framework;
    
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
     * Whether audit logging is enabled
     */
    private Boolean auditLoggingEnabled;
    
    /**
     * Whether internal controls are enabled
     */
    private Boolean internalControlsEnabled;
    
    /**
     * Whether data encryption is enabled
     */
    private Boolean dataEncryptionEnabled;
    
    /**
     * Whether access control is enabled
     */
    private Boolean accessControlEnabled;
    
    /**
     * Whether network security is enabled
     */
    private Boolean networkSecurityEnabled;
    
    /**
     * Operation type
     */
    private String operationType;
    
    /**
     * Data age in days
     */
    private Integer dataAge;
    
    /**
     * IP address
     */
    private String ipAddress;
    
    /**
     * User agent
     */
    private String userAgent;
    
    // Manual getters/setters for Lombok compatibility
    public Boolean isAuditLoggingEnabled() {
        return auditLoggingEnabled;
    }
    
    public void setAuditLoggingEnabled(Boolean auditLoggingEnabled) {
        this.auditLoggingEnabled = auditLoggingEnabled;
    }
    
    public Boolean isConsentGiven() {
        return consentGiven;
    }
    
    public void setConsentGiven(Boolean consentGiven) {
        this.consentGiven = consentGiven;
    }
    
    public Boolean isInternalControlsEnabled() {
        return internalControlsEnabled;
    }
    
    public void setInternalControlsEnabled(Boolean internalControlsEnabled) {
        this.internalControlsEnabled = internalControlsEnabled;
    }
    
    public Boolean isDataEncryptionEnabled() {
        return dataEncryptionEnabled;
    }
    
    public void setDataEncryptionEnabled(Boolean dataEncryptionEnabled) {
        this.dataEncryptionEnabled = dataEncryptionEnabled;
    }
    
    public Boolean isAccessControlEnabled() {
        return accessControlEnabled;
    }
    
    public void setAccessControlEnabled(Boolean accessControlEnabled) {
        this.accessControlEnabled = accessControlEnabled;
    }
    
    public Boolean isNetworkSecurityEnabled() {
        return networkSecurityEnabled;
    }
    
    public void setNetworkSecurityEnabled(Boolean networkSecurityEnabled) {
        this.networkSecurityEnabled = networkSecurityEnabled;
    }
    
    public String getOperationType() {
        return operationType;
    }
    
    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }
    
    public Integer getDataAge() {
        return dataAge;
    }
    
    public void setDataAge(Integer dataAge) {
        this.dataAge = dataAge;
    }
    
    public String getIpAddress() {
        return ipAddress;
    }
    
    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }
    
    public String getUserAgent() {
        return userAgent;
    }
    
    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }
}
