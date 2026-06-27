package ai.fabric.config;

import ai.fabric.dto.PIIMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for the PII detection layer.
 *
 * <p>
 * Values are loaded from the {@code ai.pii-detection.*} namespace and provide a
 * comprehensive set of controls for pattern configuration, masking strategies,
 * and storage policies.
 * </p>
 */
@Data
@Validated
@NoArgsConstructor
@ConfigurationProperties(prefix = "ai.pii-detection")
public class PIIDetectionProperties {

    /**
     * Enable or disable the detection layer. Defaults to {@code false} to avoid
     * introducing processing overhead in environments where the layer is not
     * required.
     */
    private boolean enabled = false;

    /**
     * Operating mode for the detector.
     */
    private PIIMode mode = PIIMode.PASS_THROUGH;

    /**
     * Direction of PII detection: INPUT (user query only) or INPUT_OUTPUT (user query + LLM response).
     * Defaults to INPUT_OUTPUT for comprehensive security.
     */
    private PIIDetectionDirection detectionDirection = PIIDetectionDirection.INPUT_OUTPUT;

    /**
     * When {@code true}, the original payload is stored in encrypted form to
     * support downstream auditing requirements.
     */
    private boolean storeEncryptedOriginal = false;

    /**
     * When {@code true}, detection results may include the raw original payload in
     * {@code originalQuery} even when PII was detected.
     *
     * <p>Defaults to {@code false} so accidental result logging or serialization
     * does not expose sensitive text. Callers that need recoverability should prefer
     * {@link #storeEncryptedOriginal}.</p>
     */
    private boolean exposeOriginalPayloadInResult = false;

    /**
     * Optional secret used to derive an AES key for encrypting the original
     * payload. When absent, the detector stores a salted SHA-256 hash instead of
     * an encrypted payload.
     */
    private String encryptionSecret;

    /**
     * Controls whether detection events should emit audit log entries. Logging is
     * enabled by default but can be disabled for high-throughput environments.
     */
    private boolean auditLoggingEnabled = true;

    /**
     * List of logical field identifiers considered sensitive. This list informs
     * masking metadata and can be extended via configuration.
     */
    private List<String> sensitiveFields = List.of(
        "credit_card",
        "ssn",
        "phone_number",
        "email",
        "passport_number",
        "national_id"
    );

    /**
     * Map of named detection patterns. Defaults cover common PII types but can be
     * overridden or extended in {@code application.yml}.
     */
    private Map<String, PatternConfig> patterns = defaultPatterns();

    private static Map<String, PatternConfig> defaultPatterns() {
        Map<String, PatternConfig> defaults = new LinkedHashMap<>();
        defaults.put("CREDIT_CARD", PatternConfig.builder()
            .fieldName("credit_card")
            .regex("(?<!\\d)(?:\\d[ -]?){13,16}(?!\\d)")
            .replacement("****-****-****-****")
            .enabled(true)
            .confidence(1.0d)
            .contextNote("Potential payment card number redacted")
            .build());
        defaults.put("EMAIL", PatternConfig.builder()
            .fieldName("email")
            .regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}")
            .replacement("***@***.***")
            .enabled(true)
            .confidence(1.0d)
            .contextNote("Email address redacted")
            .build());
        defaults.put("PHONE", PatternConfig.builder()
            .fieldName("phone_number")
            .regex("(?:(?:\\+?\\d{1,3}[\\s.-]?)?(?:\\(\\d{3}\\)|\\d{3})[\\s.-]?\\d{3}[\\s.-]?\\d{4})")
            .replacement("***-***-****")
            .enabled(true)
            .confidence(1.0d)
            .contextNote("Phone number redacted")
            .build());
        defaults.put("SSN", PatternConfig.builder()
            .fieldName("ssn")
            .regex("\\b\\d{3}-?\\d{2}-?\\d{4}\\b")
            .replacement("***-**-****")
            .enabled(true)
            .confidence(1.0d)
            .contextNote("Social security number redacted")
            .build());
        defaults.put("IBAN", PatternConfig.builder()
            .fieldName("iban")
            .regex("\\b[A-Z]{2}\\d{2}[A-Z0-9]{10,30}\\b")
            .replacement("****IBAN****")
            .enabled(false)
            .confidence(0.85d)
            .contextNote("International bank account number redacted")
            .build());
        return defaults;
    }

    /**
     * Individual pattern configuration entry.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PatternConfig {

        /**
         * Logical field name associated with detected matches.
         */
        private String fieldName;

        /**
         * Regular expression used to identify sensitive substrings.
         */
        private String regex;

        /**
         * Replacement text applied during redaction. Supports Java regex
         * replacement syntax.
         */
        @Builder.Default
        private String replacement = "***";

        /**
         * Optional note recorded in detection results for audit and debugging.
         */
        private String contextNote;

        /**
         * Whether the pattern is active.
         */
        @Builder.Default
        private boolean enabled = true;

        /**
         * Confidence score assigned to detections produced by this pattern.
         */
        @Builder.Default
        private double confidence = 1.0d;
    }

    /**
     * Enum to control the directional scope of PII detection.
     */
    public enum PIIDetectionDirection {
        /**
         * Detect PII only in user input queries (detect & redact BEFORE sending to LLM).
         */
        INPUT,

        /**
         * Detect PII in both user input AND LLM response (comprehensive security).
         */
        INPUT_OUTPUT
    }
}
