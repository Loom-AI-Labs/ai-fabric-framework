package com.ai.infrastructure.privacy.pii;

import com.ai.infrastructure.dto.PIIDetectionResult;

/**
 * SPI for detecting and optionally redacting PII.
 *
 * <p>This lives in core so other core features (security, sanitization, history)
 * can depend on it without requiring the optional PII module at compile time.</p>
 */
public interface PIIDetectionService {

    /**
     * Detects PII and applies configured processing (e.g. redact, encrypt original).
     */
    PIIDetectionResult detectAndProcess(String query);

    /**
     * Detects PII without mutating the payload.
     */
    PIIDetectionResult analyze(String payload);
}

