package com.ai.fabric.realapps.incident.service;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.delegation.SpecialistDelegationGateway;
import ai.fabric.execution.delegation.SpecialistDelegationRequest;
import ai.fabric.execution.delegation.SpecialistDelegationResult;
import ai.fabric.execution.gateway.AIExecutionFailure;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.gateway.ExecutionHandle;
import ai.fabric.execution.gateway.ExecutionHandleStatus;
import ai.fabric.execution.handoff.SpecialistHandoffGateway;
import ai.fabric.execution.handoff.SpecialistHandoffRequest;
import ai.fabric.execution.handoff.SpecialistHandoffResult;
import ai.fabric.execution.plan.AIExecutionCoordinator;
import ai.fabric.execution.plan.PlanExecutionRequest;
import ai.fabric.execution.plan.PlanExecutionResult;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.client.SpecialistClient;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistExecutionSnapshot;
import ai.fabric.execution.specialist.client.SpecialistInvocation;
import com.ai.fabric.realapps.incident.domain.ChangeRiskFinding;
import com.ai.fabric.realapps.incident.domain.ChangeRiskRequest;
import com.ai.fabric.realapps.incident.domain.IncidentAssessment;
import com.ai.fabric.realapps.incident.domain.IncidentPlanComparison;
import com.ai.fabric.realapps.incident.domain.IncidentPlanRequest;
import com.ai.fabric.realapps.incident.domain.IncidentRoutingDecision;
import com.ai.fabric.realapps.incident.domain.IncidentRoutingRequest;
import com.ai.fabric.realapps.incident.domain.IncidentTransitionResponse;
import com.ai.fabric.realapps.incident.domain.ServiceHealthFinding;
import com.ai.fabric.realapps.incident.domain.ServiceHealthRequest;
import com.ai.fabric.realapps.incident.execution.IncidentPlans;
import com.ai.fabric.realapps.incident.execution.IncidentSpecialists;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Service;

@Service
public class IncidentExecutionService {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(45);
    private static final long POLL_NANOS = Duration.ofMillis(40).toNanos();
    private static final Set<String> EXECUTION_SCOPES = Set.of(
        "specialist:service-health-reader@1",
        "specialist:change-risk-reader@1",
        "specialist:incident-intake@1",
        "specialist:incident-conversation-manager@1"
    );

    private final AIExecutionCoordinator coordinator;
    private final SpecialistClient<IncidentRoutingRequest, IncidentRoutingDecision>
        intakeClient;
    private final SpecialistDelegationGateway delegationGateway;
    private final SpecialistHandoffGateway handoffGateway;
    private final IncidentSessionService sessions;
    private final Clock clock;

    public IncidentExecutionService(
        AIExecutionCoordinator coordinator,
        SpecialistClientFactory clients,
        SpecialistDelegationGateway delegationGateway,
        SpecialistHandoffGateway handoffGateway,
        IncidentSessionService sessions,
        Clock clock
    ) {
        this.coordinator = coordinator;
        this.intakeClient = clients.bind(
            IncidentSpecialists.INTAKE,
            IncidentRoutingRequest.class,
            IncidentRoutingDecision.class
        );
        this.delegationGateway = delegationGateway;
        this.handoffGateway = handoffGateway;
        this.sessions = sessions;
        this.clock = clock;
    }

    public PlanExecutionResult<IncidentAssessment> executePlan(
        String sessionId,
        String mode,
        String question,
        String idempotencyKey
    ) {
        IncidentPlanRequest input = sessions.planRequest(sessionId, question);
        return coordinator.execute(new PlanExecutionRequest<>(
            "parallel".equalsIgnoreCase(mode)
                ? IncidentPlans.PARALLEL
                : IncidentPlans.SEQUENTIAL,
            input,
            trustedContext(sessions.active(sessionId)),
            null,
            requireIdempotencyKey(idempotencyKey)
        ));
    }

    public IncidentPlanComparison compare(
        String sessionId,
        String question,
        String idempotencyKey
    ) {
        String key = requireIdempotencyKey(idempotencyKey);
        PlanExecutionResult<IncidentAssessment> sequential = executePlan(
            sessionId,
            "sequential",
            question,
            key + ":sequential"
        );
        PlanExecutionResult<IncidentAssessment> parallel = executePlan(
            sessionId,
            "parallel",
            question,
            key + ":parallel"
        );
        boolean equivalent = equivalent(sequential, parallel);
        String reason = equivalent
            ? "Both application-declared plans produced the same typed severity, health, change risk, source revision, and citation set."
            : "The plans did not both produce the same approved typed outcome; inspect their explicit traces or failures.";
        return new IncidentPlanComparison(
            sequential,
            parallel,
            equivalent,
            reason
        );
    }

    public IncidentTransitionResponse delegate(
        String sessionId,
        String question,
        String idempotencyKey
    ) {
        return transition(
            sessionId,
            question,
            requireIdempotencyKey(idempotencyKey),
            false
        );
    }

    public IncidentTransitionResponse handoff(
        String sessionId,
        String question,
        String idempotencyKey
    ) {
        return transition(
            sessionId,
            question,
            requireIdempotencyKey(idempotencyKey),
            true
        );
    }

    private IncidentTransitionResponse transition(
        String sessionId,
        String question,
        String idempotencyKey,
        boolean handoff
    ) {
        IncidentSessionService.ActiveSession session = sessions.active(sessionId);
        IncidentPlanRequest incident = sessions.planRequest(sessionId, question);
        TrustedExecutionContext context = trustedContext(session);
        IncidentRoutingRequest routingRequest = new IncidentRoutingRequest(
            question,
            handoff ? "HANDOFF" : "DELEGATION",
            incident
        );
        AIExecutionResult<IncidentRoutingDecision> intake = await(
            intakeClient,
            new SpecialistInvocation<>(
                routingRequest,
                context,
                null,
                null,
                idempotencyKey + ":intake"
            ),
            IncidentSpecialists.INTAKE
        );
        if (!intake.succeeded()
            || intake.output().decision()
                != IncidentRoutingDecision.Decision.ROUTE) {
            return new IncidentTransitionResponse(intake, null, null);
        }

        SpecialistId target = SpecialistId.parse(
            intake.output().targetSpecialist()
        );
        if (target.equals(IncidentSpecialists.SERVICE_HEALTH)) {
            ServiceHealthRequest targetInput = new ServiceHealthRequest(
                question,
                incident.incidentId(),
                incident.deploymentId(),
                incident.sourceRevision(),
                incident.serviceEvidence()
            );
            return handoff
                ? handoffHealth(intake, targetInput, incident, context, idempotencyKey)
                : delegateHealth(intake, targetInput, incident, context, idempotencyKey);
        }
        if (target.equals(IncidentSpecialists.CHANGE_RISK)) {
            ChangeRiskRequest targetInput = new ChangeRiskRequest(
                question,
                incident.incidentId(),
                incident.deploymentId(),
                incident.sourceRevision(),
                incident.changeEvidence()
            );
            return handoff
                ? handoffChange(intake, targetInput, incident, context, idempotencyKey)
                : delegateChange(intake, targetInput, incident, context, idempotencyKey);
        }
        throw new IllegalStateException(
            "Validated incident intake selected an unsupported target"
        );
    }

    private IncidentTransitionResponse delegateHealth(
        AIExecutionResult<IncidentRoutingDecision> intake,
        ServiceHealthRequest input,
        IncidentPlanRequest incident,
        TrustedExecutionContext context,
        String key
    ) {
        SpecialistDelegationResult<IncidentRoutingDecision, ServiceHealthFinding>
            first = delegationGateway.delegate(
                new SpecialistDelegationRequest<>(
                    intake,
                    IncidentSpecialists.SERVICE_HEALTH,
                    input,
                    context,
                    null,
                    key + ":target"
                ),
                ServiceHealthRequest.class,
                ServiceHealthFinding.class
            );
        Object second = first.targetExecution() == null
            ? null
            : delegationGateway.delegate(
                new SpecialistDelegationRequest<>(
                    first.targetExecution(),
                    IncidentSpecialists.CHANGE_RISK,
                    changeRequest(incident),
                    context,
                    null,
                    key + ":second-hop"
                ),
                ChangeRiskRequest.class,
                ChangeRiskFinding.class
            );
        return new IncidentTransitionResponse(intake, first, second);
    }

    private IncidentTransitionResponse delegateChange(
        AIExecutionResult<IncidentRoutingDecision> intake,
        ChangeRiskRequest input,
        IncidentPlanRequest incident,
        TrustedExecutionContext context,
        String key
    ) {
        SpecialistDelegationResult<IncidentRoutingDecision, ChangeRiskFinding>
            first = delegationGateway.delegate(
                new SpecialistDelegationRequest<>(
                    intake,
                    IncidentSpecialists.CHANGE_RISK,
                    input,
                    context,
                    null,
                    key + ":target"
                ),
                ChangeRiskRequest.class,
                ChangeRiskFinding.class
            );
        Object second = first.targetExecution() == null
            ? null
            : delegationGateway.delegate(
                new SpecialistDelegationRequest<>(
                    first.targetExecution(),
                    IncidentSpecialists.SERVICE_HEALTH,
                    serviceRequest(incident),
                    context,
                    null,
                    key + ":second-hop"
                ),
                ServiceHealthRequest.class,
                ServiceHealthFinding.class
            );
        return new IncidentTransitionResponse(intake, first, second);
    }

    private IncidentTransitionResponse handoffHealth(
        AIExecutionResult<IncidentRoutingDecision> intake,
        ServiceHealthRequest input,
        IncidentPlanRequest incident,
        TrustedExecutionContext context,
        String key
    ) {
        SpecialistHandoffResult<IncidentRoutingDecision, ServiceHealthFinding>
            first = handoffGateway.handoff(
                new SpecialistHandoffRequest<>(
                    intake,
                    IncidentSpecialists.SERVICE_HEALTH,
                    input,
                    context,
                    null,
                    key + ":target"
                ),
                ServiceHealthRequest.class,
                ServiceHealthFinding.class
            );
        Object second = first.successorExecution() == null
            ? null
            : handoffGateway.handoff(
                new SpecialistHandoffRequest<>(
                    first.successorExecution(),
                    IncidentSpecialists.CHANGE_RISK,
                    changeRequest(incident),
                    context,
                    null,
                    key + ":second-hop"
                ),
                ChangeRiskRequest.class,
                ChangeRiskFinding.class
            );
        return new IncidentTransitionResponse(intake, first, second);
    }

    private IncidentTransitionResponse handoffChange(
        AIExecutionResult<IncidentRoutingDecision> intake,
        ChangeRiskRequest input,
        IncidentPlanRequest incident,
        TrustedExecutionContext context,
        String key
    ) {
        SpecialistHandoffResult<IncidentRoutingDecision, ChangeRiskFinding>
            first = handoffGateway.handoff(
                new SpecialistHandoffRequest<>(
                    intake,
                    IncidentSpecialists.CHANGE_RISK,
                    input,
                    context,
                    null,
                    key + ":target"
                ),
                ChangeRiskRequest.class,
                ChangeRiskFinding.class
            );
        Object second = first.successorExecution() == null
            ? null
            : handoffGateway.handoff(
                new SpecialistHandoffRequest<>(
                    first.successorExecution(),
                    IncidentSpecialists.SERVICE_HEALTH,
                    serviceRequest(incident),
                    context,
                    null,
                    key + ":second-hop"
                ),
                ServiceHealthRequest.class,
                ServiceHealthFinding.class
            );
        return new IncidentTransitionResponse(intake, first, second);
    }

    private ServiceHealthRequest serviceRequest(IncidentPlanRequest incident) {
        return new ServiceHealthRequest(
            incident.question(),
            incident.incidentId(),
            incident.deploymentId(),
            incident.sourceRevision(),
            incident.serviceEvidence()
        );
    }

    private ChangeRiskRequest changeRequest(IncidentPlanRequest incident) {
        return new ChangeRiskRequest(
            incident.question(),
            incident.incidentId(),
            incident.deploymentId(),
            incident.sourceRevision(),
            incident.changeEvidence()
        );
    }

    private boolean equivalent(
        PlanExecutionResult<IncidentAssessment> first,
        PlanExecutionResult<IncidentAssessment> second
    ) {
        if (!first.succeeded() || !second.succeeded()) {
            return false;
        }
        IncidentAssessment a = first.output();
        IncidentAssessment b = second.output();
        return a.sourceRevision().equals(b.sourceRevision())
            && a.severity().equals(b.severity())
            && a.healthStatus().equals(b.healthStatus())
            && a.changeRisk().equals(b.changeRisk())
            && new HashSet<>(a.evidenceIds())
                .equals(new HashSet<>(b.evidenceIds()));
    }

    private TrustedExecutionContext trustedContext(
        IncidentSessionService.ActiveSession session
    ) {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                "incident-demo-service:" + session.ownerId(),
                ExecutionPrincipalType.SERVICE
            ),
            new ExecutionSubjectRef(
                "incident",
                session.scenario().id()
            ),
            ExecutionSource.APPLICATION,
            "public-demo",
            "incident-investigation-room",
            EXECUTION_SCOPES,
            null,
            clock.instant()
        );
    }

    private <I, O> AIExecutionResult<O> await(
        SpecialistClient<I, O> client,
        SpecialistInvocation<I> invocation,
        SpecialistId specialistId
    ) {
        ExecutionHandle submitted = client.submit(invocation);
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<SpecialistExecutionSnapshot<O>> snapshot = client.find(
                submitted.invocationId(),
                invocation.trustedExecutionContext()
            );
            if (snapshot.isPresent()) {
                SpecialistExecutionSnapshot<O> value = snapshot.get();
                if (value.result() != null) {
                    return value.result();
                }
                if (terminal(value.handle().status())) {
                    return infrastructureFailure(
                        value.handle(),
                        specialistId,
                        "Incident intake ended without a result."
                    );
                }
            }
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                break;
            }
            LockSupport.parkNanos(POLL_NANOS);
        }
        client.cancel(
            submitted.invocationId(),
            invocation.trustedExecutionContext()
        );
        return infrastructureFailure(
            submitted,
            specialistId,
            "Incident intake exceeded the application wait limit."
        );
    }

    private boolean terminal(ExecutionHandleStatus status) {
        return status == ExecutionHandleStatus.SUCCEEDED
            || status == ExecutionHandleStatus.FAILED
            || status == ExecutionHandleStatus.CANCELLED
            || status == ExecutionHandleStatus.REJECTED
            || status == ExecutionHandleStatus.EXPIRED;
    }

    private <O> AIExecutionResult<O> infrastructureFailure(
        ExecutionHandle handle,
        SpecialistId specialistId,
        String message
    ) {
        Instant now = clock.instant();
        return new AIExecutionResult<>(
            handle.invocationId(),
            specialistId,
            AIExecutionStatus.FAILED,
            null,
            java.util.List.of(),
            java.util.Map.of("phase", "incident-intake"),
            new AIExecutionFailure(
                handle.failureReason() == null
                    ? "INCIDENT_INTAKE_UNAVAILABLE"
                    : handle.failureReason(),
                message,
                false
            ),
            now,
            now
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
