package com.ai.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Structured representation of a single PII detection finding.
 *
 * <p>
 * The implementation deliberately avoids storing the raw matched value to ensure
 * sensitive information never leaves the detection layer. Instead, the masked
 * value conveys redaction context while complying with privacy requirements.
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PIIDetection {

    /**
     * Canonical type identifier for the detection (e.g. {@code CREDIT_CARD},
     * {@code EMAIL}, {@code PHONE}, {@code SSN}).
     */
    private String type;

    /**
     * Logical field name associated with the detection. This maps the detection
     * to configuration driven definitions such as {@code credit_card} or
     * {@code email}.
     */
    private String fieldName;

    /**
     * Start index (inclusive) of the sensitive segment within the original payload.
     */
    private int startIndex;

    /**
     * End index (exclusive) of the sensitive segment within the original payload.
     */
    private int endIndex;

    /**
     * Masked representation that will replace the sensitive span when redaction
     * is enabled. The masked value never contains the raw PII content.
     */
    private String maskedValue;

    /**
     * Confidence score for the detection (0.0 - 1.0). Static pattern detections
     * typically default to {@code 1.0}.
     */
    @Builder.Default
    private double confidence = 1.0d;

    /**
     * Optional contextual note describing the detection, useful for audit logs
     * without disclosing sensitive content.
     */
    private String contextNote;
}
