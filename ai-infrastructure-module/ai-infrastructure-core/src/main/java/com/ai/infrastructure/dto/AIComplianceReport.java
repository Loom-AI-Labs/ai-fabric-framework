package com.ai.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO for AI Compliance Reports
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIComplianceReport {
    
    /**
     * Report ID
     */
    private String reportId;
    
    /**
     * Request ID
     */
    private String requestId;
    
    /**
     * Canonical subject identifier.
     */
    private String subjectId;
    
    /**
     * Report timestamp
     */
    private LocalDateTime timestamp;
    
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
     * Regulation types checked
     */
    private List<String> regulationTypes;
    
    /**
     * Data classification
     */
    private String dataClassification;
    
    /**
     * Purpose of data processing
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
     * Consent status
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
     * Safeguards in place
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
     * Report status
     */
    private String status;
    
    /**
     * Report priority
     */
    private String priority;
    
    /**
     * Report severity
     */
    private String severity;
    
    /**
     * Report category
     */
    private String category;
    
    /**
     * Compliance framework
     */
    private String framework;
    
    /**
     * Report tags
     */
    private List<String> tags;
    
    /**
     * Report metadata
     */
    private Map<String, Object> metadata;
    
    /**
     * Report notes
     */
    private String notes;
    
    /**
     * Report attachments
     */
    private List<String> attachments;
    
    /**
     * Report version
     */
    private String version;
    
    /**
     * Report author
     */
    private String author;
    
    /**
     * Report reviewer
     */
    private String reviewer;
    
    /**
     * Report approver
     */
    private String approver;
    
    /**
     * Report approval date
     */
    private LocalDateTime approvalDate;
    
    /**
     * Report distribution list
     */
    private List<String> distributionList;
    
    /**
     * Report confidentiality level
     */
    private String confidentialityLevel;
    
    /**
     * Report retention period
     */
    private Integer reportRetentionPeriod;
    
    /**
     * Report archive status
     */
    private Boolean archived;
    
    /**
     * Report archive date
     */
    private LocalDateTime archivedDate;
    
    /**
     * Report archive reason
     */
    private String archiveReason;
    
    // Manual getters/setters for Lombok compatibility
    public Boolean isDataPrivacyCompliant() {
        return dataPrivacyCompliant;
    }
    
    public void setDataPrivacyCompliant(Boolean dataPrivacyCompliant) {
        this.dataPrivacyCompliant = dataPrivacyCompliant;
    }
    
    public Boolean isRegulatoryCompliant() {
        return regulatoryCompliant;
    }
    
    public void setRegulatoryCompliant(Boolean regulatoryCompliant) {
        this.regulatoryCompliant = regulatoryCompliant;
    }
    
    public Boolean isAuditCompliant() {
        return auditCompliant;
    }
    
    public void setAuditCompliant(Boolean auditCompliant) {
        this.auditCompliant = auditCompliant;
    }
    
    public Boolean isRetentionCompliant() {
        return retentionCompliant;
    }
    
    public void setRetentionCompliant(Boolean retentionCompliant) {
        this.retentionCompliant = retentionCompliant;
    }
    
    public Boolean isOverallCompliant() {
        return overallCompliant;
    }
    
    public void setOverallCompliant(Boolean overallCompliant) {
        this.overallCompliant = overallCompliant;
    }
}
