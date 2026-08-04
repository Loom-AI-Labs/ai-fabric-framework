package com.ai.fabric.realapps.mcpops.web;

import ai.fabric.execution.action.ActionProposalDecisionRequest;
import com.ai.fabric.realapps.mcpops.service.McpConnectionStatusService;
import com.ai.fabric.realapps.mcpops.service.McpDemoSessionService;
import com.ai.fabric.realapps.mcpops.service.McpInvocationAuditService;
import com.ai.fabric.realapps.mcpops.service.McpOperationsService;
import com.ai.fabric.realapps.mcpops.specialist.McpOperationsExecutionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
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
@RequestMapping("/api/mcp-ops")
public class McpOperationsController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final McpDemoSessionService sessions;
    private final McpOperationsService operations;
    private final McpOperationsExecutionService execution;
    private final McpInvocationAuditService audits;

    public McpOperationsController(
        McpDemoSessionService sessions,
        McpOperationsService operations,
        McpOperationsExecutionService execution,
        McpInvocationAuditService audits
    ) {
        this.sessions = sessions;
        this.operations = operations;
        this.execution = execution;
        this.audits = audits;
    }

    @PostMapping("/sessions")
    public ResponseEntity<McpDemoSessionService.SessionView> createSession() {
        return ResponseEntity.status(HttpStatus.CREATED).body(sessions.create());
    }

    @GetMapping("/sessions/{sessionId}")
    public McpDemoSessionService.SessionView session(
        @PathVariable String sessionId
    ) {
        return sessions.get(sessionId);
    }

    @PutMapping("/sessions/{sessionId}/service")
    public McpDemoSessionService.SessionView selectService(
        @PathVariable String sessionId,
        @Valid @RequestBody SelectServiceRequest request
    ) {
        return sessions.selectService(sessionId, request.serviceName());
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        return sessions.delete(sessionId)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    @GetMapping("/sessions/{sessionId}/state")
    public McpOperationsService.SandboxState state(
        @PathVariable String sessionId
    ) {
        return operations.state(sessionId);
    }

    @GetMapping("/sessions/{sessionId}/history")
    public List<McpOperationsExecutionService.ConversationMessage> history(
        @PathVariable String sessionId
    ) {
        return execution.history(sessionId);
    }

    @GetMapping("/sessions/{sessionId}/timeline")
    public List<McpInvocationAuditService.AuditView> timeline(
        @PathVariable String sessionId
    ) {
        sessions.active(sessionId);
        return audits.timeline(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/chat")
    public McpOperationsExecutionService.TurnResponse chat(
        @PathVariable String sessionId,
        @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
        @Valid @RequestBody McpOperationsExecutionService.ChatRequest request
    ) {
        return execution.chat(sessionId, request, idempotencyKey);
    }

    @PostMapping("/sessions/{sessionId}/actions/decide")
    public McpOperationsExecutionService.DecisionResponse decide(
        @PathVariable String sessionId,
        @Valid @RequestBody ActionProposalDecisionRequest request
    ) {
        return execution.decide(sessionId, request);
    }

    @PostMapping("/sessions/{sessionId}/binding-canary")
    public McpOperationsService.BindingCanary bindingCanary(
        @PathVariable String sessionId
    ) {
        return operations.bindingCanary(sessionId);
    }

    @GetMapping("/tools")
    public List<McpOperationsService.ToolPolicy> tools() {
        return operations.catalog();
    }

    @GetMapping("/connection")
    public McpConnectionStatusService.ConnectionStatus connection() {
        return operations.connection();
    }

    public record SelectServiceRequest(@NotBlank String serviceName) {
    }
}
