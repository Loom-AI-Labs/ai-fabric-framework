package ai.fabric.privacy;

import ai.fabric.dto.AIDataPrivacyRequest;
import ai.fabric.dto.AIDataPrivacyResponse;
import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.core.AICoreService;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * AI Data Privacy Service for data protection and privacy compliance
 */
@Slf4j
@RequiredArgsConstructor
public class AIDataPrivacyService {

    private static final String TEMPLATE_FAMILY = "governance/data-privacy";
    private static final String TEMPLATE_CLASSIFY_SENSITIVITY = "classify-sensitivity";
    private static final String PLACEHOLDER_CONTENT = "content";

    private final AICoreService aiCoreService;
    private final PromptTemplateResolver promptTemplateResolver;
    private final PromptRenderer promptRenderer;
    private final Map<String, Map<String, Object>> privacySettings = new ConcurrentHashMap<>();
    private final Map<String, List<String>> dataClassifications = new ConcurrentHashMap<>();

    /**
     * Process data privacy request
     */
    public AIDataPrivacyResponse processDataPrivacyRequest(AIDataPrivacyRequest request) {
        log.info("Processing data privacy request for subject: {}", resolveSubjectId(request));
        
        try {
            long startTime = System.currentTimeMillis();
            
            // Analyze data sensitivity
            String dataClassification = analyzeDataSensitivity(request.getContent());
            
            // Check privacy compliance
            boolean isCompliant = checkPrivacyCompliance(request, dataClassification);
            
            // Apply data anonymization if needed
            String processedContent = applyDataAnonymization(request.getContent(), dataClassification);
            
            // Check consent requirements
            boolean consentRequired = checkConsentRequirements(request, dataClassification);
            
            // Generate privacy recommendations
            List<String> recommendations = generatePrivacyRecommendations(request, dataClassification, isCompliant);
            
            // Apply privacy controls
            Map<String, Object> privacyControls = applyPrivacyControls(request, dataClassification);
            
            long processingTime = System.currentTimeMillis() - startTime;
            
            return AIDataPrivacyResponse.builder()
                .requestId(request.getRequestId())
                .subjectId(resolveSubjectId(request))
                .dataClassification(dataClassification)
                .isCompliant(isCompliant)
                .processedContent(processedContent)
                .consentRequired(consentRequired)
                .recommendations(recommendations)
                .privacyControls(privacyControls)
                .processingTimeMs(processingTime)
                .timestamp(LocalDateTime.now())
                .success(true)
                .build();
                
        } catch (Exception e) {
            log.error("Error processing data privacy request", e);
            return AIDataPrivacyResponse.builder()
                .requestId(request.getRequestId())
                .subjectId(resolveSubjectId(request))
                .isCompliant(false)
                .success(false)
                .errorMessage(e.getMessage())
                .build();
        }
    }

    private String resolveSubjectId(AIDataPrivacyRequest request) {
        if (request == null || request.getAuthContext() == null) {
            return null;
        }
        AIAccessSubjectContext authContext = request.getAuthContext();
        if (authContext.getSubjectId() != null && !authContext.getSubjectId().isBlank()) {
            return authContext.getSubjectId();
        }
        if (authContext.getSessionId() != null && !authContext.getSessionId().isBlank()) {
            return authContext.getSessionId();
        }
        return null;
    }

    /**
     * Analyze data sensitivity using AI
     */
    private String analyzeDataSensitivity(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "PUBLIC";
        }
        
        try {
            String prompt = promptRenderer.render(
                promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_CLASSIFY_SENSITIVITY).template(),
                Map.of(PLACEHOLDER_CONTENT, content)
            );
            
            String response = aiCoreService.generateText(prompt);
            String classification = response.trim().toUpperCase();
            
            // Validate classification
            if (Arrays.asList("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED").contains(classification)) {
                return classification;
            } else {
                return "INTERNAL"; // Default fallback
            }
            
        } catch (Exception e) {
            log.warn("Failed to analyze data sensitivity, using default classification", e);
            return "INTERNAL";
        }
    }

    /**
     * Check privacy compliance
     */
    private boolean checkPrivacyCompliance(AIDataPrivacyRequest request, String dataClassification) {
        // Check if user has consent for this data type
        if (!hasValidConsent(request, dataClassification)) {
            return false;
        }
        
        // Check if data processing purpose is legitimate
        if (!isLegitimatePurpose(request)) {
            return false;
        }
        
        // Check if data minimization is applied
        if (!isDataMinimized(request)) {
            return false;
        }
        
        // Check if retention period is appropriate
        if (!isRetentionPeriodAppropriate(request, dataClassification)) {
            return false;
        }
        
        return true;
    }

    /**
     * Apply data anonymization based on classification
     */
    private String applyDataAnonymization(String content, String dataClassification) {
        if (content == null || content.trim().isEmpty()) {
            return content;
        }
        
        switch (dataClassification) {
            case "RESTRICTED":
                return anonymizeRestrictedData(content);
            case "CONFIDENTIAL":
                return anonymizeConfidentialData(content);
            case "INTERNAL":
                return anonymizeInternalData(content);
            case "PUBLIC":
            default:
                return content; // No anonymization needed for public data
        }
    }

    /**
     * Anonymize restricted data
     */
    private String anonymizeRestrictedData(String content) {
        // Remove or mask highly sensitive information
        String anonymized = content;
        
        // Mask email addresses
        anonymized = anonymized.replaceAll("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b", "[EMAIL]");
        
        // Mask phone numbers
        anonymized = anonymized.replaceAll("\\b\\d{3}[-.]?\\d{3}[-.]?\\d{4}\\b", "[PHONE]");
        
        // Mask SSN
        anonymized = anonymized.replaceAll("\\b\\d{3}-\\d{2}-\\d{4}\\b", "[SSN]");
        
        // Mask credit card numbers
        anonymized = anonymized.replaceAll("\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b", "[CARD]");
        
        return anonymized;
    }

    /**
     * Anonymize confidential data
     */
    private String anonymizeConfidentialData(String content) {
        // Less aggressive anonymization for confidential data
        String anonymized = content;
        
        // Mask email addresses
        anonymized = anonymized.replaceAll("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b", "[EMAIL]");
        
        // Mask phone numbers
        anonymized = anonymized.replaceAll("\\b\\d{3}[-.]?\\d{3}[-.]?\\d{4}\\b", "[PHONE]");
        
        return anonymized;
    }

    /**
     * Anonymize internal data
     */
    private String anonymizeInternalData(String content) {
        // Minimal anonymization for internal data
        String anonymized = content;
        
        // Only mask very sensitive information
        anonymized = anonymized.replaceAll("\\b\\d{3}-\\d{2}-\\d{4}\\b", "[SSN]");
        
        return anonymized;
    }

    /**
     * Check consent requirements
     */
    private boolean checkConsentRequirements(AIDataPrivacyRequest request, String dataClassification) {
        // Check if consent is required based on data classification
        switch (dataClassification) {
            case "RESTRICTED":
            case "CONFIDENTIAL":
                return true; // Always require consent for sensitive data
            case "INTERNAL":
                return request.isConsentRequired(); // Check user preference
            case "PUBLIC":
            default:
                return false; // No consent required for public data
        }
    }

    /**
     * Generate privacy recommendations
     */
    private List<String> generatePrivacyRecommendations(AIDataPrivacyRequest request, 
                                                       String dataClassification, 
                                                       boolean isCompliant) {
        List<String> recommendations = new ArrayList<>();
        
        if (!isCompliant) {
            recommendations.add("Review and update privacy settings to ensure compliance");
        }
        
        switch (dataClassification) {
            case "RESTRICTED":
                recommendations.add("Implement additional security measures for restricted data");
                recommendations.add("Ensure proper access controls are in place");
                recommendations.add("Consider data encryption for storage and transmission");
                break;
            case "CONFIDENTIAL":
                recommendations.add("Implement access logging and monitoring");
                recommendations.add("Ensure data is only accessible to authorized personnel");
                break;
            case "INTERNAL":
                recommendations.add("Review data handling procedures");
                recommendations.add("Ensure proper data classification is maintained");
                break;
        }
        
        if (request.isConsentRequired() && !request.isConsentGiven()) {
            recommendations.add("Obtain explicit consent before processing personal data");
        }
        
        if (request.getDataRetentionPeriod() == null) {
            recommendations.add("Define appropriate data retention period");
        }
        
        return recommendations;
    }

    /**
     * Apply privacy controls
     */
    private Map<String, Object> applyPrivacyControls(AIDataPrivacyRequest request, String dataClassification) {
        Map<String, Object> controls = new HashMap<>();
        
        // Retention controls
        controls.put("retentionPeriod", getRetentionPeriod(dataClassification));
        
        // Access controls
        controls.put("accessLevel", getAccessLevel(dataClassification));
        
        // Encryption controls
        controls.put("encryptionRequired", isEncryptionRequired(dataClassification));
        
        // Audit controls
        controls.put("auditRequired", isAuditRequired(dataClassification));
        
        // Data minimization
        controls.put("dataMinimization", true);
        
        // Purpose limitation
        controls.put("purposeLimitation", request.getPurpose());
        
        // Consent tracking
        controls.put("consentTracking", request.isConsentRequired());
        
        return controls;
    }

    private int getRetentionPeriod(String dataClassification) {
        switch (dataClassification) {
            case "RESTRICTED":
                return 30; // 30 days max for restricted data
            case "CONFIDENTIAL":
                return 90; // 90 days for confidential
            case "INTERNAL":
                return 365; // 1 year for internal
            case "PUBLIC":
            default:
                return 0; // No retention limit for public
        }
    }

    private String getAccessLevel(String dataClassification) {
        switch (dataClassification) {
            case "RESTRICTED":
                return "ADMIN_ONLY";
            case "CONFIDENTIAL":
                return "AUTHORIZED_PERSONNEL";
            case "INTERNAL":
                return "INTERNAL_USERS";
            case "PUBLIC":
            default:
                return "PUBLIC_ACCESS";
        }
    }

    private boolean isEncryptionRequired(String dataClassification) {
        return Arrays.asList("RESTRICTED", "CONFIDENTIAL").contains(dataClassification);
    }

    private boolean isAuditRequired(String dataClassification) {
        return Arrays.asList("RESTRICTED", "CONFIDENTIAL").contains(dataClassification);
    }

    private boolean hasValidConsent(AIDataPrivacyRequest request, String dataClassification) {
        if (Arrays.asList("RESTRICTED", "CONFIDENTIAL").contains(dataClassification)) {
            return request.isConsentGiven() != null && request.isConsentGiven();
        }
        return true;
    }

    private boolean isLegitimatePurpose(AIDataPrivacyRequest request) {
        return request.getPurpose() != null && !request.getPurpose().trim().isEmpty();
    }

    private boolean isDataMinimized(AIDataPrivacyRequest request) {
        return request.getContent() != null && request.getContent().length() < 10000; // Simple check
    }

    private boolean isRetentionPeriodAppropriate(AIDataPrivacyRequest request, String dataClassification) {
        if (request.getDataRetentionPeriod() == null) {
            return true; // Will be set by controls
        }
        int maxRetention = getRetentionPeriod(dataClassification);
        return request.getDataRetentionPeriod() <= maxRetention;
    }

    /**
     * Get privacy settings for a user
     */
    public Map<String, Object> getPrivacySettings(String userId) {
        return privacySettings.getOrDefault(userId, new HashMap<>());
    }

    /**
     * Update privacy settings for a user
     */
    public void updatePrivacySettings(String userId, Map<String, Object> settings) {
        privacySettings.put(userId, settings);
    }

    /**
     * Get data classifications for a user
     */
    public List<String> getDataClassifications(String userId) {
        return dataClassifications.getOrDefault(userId, new ArrayList<>());
    }

    /**
     * Add data classification for a user
     */
    public void addDataClassification(String userId, String classification) {
        dataClassifications.computeIfAbsent(userId, k -> new ArrayList<>()).add(classification);
    }

    /**
     * Get privacy statistics
     */
    public Map<String, Object> getPrivacyStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        long totalUsers = privacySettings.size();
        long usersWithConsent = privacySettings.values().stream()
            .mapToLong(settings -> (Boolean) settings.getOrDefault("consentGiven", false) ? 1 : 0)
            .sum();
        
        stats.put("totalUsers", totalUsers);
        stats.put("usersWithConsent", usersWithConsent);
        stats.put("consentRate", totalUsers > 0 ? (double) usersWithConsent / totalUsers : 0.0);
        
        // Data classification distribution
        Map<String, Long> classificationDistribution = dataClassifications.values().stream()
            .flatMap(List::stream)
            .collect(Collectors.groupingBy(
                classification -> classification,
                Collectors.counting()
            ));
        stats.put("classificationDistribution", classificationDistribution);
        
        return stats;
    }

    /**
     * Generate privacy report
     */
    public Map<String, Object> generatePrivacyReport(String userId) {
        Map<String, Object> report = new HashMap<>();
        
        Map<String, Object> userSettings = getPrivacySettings(userId);
        List<String> userClassifications = getDataClassifications(userId);
        
        report.put("userId", userId);
        report.put("timestamp", LocalDateTime.now());
        report.put("privacySettings", userSettings);
        report.put("dataClassifications", userClassifications);
        report.put("consentGiven", userSettings.getOrDefault("consentGiven", false));
        report.put("dataMinimizationEnabled", userSettings.getOrDefault("dataMinimizationEnabled", true));
        report.put("auditLoggingEnabled", userSettings.getOrDefault("auditLoggingEnabled", true));
        
        return report;
    }

    /**
     * Clear privacy data for a user
     */
    public void clearPrivacyData(String userId) {
        privacySettings.remove(userId);
        dataClassifications.remove(userId);
    }
}
