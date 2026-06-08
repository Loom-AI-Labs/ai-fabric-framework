package com.ai.infrastructure.dto;

/**
 * Processing modes for the PII detection layer.
 *
 * <p>
 * The mode determines how the {@code PIIDetectionService} should behave when
 * potentially sensitive data is encountered in an input payload.
 * </p>
 */
public enum PIIMode {
    /**
     * Leave the payload untouched and skip detection logic entirely.
     */
    PASS_THROUGH,

    /**
     * Perform detection, return structured detection metadata, but do not redact
     * the original text.
     */
    DETECT_ONLY,

    /**
     * Perform detection and return a redacted version of the payload that removes
     * sensitive content before passing it to downstream layers.
     */
    REDACT
}
