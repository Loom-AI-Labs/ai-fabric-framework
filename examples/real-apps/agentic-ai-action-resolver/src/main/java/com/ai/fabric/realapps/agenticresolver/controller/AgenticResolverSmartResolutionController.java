package com.ai.fabric.realapps.agenticresolver.controller;

import com.ai.fabric.realapps.agenticresolver.agentic.AccountDelegationCoordinatorRequest;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountSmartResolutionExecutionView;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountSmartResolutionService;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountSmartResolutionView;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agentic-resolver/smart-resolutions")
@ConditionalOnProperty(
    name = "ai.execution.specialist-chains.enabled",
    havingValue = "true"
)
public class AgenticResolverSmartResolutionController {

    private final AccountSmartResolutionService service;

    public AgenticResolverSmartResolutionController(
        AccountSmartResolutionService service
    ) {
        this.service = service;
    }

    @PostMapping
    public AccountSmartResolutionView resolve(
        @RequestHeader(AgenticResolverController.SESSION_HEADER)
            String sessionId,
        @RequestHeader(AgenticResolverController.IDEMPOTENCY_HEADER)
            String idempotencyKey,
        @Valid @RequestBody AccountDelegationCoordinatorRequest request
    ) {
        return service.resolve(sessionId, request, idempotencyKey);
    }

    @PostMapping("/async")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AccountSmartResolutionExecutionView submit(
        @RequestHeader(AgenticResolverController.SESSION_HEADER)
            String sessionId,
        @RequestHeader(AgenticResolverController.IDEMPOTENCY_HEADER)
            String idempotencyKey,
        @Valid @RequestBody AccountDelegationCoordinatorRequest request
    ) {
        return service.submit(sessionId, request, idempotencyKey);
    }

    @GetMapping("/{executionId}")
    public AccountSmartResolutionExecutionView status(
        @RequestHeader(AgenticResolverController.SESSION_HEADER)
            String sessionId,
        @PathVariable String executionId
    ) {
        return service.status(sessionId, executionId);
    }

    @PostMapping("/{executionId}/cancel")
    public AccountSmartResolutionExecutionView cancel(
        @RequestHeader(AgenticResolverController.SESSION_HEADER)
            String sessionId,
        @PathVariable String executionId
    ) {
        return service.cancel(sessionId, executionId);
    }
}
