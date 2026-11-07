package ai.fabric.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Configuration settings governing the response sanitization layer.
 */
@Data
@Validated
@Slf4j
@ConfigurationProperties(prefix = "ai.response-sanitization")
public class ResponseSanitizationProperties {

    /**
     * Master switch for enabling/disabling sanitization.
     */
    private boolean enabled = true;

    /**
     * When sensitive data is detected, redact it even if the global PII mode is set to DETECT_ONLY.
     */
    private boolean forceRedaction = true;

    /**
     * Keys removed from payloads before presenting to end users.
     */
    private List<String> filteredDataKeys = List.of(
        "metadata",
        "ragResponse",
        "documents",
        "debug",
        "internalContext"
    );

    /**
     * High-risk PII types that trigger warning messages.
     */
    private Set<String> highRiskTypes = Set.of("CREDIT_CARD", "SSN", "API_KEY", "DB_PASSWORD");

    /**
     * Warning message appended when high-risk PII is detected in the generated response.
     */
    private String highRiskWarningMessage = "Sensitive information detected and redacted for your safety.";

    /**
     * Message displayed when medium-risk PII (e.g. email, phone) is sanitized.
     */
    private String mediumRiskWarningMessage = "Some personal information was redacted before showing this response.";

    /**
     * Indicates whether warnings should be surfaced when sanitization occurs.
     */
    private boolean warningEnabled = true;

    /**
     * Warning level values that downstream consumers can map to UI severity.
     */
    private String warningLevelHighRisk = "BLOCK";
    private String warningLevelMediumRisk = "WARN";

    /**
     * Optional guidance message encouraging users to use secure channels when PII is detected.
     */
    private boolean guidanceEnabled = true;
    private String guidanceMessage = "For sensitive requests, please use our secure support form.";

    /**
     * Generic replacement token used when the detection pattern does not provide one.
     */
    private String defaultReplacement = "[REDACTED]";

    /**
     * Limit for the total number of suggestions returned to the UI.
     */
    private int suggestionLimit = 3;

    /**
     * Whether to include error codes originating from action handlers in sanitized payloads.
     */
    private boolean includeErrorCodes = false;

    /**
     * Include metadata alongside suggestions indicating whether sanitization affected them.
     */
    private boolean includeSuggestionMetadata = true;

    /**
     * Publish application events whenever sanitization takes place. Useful for analytics dashboards.
     */
    private boolean publishEvents = true;

    public Set<String> normalizedHighRiskTypes() {
        return highRiskTypes.stream()
            .map(type -> type == null ? null : type.trim().toUpperCase(Locale.ROOT))
            .filter(type -> !type.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isHighRiskType(String type) {
        if (type == null) {
            return false;
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }

        Set<String> highRisk = normalizedHighRiskTypes();
        boolean match = highRisk.contains(normalized);
        if (!match) {
            match = highRisk.stream()
                .filter(candidate -> candidate != null && !candidate.isEmpty())
                .anyMatch(normalized::contains);
        }
        if (match) {
            log.debug("Detected high-risk type: {}", type);
        }
        return match;
    }
}
