package com.ai.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO for AI Security Events
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AISecurityEvent {
    
    /**
     * Unique event ID
     */
    private String eventId;
    
    /**
     * Canonical subject identifier associated with the event.
     */
    private String subjectId;
    
    /**
     * Request ID that triggered the event
     */
    private String requestId;
    
    /**
     * Type of security event
     */
    private String eventType;
    
    /**
     * Threats detected
     */
    private List<String> threatsDetected;
    
    /**
     * Security score (0-100)
     */
    private Double securityScore;
    
    /**
     * Event severity: LOW, MEDIUM, HIGH, CRITICAL
     */
    private String severity;
    
    /**
     * Event timestamp
     */
    private LocalDateTime timestamp;
    
    /**
     * IP address of the request
     */
    private String ipAddress;
    
    /**
     * User agent of the request
     */
    private String userAgent;
    
    /**
     * Additional context
     */
    private String context;
    
    /**
     * Event description
     */
    private String description;
    
    /**
     * Event status: ACTIVE, RESOLVED, IGNORED
     */
    private String status;
    
    /**
     * Resolution notes
     */
    private String resolutionNotes;
    
    /**
     * Assigned to user
     */
    private String assignedTo;
    
    /**
     * Event metadata
     */
    private Map<String, Object> metadata;
    
    /**
     * Risk level
     */
    private String riskLevel;
    
    /**
     * Event category
     */
    private String category;
    
    /**
     * Source system
     */
    private String sourceSystem;
    
    /**
     * Event hash for deduplication
     */
    private String eventHash;
    
    /**
     * Related events
     */
    private List<String> relatedEventIds;
    
    /**
     * Event tags
     */
    private List<String> tags;
    
    /**
     * Geographic location
     */
    private String location;
    
    /**
     * Device information
     */
    private String deviceInfo;
    
    /**
     * Session information
     */
    private String sessionId;
    
    /**
     * Event duration in milliseconds
     */
    private Long durationMs;
    
    /**
     * Event impact level
     */
    private String impactLevel;
    
    /**
     * Event confidence score
     */
    private Double confidenceScore;
    
    /**
     * False positive flag
     */
    private Boolean falsePositive;
    
    /**
     * Event escalation level
     */
    private Integer escalationLevel;
    
    /**
     * Event priority
     */
    private String priority;
    
    /**
     * Event source IP country
     */
    private String sourceCountry;
    
    /**
     * Event source IP city
     */
    private String sourceCity;
    
    /**
     * Event source IP organization
     */
    private String sourceOrganization;
    
    /**
     * Event source IP ISP
     */
    private String sourceISP;
    
    /**
     * Event source IP threat intelligence
     */
    private Map<String, Object> threatIntelligence;
    
    /**
     * Event remediation actions
     */
    private List<String> remediationActions;
    
    /**
     * Event compliance status
     */
    private String complianceStatus;
    
    /**
     * Event data classification
     */
    private String dataClassification;
    
    /**
     * Event retention period
     */
    private Integer retentionPeriodDays;
    
    /**
     * Event archive status
     */
    private Boolean archived;
    
    /**
     * Event archive timestamp
     */
    private LocalDateTime archivedAt;
    
    /**
     * Event archive reason
     */
    private String archiveReason;
}
