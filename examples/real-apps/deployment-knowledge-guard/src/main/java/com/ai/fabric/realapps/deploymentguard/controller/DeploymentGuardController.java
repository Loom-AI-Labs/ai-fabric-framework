package com.ai.fabric.realapps.deploymentguard.controller;

import com.ai.fabric.realapps.deploymentguard.domain.DeploymentKnowledgeCatalog;
import com.ai.fabric.realapps.deploymentguard.domain.DeploymentKnowledgeDocument;
import com.ai.fabric.realapps.deploymentguard.service.DeploymentGuardSessionService;
import com.ai.fabric.realapps.deploymentguard.service.DeploymentKnowledgeExecutionService;
import com.ai.fabric.realapps.deploymentguard.service.DeploymentKnowledgeExecutionService.CanaryType;
import com.ai.fabric.realapps.deploymentguard.specialist.DeploymentKnowledgeRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
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
@RequestMapping("/api/deployment-guard")
public class DeploymentGuardController {

    public static final String SESSION_HEADER = "X-AI-Fabric-Demo-Session";

    private final DeploymentGuardSessionService sessionService;
    private final DeploymentKnowledgeExecutionService executionService;
    private final DeploymentKnowledgeCatalog catalog;

    public DeploymentGuardController(
        DeploymentGuardSessionService sessionService,
        DeploymentKnowledgeExecutionService executionService,
        DeploymentKnowledgeCatalog catalog
    ) {
        this.sessionService = sessionService;
        this.executionService = executionService;
        this.catalog = catalog;
    }

    @PostMapping("/sessions")
    public DeploymentGuardSessionService.SessionView createSession() {
        return sessionService.create();
    }

    @GetMapping("/sessions/current")
    public DeploymentGuardSessionService.SessionView currentSession(
        @RequestHeader(SESSION_HEADER) String sessionId
    ) {
        return sessionService.require(sessionId);
    }

    @PutMapping("/sessions/current/contexts/{contextId}")
    public DeploymentGuardSessionService.SessionView selectContext(
        @RequestHeader(SESSION_HEADER) String sessionId,
        @PathVariable String contextId
    ) {
        return sessionService.selectContext(sessionId, contextId);
    }

    @DeleteMapping("/sessions/current")
    public void deleteSession(
        @RequestHeader(SESSION_HEADER) String sessionId
    ) {
        sessionService.delete(sessionId);
    }

    @PostMapping("/query")
    public DeploymentKnowledgeExecutionService.QueryResponse query(
        @RequestHeader(SESSION_HEADER) String sessionId,
        @Valid @RequestBody DeploymentKnowledgeRequest request
    ) {
        return executionService.query(sessionId, request);
    }

    @PostMapping("/canaries/{type}")
    public DeploymentKnowledgeExecutionService.QueryResponse canary(
        @RequestHeader(SESSION_HEADER) String sessionId,
        @PathVariable String type
    ) {
        CanaryType canaryType = CanaryType.valueOf(
            type.trim().replace('-', '_').toUpperCase(Locale.ROOT)
        );
        return executionService.runCanary(sessionId, canaryType);
    }

    @GetMapping("/evidence")
    public List<EvidenceSummary> evidence(
        @RequestHeader(SESSION_HEADER) String sessionId
    ) {
        var session = sessionService.activeSession(sessionId);
        return catalog.documentsFor(session.context()).stream()
            .map(document -> new EvidenceSummary(
                document.id(),
                document.title(),
                document.sourceType(),
                document.revision()
            ))
            .toList();
    }

    @GetMapping("/evidence/{documentId}")
    public DeploymentKnowledgeDocument evidenceDocument(
        @RequestHeader(SESSION_HEADER) String sessionId,
        @PathVariable String documentId
    ) {
        var session = sessionService.activeSession(sessionId);
        DeploymentKnowledgeDocument document = catalog.requireDocument(documentId);
        if (!catalog.belongsTo(document, session.context())) {
            throw new IllegalArgumentException(
                "Evidence does not belong to the active deployment context"
            );
        }
        return document;
    }

    public record EvidenceSummary(
        String id,
        String title,
        String sourceType,
        int revision
    ) {}
}
