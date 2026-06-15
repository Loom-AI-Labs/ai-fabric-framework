package ai.fabric.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO for AI Content Filter Responses
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIContentFilterResponse {
    
    /**
     * Request ID
     */
    private String requestId;
    
    /**
     * Canonical subject identifier.
     */
    private String subjectId;
    
    /**
     * Violations detected
     */
    private List<String> violations;
    
    /**
     * Whether content is blocked
     */
    private Boolean isBlocked;
    
    /**
     * Whether content is allowed
     */
    private Boolean isAllowed;
    
    /**
     * Whether content should be filtered
     */
    private Boolean shouldFilter;
    
    /**
     * Sanitized content
     */
    private String sanitizedContent;
    
    /**
     * Content score (0-1)
     */
    private Double contentScore;
    
    /**
     * Filter recommendations
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
     * Filter settings used
     */
    private Map<String, Object> filterSettings;
    
    /**
     * Content analysis results
     */
    private Map<String, Object> contentAnalysis;
    
    /**
     * Violation details
     */
    private Map<String, Object> violationDetails;
    
    /**
     * Content metrics
     */
    private Map<String, Object> contentMetrics;
    
    /**
     * Filter metrics
     */
    private Map<String, Object> filterMetrics;
    
    /**
     * Content quality score
     */
    private Double contentQualityScore;
    
    /**
     * Content readability score
     */
    private Double contentReadabilityScore;
    
    /**
     * Content sentiment score
     */
    private Double contentSentimentScore;
    
    /**
     * Content toxicity score
     */
    private Double contentToxicityScore;
    
    /**
     * Content bias score
     */
    private Double contentBiasScore;
    
    /**
     * Content accuracy score
     */
    private Double contentAccuracyScore;
    
    /**
     * Content relevance score
     */
    private Double contentRelevanceScore;
    
    /**
     * Content completeness score
     */
    private Double contentCompletenessScore;
    
    /**
     * Content consistency score
     */
    private Double contentConsistencyScore;
    
    /**
     * Content clarity score
     */
    private Double contentClarityScore;
    
    /**
     * Content originality score
     */
    private Double contentOriginalityScore;
    
    /**
     * Content uniqueness score
     */
    private Double contentUniquenessScore;
    
    /**
     * Content diversity score
     */
    private Double contentDiversityScore;
    
    /**
     * Content inclusivity score
     */
    private Double contentInclusivityScore;
    
    /**
     * Content accessibility score
     */
    private Double contentAccessibilityScore;
    
    /**
     * Content usability score
     */
    private Double contentUsabilityScore;
    
    /**
     * Content maintainability score
     */
    private Double contentMaintainabilityScore;
    
    /**
     * Content scalability score
     */
    private Double contentScalabilityScore;
    
    /**
     * Content performance score
     */
    private Double contentPerformanceScore;
    
    /**
     * Content security score
     */
    private Double contentSecurityScore;
    
    /**
     * Content privacy score
     */
    private Double contentPrivacyScore;
    
    /**
     * Content compliance score
     */
    private Double contentComplianceScore;
    
    /**
     * Content audit score
     */
    private Double contentAuditScore;
    
    /**
     * Content governance score
     */
    private Double contentGovernanceScore;
    
    /**
     * Content risk score
     */
    private Double contentRiskScore;
    
    /**
     * Content impact score
     */
    private Double contentImpactScore;
    
    /**
     * Content value score
     */
    private Double contentValueScore;
    
    /**
     * Content cost score
     */
    private Double contentCostScore;
    
    /**
     * Content benefit score
     */
    private Double contentBenefitScore;
    
    /**
     * Content roi score
     */
    private Double contentRoiScore;
    
    /**
     * Content efficiency score
     */
    private Double contentEfficiencyScore;
    
    /**
     * Content effectiveness score
     */
    private Double contentEffectivenessScore;
    
    /**
     * Content productivity score
     */
    private Double contentProductivityScore;
    
    /**
     * Content innovation score
     */
    private Double contentInnovationScore;
    
    /**
     * Content creativity score
     */
    private Double contentCreativityScore;
    
    /**
     * Content engagement score
     */
    private Double contentEngagementScore;
    
    /**
     * Content satisfaction score
     */
    private Double contentSatisfactionScore;
    
    /**
     * Content loyalty score
     */
    private Double contentLoyaltyScore;
    
    /**
     * Content advocacy score
     */
    private Double contentAdvocacyScore;
    
    /**
     * Content recommendation score
     */
    private Double contentRecommendationScore;
    
    /**
     * Content sharing score
     */
    private Double contentSharingScore;
    
    /**
     * Content viral score
     */
    private Double contentViralScore;
    
    /**
     * Content trending score
     */
    private Double contentTrendingScore;
    
    /**
     * Content popularity score
     */
    private Double contentPopularityScore;
    
    /**
     * Content authority score
     */
    private Double contentAuthorityScore;
    
    /**
     * Content credibility score
     */
    private Double contentCredibilityScore;
    
    /**
     * Content trustworthiness score
     */
    private Double contentTrustworthinessScore;
    
    /**
     * Content reliability score
     */
    private Double contentReliabilityScore;
    
    /**
     * Content validity score
     */
    private Double contentValidityScore;
    
    /**
     * Content veracity score
     */
    private Double contentVeracityScore;
    
    /**
     * Content authenticity score
     */
    private Double contentAuthenticityScore;
    
    /**
     * Content genuineness score
     */
    private Double contentGenuinenessScore;
    
    /**
     * Content legitimacy score
     */
    private Double contentLegitimacyScore;
    
    /**
     * Content legality score
     */
    private Double contentLegalityScore;
    
    /**
     * Content ethicality score
     */
    private Double contentEthicalityScore;
    
    /**
     * Content morality score
     */
    private Double contentMoralityScore;
    
    /**
     * Content appropriateness score
     */
    private Double contentAppropriatenessScore;
    
    /**
     * Content suitability score
     */
    private Double contentSuitabilityScore;
    
    /**
     * Content fitness score
     */
    private Double contentFitnessScore;
    
    /**
     * Content adequacy score
     */
    private Double contentAdequacyScore;
    
    /**
     * Content sufficiency score
     */
    private Double contentSufficiencyScore;
    
    /**
     * Content completeness score
     */
    private Double contentCompletenessScore2;
    
    /**
     * Content thoroughness score
     */
    private Double contentThoroughnessScore;
    
    /**
     * Content comprehensiveness score
     */
    private Double contentComprehensivenessScore;
    
    /**
     * Content exhaustiveness score
     */
    private Double contentExhaustivenessScore;
    
    /**
     * Content inclusiveness score
     */
    private Double contentInclusivenessScore;
    
    /**
     * Content exclusiveness score
     */
    private Double contentExclusivenessScore;
    
    /**
     * Content selectivity score
     */
    private Double contentSelectivityScore;
    
    /**
     * Content specificity score
     */
    private Double contentSpecificityScore;
    
    /**
     * Content generality score
     */
    private Double contentGeneralityScore;
    
    /**
     * Content universality score
     */
    private Double contentUniversalityScore;
    
    /**
     * Content particularity score
     */
    private Double contentParticularityScore;
    
    /**
     * Content individuality score
     */
    private Double contentIndividualityScore;
    
    /**
     * Content uniqueness score
     */
    private Double contentUniquenessScore2;
    
    /**
     * Content distinctiveness score
     */
    private Double contentDistinctivenessScore;
    
    /**
     * Content differentiation score
     */
    private Double contentDifferentiationScore;
    
    /**
     * Content variation score
     */
    private Double contentVariationScore;
    
    /**
     * Content diversity score
     */
    private Double contentDiversityScore2;
    
    /**
     * Content variety score
     */
    private Double contentVarietyScore;
    
    /**
     * Content multiplicity score
     */
    private Double contentMultiplicityScore;
    
    /**
     * Content plurality score
     */
    private Double contentPluralityScore;
    
    /**
     * Content multiplicity score
     */
    private Double contentMultiplicityScore2;
    
    /**
     * Content plurality score
     */
    private Double contentPluralityScore2;
    
    /**
     * Content multiplicity score
     */
    private Double contentMultiplicityScore3;
    
    /**
     * Content plurality score
     */
    private Double contentPluralityScore3;
    
    /**
     * Content multiplicity score
     */
    private Double contentMultiplicityScore4;
    
    /**
     * Content plurality score
     */
    private Double contentPluralityScore4;
    
    /**
     * Content multiplicity score
     */
    private Double contentMultiplicityScore5;
    
    /**
     * Content plurality score
     */
    private Double contentPluralityScore5;
    
    /**
     * Content multiplicity score
     */
    private Double contentMultiplicityScore6;
    
    /**
     * Content plurality score
     */
    private Double contentPluralityScore6;
    
    /**
     * Content multiplicity score
     */
    private Double contentMultiplicityScore7;
    
    /**
     * Content plurality score
     */
    private Double contentPluralityScore7;
    
    /**
     * Content multiplicity score
     */
    private Double contentMultiplicityScore8;
    
    /**
     * Content plurality score
     */
    private Double contentPluralityScore8;
    
    /**
     * Content multiplicity score
     */
    private Double contentMultiplicityScore9;
    
    /**
     * Content plurality score
     */
    private Double contentPluralityScore9;
    
    /**
     * Additional content analysis scores
     */
    private Map<String, Double> additionalScores;
}
