package com.ai.fabric.realapps.agenticresolver.agentic.event;

import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionRequest;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Deterministic application-event mapping. No model selects the specialist or
 * constructs trusted execution context.
 */
@Component
public final class PaymentVerificationFailedEventMapper {

    public AccountResolutionRequest map(
        PaymentVerificationFailedEvent event
    ) {
        Objects.requireNonNull(event, "event is required");
        return new AccountResolutionRequest((
            "An application PAYMENT_VERIFICATION_FAILED event occurred at %s "
                + "with failure code %s after attempt %d. Assess the current "
                + "account profile against the registered account policies. "
                + "Return the typed readiness assessment and safest next "
                + "step. Do not mutate the account."
            ).formatted(
                event.occurredAt(),
                event.failureCode(),
                event.attemptNumber()
            ));
    }
}
