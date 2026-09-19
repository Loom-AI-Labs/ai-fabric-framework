package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.chain.SpecialistChainExecutionRequest;
import ai.fabric.execution.chain.SpecialistChainExecutionResult;
import ai.fabric.execution.chain.SpecialistChainExecutionSnapshot;
import ai.fabric.execution.chain.SpecialistChainGateway;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.ConversationBinding;
import java.time.Clock;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
    name = "ai.execution.specialist-chains.enabled",
    havingValue = "true"
)
public class AccountSmartResolutionService {

    private static final Set<String> CHAIN_SCOPES = Set.of(
        "specialist:account-resolution-chain-manager@1",
        "specialist:account-resolver-manager-read@1",
        "specialist:billing-resolution-manager-advisor@1",
        "action:get_account_profile",
        "action:assess_billing_resolution",
        "vector:account-resolution-policy"
    );

    private final SpecialistChainGateway gateway;
    private final AgenticResolverSessionService sessions;
    private final Clock clock;

    public AccountSmartResolutionService(
        SpecialistChainGateway gateway,
        AgenticResolverSessionService sessions,
        Clock clock
    ) {
        this.gateway = gateway;
        this.sessions = sessions;
        this.clock = clock;
    }

    public AccountSmartResolutionView resolve(
        String sessionId,
        AccountDelegationCoordinatorRequest input,
        String idempotencyKey
    ) {
        return AccountSmartResolutionView.from(gateway.execute(
            request(sessionId, input, idempotencyKey)
        ));
    }

    public AccountSmartResolutionExecutionView submit(
        String sessionId,
        AccountDelegationCoordinatorRequest input,
        String idempotencyKey
    ) {
        SpecialistChainExecutionRequest<AccountDelegationCoordinatorRequest>
            request = request(
            sessionId,
            input,
            idempotencyKey
        );
        var handle = gateway.submit(request);
        if (!handle.replayed() || !handle.status().terminal()) {
            return AccountSmartResolutionExecutionView.from(handle);
        }
        SpecialistChainExecutionSnapshot snapshot = gateway.find(
            handle.executionId(),
            request.trustedExecutionContext()
        ).orElseThrow(() -> new IllegalStateException(
            "The replayed smart account-resolution state is unavailable"
        ));
        SpecialistChainExecutionResult result = gateway.findResult(
            handle.executionId(),
            request.trustedExecutionContext()
        ).orElseThrow(() -> new IllegalStateException(
            "The replayed smart account-resolution result is unavailable"
        ));
        return AccountSmartResolutionExecutionView.from(
            snapshot,
            result.asReplayed()
        );
    }

    public AccountSmartResolutionExecutionView status(
        String sessionId,
        String executionId
    ) {
        TrustedExecutionContext context = trustedContext(
            sessions.active(sessionId)
        );
        SpecialistChainExecutionSnapshot snapshot = gateway.find(
            executionId,
            context
        ).orElseThrow(() -> new IllegalArgumentException(
            "Smart account-resolution execution was not found"
        ));
        SpecialistChainExecutionResult result = snapshot.status().terminal()
            ? gateway.findResult(executionId, context).orElse(null)
            : null;
        if (snapshot.status().terminal() && result == null) {
            throw new IllegalStateException(
                "The terminal smart account-resolution result could not be verified"
            );
        }
        return AccountSmartResolutionExecutionView.from(snapshot, result);
    }

    public AccountSmartResolutionExecutionView cancel(
        String sessionId,
        String executionId
    ) {
        TrustedExecutionContext context = trustedContext(
            sessions.active(sessionId)
        );
        if (!gateway.cancel(executionId, context)) {
            throw new IllegalArgumentException(
                "Smart account-resolution execution is missing or already terminal"
            );
        }
        return status(sessionId, executionId);
    }

    private SpecialistChainExecutionRequest<AccountDelegationCoordinatorRequest>
        request(
            String sessionId,
            AccountDelegationCoordinatorRequest input,
            String idempotencyKey
        ) {
        AgenticResolverSessionService.ActiveSession session =
            sessions.active(sessionId);
        return new SpecialistChainExecutionRequest<>(
            AccountSpecialistChains.SMART_RESOLUTION,
            input,
            trustedContext(session),
            new ConversationBinding(
                session.conversationOwnerId(),
                session.conversationId()
            ),
            null,
            requireIdempotencyKey(idempotencyKey)
        );
    }

    private TrustedExecutionContext trustedContext(
        AgenticResolverSessionService.ActiveSession session
    ) {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                session.conversationOwnerId(),
                ExecutionPrincipalType.END_USER
            ),
            new ExecutionSubjectRef(
                "account",
                session.subjectUserId().toString()
            ),
            ExecutionSource.INTERACTIVE,
            "public-demo",
            "agentic-ai-action-resolver",
            CHAIN_SCOPES,
            null,
            clock.instant()
        );
    }

    private String requireIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        String normalized = value.trim();
        if (normalized.length() > 160) {
            throw new IllegalArgumentException(
                "Idempotency-Key must not exceed 160 characters"
            );
        }
        return normalized;
    }
}
