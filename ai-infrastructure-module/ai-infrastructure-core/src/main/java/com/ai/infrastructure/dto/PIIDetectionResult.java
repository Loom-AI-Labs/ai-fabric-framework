package com.ai.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Aggregates the outcome of processing a payload through the PII detection layer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PIIDetectionResult {

    /**
     * Original payload that entered the detection pipeline.
     */
    private String originalQuery;

    /**
     * Processed payload forwarded to downstream layers. Depending on the active
     * {@link PIIMode}, this may be identical to {@link #originalQuery} (pass-through
     * or detect-only) or a redacted variant that masks sensitive spans.
     */
    private String processedQuery;

    /**
     * Indicates whether any PII markers were found in the original payload.
     */
    private boolean piiDetected;

    /**
     * Collection of PII detections that were identified during processing.
     */
    @Builder.Default
    private List<PIIDetection> detections = Collections.emptyList();

    /**
     * Processing mode applied for this detection cycle.
     */
    private PIIMode modeApplied;

    /**
     * Optional encrypted or hashed representation of the original payload when
     * {@code store-encrypted-original=true}. The module never persists the raw
     * payload outside the detection layer.
     */
    private String encryptedOriginalQuery;

    /**
     * Salt or IV value that accompanies {@link #encryptedOriginalQuery}. Consumers
     * should treat the value as opaque and only use it when decryption is
     * explicitly required.
     */
    private String encryptionSalt;

    /**
     * Timestamp indicating when the detection run completed.
     */
    @Builder.Default
    private Instant detectedAt = Instant.now();

    /**
     * Additional metadata that may be attached to the detection run (for audit
     * events, rate limiting, etc.).
     */
    @Builder.Default
    private Map<String, Object> metadata = Collections.emptyMap();

    /**
     * Convenience accessor to determine if any detections exist without exposing
     * the collection directly in business logic.
     *
     * @return {@code true} when at least one detection entry is present.
     */
    public boolean hasDetections() {
        return detections != null && !detections.isEmpty();
    }
}
