package com.ai.fabric.realapps.agenticresolver.agentic.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PaymentVerificationFailedEventMapperTest {

    @Test
    void mapsRawEventFactsWithoutTrustedExecutionFields() {
        PaymentVerificationFailedEvent event =
            new PaymentVerificationFailedEvent(
                "payment-event-42",
                PaymentVerificationFailureCode.DECLINED,
                2,
                Instant.parse("2026-07-29T10:00:00Z")
            );

        var request = new PaymentVerificationFailedEventMapper().map(event);

        assertThat(request.question())
            .contains("PAYMENT_VERIFICATION_FAILED")
            .contains("DECLINED")
            .contains("attempt 2")
            .contains("Do not mutate the account");
        assertThat(Arrays.stream(
            PaymentVerificationFailedEvent.class.getRecordComponents()
        ).map(component -> component.getName()).toList())
            .containsExactly(
                "eventId",
                "failureCode",
                "attemptNumber",
                "occurredAt"
            );
    }

    @Test
    void rejectsInvalidRawEventIdentityAndAttempt() {
        assertThatThrownBy(() ->
            new PaymentVerificationFailedEvent(
                "../unsafe",
                PaymentVerificationFailureCode.DECLINED,
                1,
                Instant.EPOCH
            )
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            new PaymentVerificationFailedEvent(
                "event-1",
                PaymentVerificationFailureCode.DECLINED,
                0,
                Instant.EPOCH
            )
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
