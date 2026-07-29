package com.ai.fabric.realapps.agenticresolver.agentic.event;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.ExecutionHandle;
import ai.fabric.execution.specialist.client.SpecialistClient;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistExecutionSnapshot;
import ai.fabric.execution.specialist.client.SpecialistInvocation;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionRequest;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionResult;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolverSpecialists;
import com.ai.fabric.realapps.agenticresolver.agentic.AgenticResolverSessionService;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public final class ProactiveAccountEventService {

    public static final String EVENT_TYPE =
        "PAYMENT_VERIFICATION_FAILED";
    private static final String EVENT_CONSUMER =
        "agentic-account-resolver-event-consumer";
    private static final String IDEMPOTENCY_PREFIX =
        "account-payment-verification-failed:v1:";
    private static final Set<String> EVENT_SCOPES = Set.of(
        "specialist:account-resolver-read@1",
        "action:get_account_profile",
        "vector:account-resolution-policy"
    );

    private final SpecialistClient<
        AccountResolutionRequest,
        AccountResolutionResult
    > readClient;
    private final AgenticResolverSessionService sessionService;
    private final PaymentVerificationFailedEventMapper mapper;
    private final Clock clock;

    public ProactiveAccountEventService(
        SpecialistClientFactory specialistClientFactory,
        AgenticResolverSessionService sessionService,
        PaymentVerificationFailedEventMapper mapper,
        Clock clock
    ) {
        this.readClient = specialistClientFactory.bind(
            AccountResolverSpecialists.READ_SPECIALIST_ID,
            AccountResolutionRequest.class,
            AccountResolutionResult.class
        );
        this.sessionService = sessionService;
        this.mapper = mapper;
        this.clock = clock;
    }

    public ProactiveEventSubmission submit(
        String sessionId,
        PaymentVerificationFailedEvent event
    ) {
        if (event.occurredAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException(
                "event occurredAt must not be in the future"
            );
        }
        AgenticResolverSessionService.ActiveSession session =
            sessionService.active(sessionId);
        ExecutionHandle handle = readClient.submit(
            new SpecialistInvocation<>(
                mapper.map(event),
                trustedEventContext(
                    session,
                    "payment-event:" + event.eventId()
                ),
                null,
                null,
                idempotencyKey(event)
            )
        );
        return new ProactiveEventSubmission(
            event.eventId(),
            EVENT_TYPE,
            handle
        );
    }

    public Optional<SpecialistExecutionSnapshot<AccountResolutionResult>>
    find(String sessionId, String invocationId) {
        AgenticResolverSessionService.ActiveSession session =
            sessionService.active(sessionId);
        return readClient.find(
            invocationId,
            trustedEventContext(
                session,
                "payment-event-status:" + session.sessionId()
            )
        );
    }

    public boolean cancel(String sessionId, String invocationId) {
        AgenticResolverSessionService.ActiveSession session =
            sessionService.active(sessionId);
        return readClient.cancel(
            invocationId,
            trustedEventContext(
                session,
                "payment-event-cancel:" + session.sessionId()
            )
        );
    }

    private TrustedExecutionContext trustedEventContext(
        AgenticResolverSessionService.ActiveSession session,
        String correlationId
    ) {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                EVENT_CONSUMER,
                ExecutionPrincipalType.SERVICE
            ),
            new ExecutionSubjectRef(
                "account",
                session.subjectUserId().toString()
            ),
            ExecutionSource.EVENT,
            "public-demo",
            "agentic-ai-action-resolver",
            EVENT_SCOPES,
            correlationId,
            clock.instant()
        );
    }

    private String idempotencyKey(
        PaymentVerificationFailedEvent event
    ) {
        return IDEMPOTENCY_PREFIX + event.eventId();
    }
}
