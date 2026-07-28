package com.ai.fabric.realapps.agenticresolver.controller;

import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.action.ActionProposalDecisionRequest;
import ai.fabric.execution.action.ActionProposalDecisionResult;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionRequest;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionResult;
import com.ai.fabric.realapps.agenticresolver.agentic.AgenticResolverExecutionService;
import com.ai.fabric.realapps.agenticresolver.agentic.AgenticResolverSessionService;
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
}
