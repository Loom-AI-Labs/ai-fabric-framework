package com.ai.fabric.realapps.agenticresolver.controller;

import ai.fabric.execution.manager.ConversationManagerTurnResult;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountConversationManagerService;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountDelegationCoordinatorRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agentic-resolver/manager")
public class AgenticResolverManagerController {

    private final AccountConversationManagerService managerService;

    public AgenticResolverManagerController(
        AccountConversationManagerService managerService
    ) {
        this.managerService = managerService;
    }

    @PostMapping("/chat")
    public ConversationManagerTurnResult chat(
        @RequestHeader(AgenticResolverController.SESSION_HEADER)
            String sessionId,
        @RequestHeader(AgenticResolverController.IDEMPOTENCY_HEADER)
            String idempotencyKey,
        @Valid @RequestBody AccountDelegationCoordinatorRequest request
    ) {
        return managerService.chat(
            sessionId,
            request,
            idempotencyKey
        );
    }
}
