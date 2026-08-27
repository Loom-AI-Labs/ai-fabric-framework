package com.ai.fabric.examples.governedactions.controller;

import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/actions")
public class GovernedActionsController {

    private final ObjectProvider<RAGOrchestrator> orchestratorProvider;
    private final ObjectProvider<AIActionRegistry> actionRegistryProvider;

    public GovernedActionsController(
            ObjectProvider<RAGOrchestrator> orchestratorProvider,
            ObjectProvider<AIActionRegistry> actionRegistryProvider) {

        this.orchestratorProvider = orchestratorProvider;
        this.actionRegistryProvider = actionRegistryProvider;
    }

    @GetMapping
    public ResponseEntity<?> getActions() {

        AIActionRegistry registry = actionRegistryProvider.getIfAvailable();

        if (registry == null) {
            return ResponseEntity.ok(
                    Map.of("enabled", false)
            );
        }

        return ResponseEntity.ok(
                registry.getAllMetadata()
        );
    }

    @PostMapping("/query")
    public ResponseEntity<?> query(@RequestBody QueryRequest request) {

        RAGOrchestrator orchestrator =
                orchestratorProvider.getIfAvailable();

        if (orchestrator == null) {
            return ResponseEntity.ok(
                    Map.of("error", "Orchestrator not configured")
            );
        }

        String sessionId =
                request.sessionId() != null
                        ? request.sessionId()
                        : UUID.randomUUID().toString();

        String conversationId =
                request.conversationId() != null
                        ? request.conversationId()
                        : "quickstart-" + sessionId;

        OrchestrationContext context =
                OrchestrationContext.builder()
                        .userId("demo-user")
                        .sessionId(sessionId)
                        .conversationId(conversationId)
                        .build();

        OrchestrationResult result =
                orchestrator.orchestrate(
                        request.query(),
                        context
                );

        return ResponseEntity.ok(
                new QueryResponse(
                        sessionId,
                        conversationId,
                        result
                )
        );
    }

    public record QueryRequest(
            String query,
            String sessionId,
            String conversationId
    ) {}

    public record QueryResponse(
            String sessionId,
            String conversationId,
            OrchestrationResult result
    ) {}
}