package com.ai.fabric.realapps.agenticresolver.controller;

import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionResumeResult;
import ai.fabric.execution.plan.PlanExecutionResult;
import ai.fabric.execution.plan.PlanExecutionResumeResult;
import ai.fabric.execution.plan.PlanExecutionSnapshot;
import ai.fabric.execution.action.ActionProposalDecisionRequest;
import ai.fabric.execution.action.ActionProposalDecisionResult;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionRequest;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionResult;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountDelegationCoordinatorRequest;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountDelegationResponse;
import com.ai.fabric.realapps.agenticresolver.agentic.AgenticResolverExecutionService;
import com.ai.fabric.realapps.agenticresolver.agentic.AgenticResolverSessionService;
import com.ai.fabric.realapps.agenticresolver.agentic.BillingAssessmentResumeRequest;
import com.ai.fabric.realapps.agenticresolver.agentic.BillingResolutionAssessmentRequest;
import com.ai.fabric.realapps.agenticresolver.agentic.BillingResolutionAssessmentResult;
import com.ai.fabric.realapps.agenticresolver.agentic.plan.AccountBillingResolutionPlanRequest;
import com.ai.fabric.realapps.agenticresolver.agentic.plan.AccountBillingResolutionPlanResult;
import com.ai.fabric.realapps.agenticresolver.agentic.plan.PlanInputResumeRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agentic-resolver")
public class AgenticResolverController {

    public static final String SESSION_HEADER = "X-AI-Fabric-Demo-Session";
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final AgenticResolverSessionService sessionService;
    private final AgenticResolverExecutionService executionService;

    public AgenticResolverController(
        AgenticResolverSessionService sessionService,
        AgenticResolverExecutionService executionService
    ) {
        this.sessionService = sessionService;
        this.executionService = executionService;
    }

    @PostMapping("/sessions")
    public ResponseEntity<AgenticResolverSessionService.SessionView> createSession() {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(sessionService.create());
    }

    @GetMapping("/sessions/{sessionId}")
    public AgenticResolverSessionService.SessionView session(
        @PathVariable String sessionId
    ) {
        return sessionService.get(sessionId);
    }

    @PutMapping("/sessions/{sessionId}/scenarios/{scenarioId}")
    public AgenticResolverSessionService.SessionView selectScenario(
        @PathVariable String sessionId,
        @PathVariable String scenarioId
    ) {
        return sessionService.select(sessionId, scenarioId);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        return sessionService.delete(sessionId)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    @PostMapping("/evaluate")
    public AIExecutionResult<AccountResolutionResult> evaluate(
        @RequestHeader(SESSION_HEADER) String sessionId,
        @RequestHeader(
            name = IDEMPOTENCY_HEADER,
            required = false
        ) String idempotencyKey,
        @Valid @RequestBody AccountResolutionRequest request
    ) {
        return executionService.evaluate(sessionId, request, idempotencyKey);
    }

    @PostMapping("/chat")
    public AIExecutionResult<AccountResolutionResult> chat(
        @RequestHeader(SESSION_HEADER) String sessionId,
        @RequestHeader(
            name = IDEMPOTENCY_HEADER,
            required = false
        ) String idempotencyKey,
        @Valid @RequestBody AccountResolutionRequest request
    ) {
        return executionService.chat(sessionId, request, idempotencyKey);
    }

    @PostMapping("/actions/decide")
    public ActionProposalDecisionResult decide(
        @RequestHeader(SESSION_HEADER) String sessionId,
        @Valid @RequestBody ActionProposalDecisionRequest request
    ) {
        return executionService.decide(sessionId, request);
    }

    @PostMapping("/billing-assessment")
    public AIExecutionResult<BillingResolutionAssessmentResult>
    assessBillingResolution(
        @RequestHeader(SESSION_HEADER) String sessionId,
        @RequestHeader(
            name = IDEMPOTENCY_HEADER,
            required = false
        ) String idempotencyKey,
        @Valid @RequestBody BillingResolutionAssessmentRequest request
    ) {
        return executionService.assessBillingResolution(
            sessionId,
            request,
            idempotencyKey
        );
    }

    @PostMapping("/delegate")
    public AccountDelegationResponse delegate(
        @RequestHeader(SESSION_HEADER) String sessionId,
        @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
        @Valid @RequestBody AccountDelegationCoordinatorRequest request
    ) {
        return executionService.delegateAccountResolution(
            sessionId,
            request,
            idempotencyKey
        );
    }

    @PostMapping("/input/resume")
    public AIExecutionResumeResult<BillingResolutionAssessmentResult>
    resumeBillingAssessment(
        @RequestHeader(SESSION_HEADER) String sessionId,
        @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
        @Valid @RequestBody BillingAssessmentResumeRequest request
    ) {
        return executionService.resumeBillingAssessment(
            sessionId,
            request,
            idempotencyKey
        );
    }

    @PostMapping("/plans/account-readiness")
    public PlanExecutionResult<AccountResolutionResult>
    executeAccountReadinessPlan(
        @RequestHeader(SESSION_HEADER) String sessionId,
        @RequestHeader(
            name = IDEMPOTENCY_HEADER,
            required = false
        ) String idempotencyKey,
        @Valid @RequestBody AccountResolutionRequest request
    ) {
        return executionService.executeAccountReadinessPlan(
            sessionId,
            request,
            idempotencyKey
        );
    }

    @PostMapping("/plans/account-billing-resolution")
    public PlanExecutionResult<AccountBillingResolutionPlanResult>
    executeAccountBillingPlan(
        @RequestHeader(SESSION_HEADER) String sessionId,
        @RequestHeader(
            name = IDEMPOTENCY_HEADER,
            required = false
        ) String idempotencyKey,
        @Valid @RequestBody AccountBillingResolutionPlanRequest request
    ) {
        return executionService.executeAccountBillingPlan(
            sessionId,
            request,
            idempotencyKey
        );
    }

    @PostMapping("/plans/input/resume")
    public PlanExecutionResumeResult<AccountBillingResolutionPlanResult>
    resumeAccountBillingPlan(
        @RequestHeader(SESSION_HEADER) String sessionId,
        @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
        @Valid @RequestBody PlanInputResumeRequest request
    ) {
        return executionService.resumeAccountBillingPlan(
            sessionId,
            request,
            idempotencyKey
        );
    }

    @GetMapping("/plans/executions/{executionId}")
    public ResponseEntity<PlanExecutionSnapshot> planExecution(
        @RequestHeader(SESSION_HEADER) String sessionId,
        @PathVariable String executionId
    ) {
        return executionService.findPlanExecution(sessionId, executionId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/plans/executions/{executionId}")
    public ResponseEntity<Void> cancelPlanExecution(
        @RequestHeader(SESSION_HEADER) String sessionId,
        @PathVariable String executionId
    ) {
        return executionService.cancelPlanExecution(
                sessionId,
                executionId
            )
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }
}
