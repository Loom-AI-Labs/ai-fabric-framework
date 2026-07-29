package com.ai.fabric.realapps.agenticresolver.controller;

import ai.fabric.execution.specialist.client.SpecialistExecutionSnapshot;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionResult;
import com.ai.fabric.realapps.agenticresolver.agentic.event.PaymentVerificationFailedEvent;
import com.ai.fabric.realapps.agenticresolver.agentic.event.ProactiveAccountEventService;
import com.ai.fabric.realapps.agenticresolver.agentic.event.ProactiveEventSubmission;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agentic-resolver/events")
public class ProactiveAccountEventController {

    private final ProactiveAccountEventService eventService;

    public ProactiveAccountEventController(
        ProactiveAccountEventService eventService
    ) {
        this.eventService = eventService;
    }

    @PostMapping("/payment-verification-failed")
    public ResponseEntity<ProactiveEventSubmission>
    paymentVerificationFailed(
        @RequestHeader(AgenticResolverController.SESSION_HEADER)
        String sessionId,
        @Valid @RequestBody PaymentVerificationFailedEvent event
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(eventService.submit(sessionId, event));
    }

    @GetMapping("/executions/{invocationId}")
    public ResponseEntity<
        SpecialistExecutionSnapshot<AccountResolutionResult>
    > execution(
        @RequestHeader(AgenticResolverController.SESSION_HEADER)
        String sessionId,
        @PathVariable String invocationId
    ) {
        return eventService.find(sessionId, invocationId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/executions/{invocationId}")
    public ResponseEntity<Void> cancel(
        @RequestHeader(AgenticResolverController.SESSION_HEADER)
        String sessionId,
        @PathVariable String invocationId
    ) {
        return eventService.cancel(sessionId, invocationId)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }
}
