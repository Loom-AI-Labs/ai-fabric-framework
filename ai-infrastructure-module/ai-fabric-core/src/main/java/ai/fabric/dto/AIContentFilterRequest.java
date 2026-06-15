package ai.fabric.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO for AI Content Filter Requests
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIContentFilterRequest {
    
    /**
     * Request ID
     */
    private String requestId;
    
    /**
     * Canonical subject context for the evaluated caller.
     */
    private AIAccessSubjectContext authContext;
    
    /**
     * Content to filter
     */
    private String content;
    
    /**
     * Content type
     */
    private String contentType;
    
    /**
     * Content language
     */
    private String language;
    
    /**
     * Content category
     */
    private String category;
    
    /**
     * Content tags
     */
    private List<String> tags;
    
    /**
     * Content metadata
     */
    private Map<String, Object> metadata;
    
    /**
     * Filter settings
     */
    private Map<String, Object> filterSettings;
    
    /**
     * Maximum violations allowed
     */
    private Integer maxViolations;
    
    /**
     * Minimum content score
     */
    private Double minContentScore;
    
    /**
     * Content age in days
     */
    private Integer contentAge;
    
    /**
     * Content source
     */
    private String contentSource;
    
    /**
     * Content author
     */
    private String contentAuthor;
    
    /**
     * Content publisher
     */
    private String contentPublisher;
    
    /**
     * Content license
     */
    private String contentLicense;
    
    /**
     * Content copyright
     */
    private String contentCopyright;
    
    /**
     * Content version
     */
    private String contentVersion;
    
    /**
     * Content status
     */
    private String contentStatus;
    
    /**
     * Content priority
     */
    private String contentPriority;
    
    /**
     * Content severity
     */
    private String contentSeverity;
    
    /**
     * Content confidentiality level
     */
    private String contentConfidentialityLevel;
    
    /**
     * Content retention period
     */
    private Integer contentRetentionPeriod;
    
    /**
     * Content archive status
     */
    private Boolean contentArchived;
    
    /**
     * Content archive date
     */
    private LocalDateTime contentArchivedDate;
    
    /**
     * Content archive reason
     */
    private String contentArchiveReason;
    
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
    private String requestCategory;
    
    /**
     * Request tags
     */
    private List<String> requestTags;
    
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
}
