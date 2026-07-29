package com.ai.fabric.realapps.agenticresolver.agentic.event;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Objects;

/**
 * Raw application event. Trusted identity and execution policy are
 * deliberately absent.
 */
public record PaymentVerificationFailedEvent(
    @NotBlank
    @Size(max = 120)
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*")
    String eventId,
    @NotNull PaymentVerificationFailureCode failureCode,
    @Min(1) @Max(20) int attemptNumber,
    @NotNull @PastOrPresent Instant occurredAt
) {
    private static final java.util.regex.Pattern EVENT_ID =
        java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");

    public PaymentVerificationFailedEvent {
        eventId = eventId != null ? eventId.trim() : null;
        if (eventId == null
            || eventId.isEmpty()
            || eventId.length() > 120
            || !EVENT_ID.matcher(eventId).matches()) {
            throw new IllegalArgumentException("eventId is invalid");
        }
        Objects.requireNonNull(failureCode, "failureCode is required");
        if (attemptNumber < 1 || attemptNumber > 20) {
            throw new IllegalArgumentException(
                "attemptNumber must be between 1 and 20"
            );
        }
        Objects.requireNonNull(occurredAt, "occurredAt is required");
    }
}
