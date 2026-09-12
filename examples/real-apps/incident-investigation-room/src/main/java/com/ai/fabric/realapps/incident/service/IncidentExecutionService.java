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
import com.ai.fabric.realapps.incident.domain.ChangeRiskInvestigationFinding;
import com.ai.fabric.realapps.incident.domain.ChangeRiskInvestigationRequest;
import com.ai.fabric.realapps.incident.domain.IncidentFailureView;
import com.ai.fabric.realapps.incident.domain.IncidentInvestigationAssessment;
import com.ai.fabric.realapps.incident.domain.IncidentInvestigationPlanComparison;
import com.ai.fabric.realapps.incident.domain.IncidentPlanRequest;
import com.ai.fabric.realapps.incident.domain.IncidentPlanRunView;
import com.ai.fabric.realapps.incident.domain.IncidentPlanStepView;
import com.ai.fabric.realapps.incident.domain.IncidentRoutingDecision;
import com.ai.fabric.realapps.incident.domain.IncidentRoutingRequest;
import com.ai.fabric.realapps.incident.domain.IncidentRoutingScope;
import com.ai.fabric.realapps.incident.domain.IncidentSpecialistExecutionView;
import com.ai.fabric.realapps.incident.domain.IncidentSpecialistTrace;
import com.ai.fabric.realapps.incident.domain.IncidentTransitionResponse;
import com.ai.fabric.realapps.incident.domain.IncidentTransitionView;
import com.ai.fabric.realapps.incident.domain.ServiceHealthInvestigationFinding;
import com.ai.fabric.realapps.incident.domain.ServiceHealthInvestigationRequest;
import com.ai.fabric.realapps.incident.execution.IncidentPlans;
import com.ai.fabric.realapps.incident.execution.IncidentSpecialists;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Service;

@Service
public class IncidentExecutionService {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(45);
    private static final long POLL_NANOS = Duration.ofMillis(40).toNanos();
    private static final Set<String> EXECUTION_SCOPES = Set.of(
        "specialist:service-health-reader@2",
        "specialist:change-risk-reader@2",
        "specialist:incident-intake@2",
        "specialist:incident-conversation-manager@2",
        "action:read_service_metrics",
        "action:read_incident_alerts",
        "action:read_recent_deployments",
        "action:read_change_approvals",
        "vector:incident-runbook"
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
            IncidentSpecialists.INTAKE_V2,
            IncidentRoutingRequest.class,
            IncidentRoutingDecision.class
        );
        this.delegationGateway = delegationGateway;
        this.handoffGateway = handoffGateway;
        this.sessions = sessions;
        this.clock = clock;
    }

    public IncidentPlanRunView executePlan(
        String sessionId,
        String mode,
        String question,
        String idempotencyKey
    ) {
        return planView(executeRawPlan(
            sessionId,
            mode,
            question,
            idempotencyKey
        ));
    }

    private PlanExecutionResult<IncidentInvestigationAssessment> executeRawPlan(
        String sessionId,
        String mode,
        String question,
        String idempotencyKey
    ) {
        IncidentPlanRequest input = sessions.planRequest(sessionId, question);
        return coordinator.execute(new PlanExecutionRequest<>(
            "parallel".equalsIgnoreCase(mode)
                ? IncidentPlans.PARALLEL_V2
                : IncidentPlans.SEQUENTIAL_V2,
            input,
            trustedContext(sessions.active(sessionId)),
            null,
            requireIdempotencyKey(idempotencyKey)
        ));
    }

    public IncidentInvestigationPlanComparison compare(
        String sessionId,
        String question,
        String idempotencyKey
    ) {
        String key = requireIdempotencyKey(idempotencyKey);
        PlanExecutionResult<IncidentInvestigationAssessment> sequential = executeRawPlan(
            sessionId,
            "sequential",
            question,
            key + ":sequential"
        );
        PlanExecutionResult<IncidentInvestigationAssessment> parallel = executeRawPlan(
            sessionId,
            "parallel",
            question,
            key + ":parallel"
        );
        boolean equivalent = equivalent(sequential, parallel);
        String reason = equivalent
            ? "Both application-declared plans produced the same typed severity, health, change risk, source revision, and citation set."
            : "The plans did not both produce the same approved typed outcome; inspect their explicit traces or failures.";
        return new IncidentInvestigationPlanComparison(
            planView(sequential),
            planView(parallel),
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
            new IncidentRoutingScope(
                incident.incidentId(),
                incident.deploymentId(),
                incident.sourceRevision()
            )
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
            IncidentSpecialists.INTAKE_V2
        );
        if (!intake.succeeded()
            || intake.output().decision()
                != IncidentRoutingDecision.Decision.ROUTE) {
            return new IncidentTransitionResponse(
                executionView(intake),
                null,
                null
            );
        }

        SpecialistId target = SpecialistId.parse(
            intake.output().targetSpecialist()
        );
        if (target.equals(IncidentSpecialists.SERVICE_HEALTH_V2)) {
            ServiceHealthInvestigationRequest targetInput = new ServiceHealthInvestigationRequest(
                question,
                incident.incidentId(),
                incident.deploymentId(),
                incident.sourceRevision()
            );
            return handoff
                ? handoffHealth(intake, targetInput, incident, context, idempotencyKey)
                : delegateHealth(intake, targetInput, incident, context, idempotencyKey);
        }
        if (target.equals(IncidentSpecialists.CHANGE_RISK_V2)) {
            ChangeRiskInvestigationRequest targetInput = new ChangeRiskInvestigationRequest(
                question,
                incident.incidentId(),
                incident.deploymentId(),
                incident.sourceRevision()
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
        ServiceHealthInvestigationRequest input,
        IncidentPlanRequest incident,
        TrustedExecutionContext context,
        String key
    ) {
        SpecialistDelegationResult<IncidentRoutingDecision, ServiceHealthInvestigationFinding>
            first = delegationGateway.delegate(
                new SpecialistDelegationRequest<>(
                    intake,
                    IncidentSpecialists.SERVICE_HEALTH_V2,
                    input,
                    context,
                    null,
                    key + ":target"
                ),
                ServiceHealthInvestigationRequest.class,
                ServiceHealthInvestigationFinding.class
            );
        Object second = first.targetExecution() == null
            ? null
            : delegationGateway.delegate(
                new SpecialistDelegationRequest<>(
                    first.targetExecution(),
                    IncidentSpecialists.CHANGE_RISK_V2,
                    changeRequest(incident),
                    context,
                    null,
                    key + ":second-hop"
                ),
                ChangeRiskInvestigationRequest.class,
                ChangeRiskInvestigationFinding.class
            );
        return transitionResponse(intake, first, second);
    }

    private IncidentTransitionResponse delegateChange(
        AIExecutionResult<IncidentRoutingDecision> intake,
        ChangeRiskInvestigationRequest input,
        IncidentPlanRequest incident,
        TrustedExecutionContext context,
        String key
    ) {
        SpecialistDelegationResult<IncidentRoutingDecision, ChangeRiskInvestigationFinding>
            first = delegationGateway.delegate(
                new SpecialistDelegationRequest<>(
                    intake,
                    IncidentSpecialists.CHANGE_RISK_V2,
                    input,
                    context,
                    null,
                    key + ":target"
                ),
                ChangeRiskInvestigationRequest.class,
                ChangeRiskInvestigationFinding.class
            );
        Object second = first.targetExecution() == null
            ? null
            : delegationGateway.delegate(
                new SpecialistDelegationRequest<>(
                    first.targetExecution(),
                    IncidentSpecialists.SERVICE_HEALTH_V2,
                    serviceRequest(incident),
                    context,
                    null,
                    key + ":second-hop"
                ),
                ServiceHealthInvestigationRequest.class,
                ServiceHealthInvestigationFinding.class
            );
        return transitionResponse(intake, first, second);
    }

    private IncidentTransitionResponse handoffHealth(
        AIExecutionResult<IncidentRoutingDecision> intake,
        ServiceHealthInvestigationRequest input,
        IncidentPlanRequest incident,
        TrustedExecutionContext context,
        String key
    ) {
        SpecialistHandoffResult<IncidentRoutingDecision, ServiceHealthInvestigationFinding>
            first = handoffGateway.handoff(
                new SpecialistHandoffRequest<>(
                    intake,
                    IncidentSpecialists.SERVICE_HEALTH_V2,
                    input,
                    context,
                    null,
                    key + ":target"
                ),
                ServiceHealthInvestigationRequest.class,
                ServiceHealthInvestigationFinding.class
            );
        Object second = first.successorExecution() == null
            ? null
            : handoffGateway.handoff(
                new SpecialistHandoffRequest<>(
                    first.successorExecution(),
                    IncidentSpecialists.CHANGE_RISK_V2,
                    changeRequest(incident),
                    context,
                    null,
                    key + ":second-hop"
                ),
                ChangeRiskInvestigationRequest.class,
                ChangeRiskInvestigationFinding.class
            );
        return transitionResponse(intake, first, second);
    }

    private IncidentTransitionResponse handoffChange(
        AIExecutionResult<IncidentRoutingDecision> intake,
        ChangeRiskInvestigationRequest input,
        IncidentPlanRequest incident,
        TrustedExecutionContext context,
        String key
    ) {
        SpecialistHandoffResult<IncidentRoutingDecision, ChangeRiskInvestigationFinding>
            first = handoffGateway.handoff(
                new SpecialistHandoffRequest<>(
                    intake,
                    IncidentSpecialists.CHANGE_RISK_V2,
                    input,
                    context,
                    null,
                    key + ":target"
                ),
                ChangeRiskInvestigationRequest.class,
                ChangeRiskInvestigationFinding.class
            );
        Object second = first.successorExecution() == null
            ? null
            : handoffGateway.handoff(
                new SpecialistHandoffRequest<>(
                    first.successorExecution(),
                    IncidentSpecialists.SERVICE_HEALTH_V2,
                    serviceRequest(incident),
                    context,
                    null,
                    key + ":second-hop"
                ),
                ServiceHealthInvestigationRequest.class,
                ServiceHealthInvestigationFinding.class
            );
        return transitionResponse(intake, first, second);
    }

    private IncidentPlanRunView planView(
        PlanExecutionResult<IncidentInvestigationAssessment> result
    ) {
        IncidentInvestigationAssessment output = result.output();
        List<IncidentPlanStepView> steps = result.steps().stream()
            .map(step -> new IncidentPlanStepView(
                step.stepId(),
                step.parallelGroupId(),
                step.sourceRevision(),
                step.specialistId().toString(),
                step.invocationId(),
                step.status().name(),
                step.startedAt(),
                step.completedAt(),
                output == null ? null : traceForStep(output, step.stepId())
            ))
            .toList();
        IncidentFailureView failure = result.failure() == null
            ? null
            : new IncidentFailureView(
                result.failure().reason(),
                result.failure().publicMessage(),
                result.failure().retryable(),
                result.failure().stepId()
            );
        return new IncidentPlanRunView(
            result.executionId(),
            result.planId().toString(),
            result.planContentHash(),
            result.status().name(),
            result.activeStepId(),
            output,
            steps,
            failure,
            result.startedAt(),
            result.completedAt()
        );
    }

    private IncidentSpecialistTrace traceForStep(
        IncidentInvestigationAssessment output,
        String stepId
    ) {
        return IncidentPlans.SERVICE_HEALTH_STEP.equals(stepId)
            ? trace(
                output.serviceHealth(),
                IncidentSpecialists.SERVICE_HEALTH_V2.toString()
            )
            : trace(
                output.changeRiskFinding(),
                IncidentSpecialists.CHANGE_RISK_V2.toString()
            );
    }

    private IncidentTransitionResponse transitionResponse(
        AIExecutionResult<IncidentRoutingDecision> intake,
        Object transition,
        Object second
    ) {
        return new IncidentTransitionResponse(
            executionView(intake),
            transitionView(transition),
            transitionView(second)
        );
    }

    private IncidentTransitionView transitionView(Object value) {
        if (value instanceof SpecialistDelegationResult<?, ?> delegation) {
            return new IncidentTransitionView(
                delegation.delegationId(),
                null,
                delegation.parentInvocationId(),
                null,
                delegation.sourceSpecialistId().toString(),
                null,
                delegation.targetSpecialistId().toString(),
                null,
                delegation.depth(),
                delegation.status().name(),
                executionView(delegation.targetExecution()),
                null,
                delegation.failure() == null ? null : new IncidentFailureView(
                    delegation.failure().reason(),
                    delegation.failure().publicMessage(),
                    delegation.failure().retryable(),
                    null
                ),
                delegation.replayed(),
                delegation.startedAt(),
                delegation.completedAt()
            );
        }
        if (value instanceof SpecialistHandoffResult<?, ?> handoff) {
            return new IncidentTransitionView(
                null,
                handoff.handoffId(),
                null,
                handoff.predecessorInvocationId(),
                null,
                handoff.predecessorSpecialistId().toString(),
                null,
                handoff.successorSpecialistId().toString(),
                handoff.depth(),
                handoff.status().name(),
                null,
                executionView(handoff.successorExecution()),
                handoff.failure() == null ? null : new IncidentFailureView(
                    handoff.failure().reason(),
                    handoff.failure().publicMessage(),
                    handoff.failure().retryable(),
                    null
                ),
                handoff.replayed(),
                handoff.startedAt(),
                handoff.completedAt()
            );
        }
        return null;
    }

    private IncidentSpecialistExecutionView executionView(
        AIExecutionResult<?> execution
    ) {
        if (execution == null) {
            return null;
        }
        IncidentFailureView failure = execution.failure() == null
            ? null
            : new IncidentFailureView(
                execution.failure().reason(),
                execution.failure().publicMessage(),
                execution.failure().retryable(),
                null
            );
        return new IncidentSpecialistExecutionView(
            execution.invocationId(),
            execution.specialistId().toString(),
            execution.status().name(),
            execution.output(),
            failure,
            execution.startedAt(),
            execution.completedAt(),
            trace(execution.output(), execution.specialistId().toString())
        );
    }

    private IncidentSpecialistTrace trace(Object output, String specialist) {
        if (output instanceof ServiceHealthInvestigationFinding finding) {
            return new IncidentSpecialistTrace(
                specialist,
                "SUCCEEDED",
                finding.dataSources(),
                finding.evidenceIds(),
                List.of(),
                finding.sourceRevision(),
                "ACTION_CITATIONS_VALIDATED"
            );
        }
        if (output instanceof ChangeRiskInvestigationFinding finding) {
            return new IncidentSpecialistTrace(
                specialist,
                "SUCCEEDED",
                finding.dataSources(),
                finding.evidenceIds(),
                finding.runbookEvidenceIds(),
                finding.sourceRevision(),
                "ACTION_AND_RAG_CITATIONS_VALIDATED"
            );
        }
        return null;
    }

    private ServiceHealthInvestigationRequest serviceRequest(IncidentPlanRequest incident) {
        return new ServiceHealthInvestigationRequest(
            incident.question(),
            incident.incidentId(),
            incident.deploymentId(),
            incident.sourceRevision()
        );
    }

    private ChangeRiskInvestigationRequest changeRequest(IncidentPlanRequest incident) {
        return new ChangeRiskInvestigationRequest(
            incident.question(),
            incident.incidentId(),
            incident.deploymentId(),
            incident.sourceRevision()
        );
    }

    private boolean equivalent(
        PlanExecutionResult<IncidentInvestigationAssessment> first,
        PlanExecutionResult<IncidentInvestigationAssessment> second
    ) {
        if (!first.succeeded() || !second.succeeded()) {
            return false;
        }
        IncidentInvestigationAssessment a = first.output();
        IncidentInvestigationAssessment b = second.output();
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
            session.scenario().deploymentId(),
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
