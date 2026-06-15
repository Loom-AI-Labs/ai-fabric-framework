package ai.fabric.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO for AI Access Control Responses
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIAccessControlResponse {
    
    /**
     * Request ID
     */
    private String requestId;
    
    /**
     * Canonical subject identifier evaluated by access control.
     */
    private String subjectId;
    
    /**
     * Resource ID
     */
    private String resourceId;
    
    /**
     * Operation type
     */
    private String operationType;
    
    /**
     * Whether access is granted
     */
    private Boolean accessGranted;
    
    /**
     * Indicates whether the decision was served from cache
     */
    private Boolean fromCache;
    
    /**
     * Access decision
     */
    private String accessDecision;
    
    /**
     * Role-based access status
     */
    private Boolean roleAccess;
    
    /**
     * Attribute-based access status
     */
    private Boolean attributeAccess;
    
    /**
     * Time-based access status
     */
    private Boolean timeAccess;
    
    /**
     * Location-based access status
     */
    private Boolean locationAccess;
    
    /**
     * Resource-based access status
     */
    private Boolean resourceAccess;
    
    /**
     * Access recommendations
     */
    private List<String> recommendations;
    
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
     * Access control details
     */
    private Map<String, Object> accessControlDetails;
    
    /**
     * Security assessment
     */
    private String securityAssessment;
    
    /**
     * Risk assessment
     */
    private String riskAssessment;
    
    /**
     * Compliance assessment
     */
    private String complianceAssessment;
    
    /**
     * Access metrics
     */
    private Map<String, Object> accessMetrics;
    
    /**
     * Security metrics
     */
    private Map<String, Object> securityMetrics;
    
    /**
     * Risk metrics
     */
    private Map<String, Object> riskMetrics;
    
    /**
     * Compliance metrics
     */
    private Map<String, Object> complianceMetrics;
    
    /**
     * Access level
     */
    private String accessLevel;
    
    /**
     * Access duration
     */
    private Integer accessDuration;
    
    /**
     * Access expiration
     */
    private LocalDateTime accessExpiration;
    
    /**
     * Access conditions
     */
    private List<String> accessConditions;
    
    /**
     * Access restrictions
     */
    private List<String> accessRestrictions;
    
    /**
     * Access permissions
     */
    private List<String> accessPermissions;
    
    /**
     * Access roles
     */
    private List<String> accessRoles;
    
    /**
     * Access attributes
     */
    private Map<String, Object> accessAttributes;
    
    /**
     * Access context
     */
    private Map<String, Object> accessContext;
    
    /**
     * Access policy
     */
    private String accessPolicy;
    
    /**
     * Access policy version
     */
    private String accessPolicyVersion;
    
    /**
     * Access policy effective date
     */
    private LocalDateTime accessPolicyEffectiveDate;
    
    /**
     * Access policy expiration date
     */
    private LocalDateTime accessPolicyExpirationDate;
    
    /**
     * Access policy status
     */
    private String accessPolicyStatus;
    
    /**
     * Access policy priority
     */
    private String accessPolicyPriority;
    
    /**
     * Access policy severity
     */
    private String accessPolicySeverity;
    
    /**
     * Access policy category
     */
    private String accessPolicyCategory;
    
    /**
     * Access policy tags
     */
    private List<String> accessPolicyTags;
    
    /**
     * Access policy notes
     */
    private String accessPolicyNotes;
    
    /**
     * Access policy attachments
     */
    private List<String> accessPolicyAttachments;
    
    /**
     * Access policy version
     */
    private String accessPolicyVersion2;
    
    /**
     * Access policy author
     */
    private String accessPolicyAuthor;
    
    /**
     * Access policy reviewer
     */
    private String accessPolicyReviewer;
    
    /**
     * Access policy approver
     */
    private String accessPolicyApprover;
    
    /**
     * Access policy approval date
     */
    private LocalDateTime accessPolicyApprovalDate;
    
    /**
     * Access policy distribution list
     */
    private List<String> accessPolicyDistributionList;
    
    /**
     * Access policy confidentiality level
     */
    private String accessPolicyConfidentialityLevel;
    
    /**
     * Access policy retention period
     */
    private Integer accessPolicyRetentionPeriod;
    
    /**
     * Access policy archive status
     */
    private Boolean accessPolicyArchived;
    
    /**
     * Access policy archive date
     */
    private LocalDateTime accessPolicyArchivedDate;
    
    /**
     * Access policy archive reason
     */
    private String accessPolicyArchiveReason;
}
