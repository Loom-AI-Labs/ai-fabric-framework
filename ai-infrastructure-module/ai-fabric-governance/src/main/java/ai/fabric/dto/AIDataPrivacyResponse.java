package ai.fabric.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO for AI Data Privacy Responses
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIDataPrivacyResponse {
    
    /**
     * Request ID
     */
    private String requestId;
    
    /**
     * Canonical subject identifier.
     */
    private String subjectId;
    
    /**
     * Data classification
     */
    private String dataClassification;
    
    /**
     * Whether the request is compliant
     */
    private Boolean isCompliant;
    
    /**
     * Processed content (anonymized if needed)
     */
    private String processedContent;
    
    /**
     * Whether consent is required
     */
    private Boolean consentRequired;
    
    /**
     * Privacy recommendations
     */
    private List<String> recommendations;
    
    /**
     * Privacy controls applied
     */
    private Map<String, Object> privacyControls;
    
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
     * Privacy score (0-100)
     */
    private Double privacyScore;
    
    /**
     * Compliance score (0-100)
     */
    private Double complianceScore;
    
    /**
     * Risk score (0-100)
     */
    private Double riskScore;
    
    /**
     * Impact score (0-100)
     */
    private Double impactScore;
    
    /**
     * Privacy violations
     */
    private List<String> privacyViolations;
    
    /**
     * Compliance violations
     */
    private List<String> complianceViolations;
    
    /**
     * Risk violations
     */
    private List<String> riskViolations;
    
    /**
     * Impact violations
     */
    private List<String> impactViolations;
    
    /**
     * Privacy recommendations
     */
    private List<String> privacyRecommendations;
    
    /**
     * Compliance recommendations
     */
    private List<String> complianceRecommendations;
    
    /**
     * Risk recommendations
     */
    private List<String> riskRecommendations;
    
    /**
     * Impact recommendations
     */
    private List<String> impactRecommendations;
    
    /**
     * Privacy controls
     */
    private Map<String, Object> privacyControlsDetails;
    
    /**
     * Compliance controls
     */
    private Map<String, Object> complianceControls;
    
    /**
     * Risk controls
     */
    private Map<String, Object> riskControls;
    
    /**
     * Impact controls
     */
    private Map<String, Object> impactControls;
    
    /**
     * Privacy metrics
     */
    private Map<String, Object> privacyMetrics;
    
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
     * Data subject rights status
     */
    private Map<String, Boolean> dataSubjectRightsStatus;
    
    /**
     * Processing activities status
     */
    private Map<String, Boolean> processingActivitiesStatus;
    
    /**
     * Safeguards status
     */
    private Map<String, Boolean> safeguardsStatus;
    
    /**
     * Recipients status
     */
    private Map<String, Boolean> recipientsStatus;
    
    /**
     * Third countries status
     */
    private Map<String, Boolean> thirdCountriesStatus;
    
    /**
     * Cross-border transfer status
     */
    private Boolean crossBorderTransferStatus;
    
    /**
     * Data retention status
     */
    private Boolean dataRetentionStatus;
    
    /**
     * Data minimization status
     */
    private Boolean dataMinimizationStatus;
    
    /**
     * Purpose limitation status
     */
    private Boolean purposeLimitationStatus;
    
    /**
     * Storage limitation status
     */
    private Boolean storageLimitationStatus;
    
    /**
     * Accuracy status
     */
    private Boolean accuracyStatus;
    
    /**
     * Integrity and confidentiality status
     */
    private Boolean integrityAndConfidentialityStatus;
    
    /**
     * Accountability status
     */
    private Boolean accountabilityStatus;
    
    /**
     * Transparency status
     */
    private Boolean transparencyStatus;
    
    /**
     * Fairness status
     */
    private Boolean fairnessStatus;
    
    /**
     * Lawfulness status
     */
    private Boolean lawfulnessStatus;
}
