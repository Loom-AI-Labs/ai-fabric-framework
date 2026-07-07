package com.ai.fabric.realapps.tenantportal.web;

import ai.fabric.intent.action.ActionAccessMode;
import com.ai.fabric.realapps.tenantportal.service.TenantKnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenant-guard-demo")
@RequiredArgsConstructor
public class TenantGuardDemoController {

    private final TenantKnowledgeService service;

    @GetMapping("/dashboard")
    public TenantKnowledgeService.TenantGuardDashboard dashboard(
        @RequestParam(value = "sessionId", required = false) String sessionId
    ) {
        return service.dashboard(sessionId);
    }

    @PostMapping("/reset")
    public TenantKnowledgeService.TenantGuardDashboard reset(
        @RequestParam(value = "sessionId", required = false) String sessionId
    ) {
        return service.resetDemoData(sessionId);
    }

    @GetMapping("/compare")
    public TenantKnowledgeService.SearchComparison compare(
        @RequestParam(defaultValue = "VPN") String q,
        @RequestParam(value = "sessionId", required = false) String sessionId
    ) {
        return service.compareSearch(sessionId, q);
    }

    @PostMapping("/query")
    public TenantKnowledgeService.TenantRagResponse query(
        @RequestParam(value = "sessionId", required = false) String sessionId,
        @RequestBody TenantGuardQueryRequest request
    ) {
        TenantGuardQueryRequest effective = request != null ? request : TenantGuardQueryRequest.defaultRequest();
        return service.queryTenantKnowledge(
            sessionId,
            new TenantKnowledgeService.TenantQueryRequest(
                effective.tenantId(),
                effective.role(),
                effective.query(),
                effective.limit()
            )
        );
    }

    @PostMapping("/index/seed")
    public TenantKnowledgeService.VectorIndexProof seedIndex(
        @RequestParam(value = "sessionId", required = false) String sessionId
    ) {
        return service.seedAiIndex(sessionId);
    }

    @GetMapping("/index/proof")
    public TenantKnowledgeService.VectorIndexProof indexProof(
        @RequestParam(value = "sessionId", required = false) String sessionId
    ) {
        return service.indexProof(sessionId);
    }

    @PostMapping("/actions/execute")
    public TenantKnowledgeService.ActionDecision execute(
        @RequestParam(value = "sessionId", required = false) String sessionId,
        @RequestBody TenantGuardActionRequest request
    ) {
        TenantGuardActionRequest effective = request != null ? request : TenantGuardActionRequest.defaultRequest();
        return service.executeAction(
            sessionId,
            new TenantKnowledgeService.UserContext(effective.tenantId(), effective.role()),
            new TenantKnowledgeService.TenantActionRequest(
                effective.actionId(),
                effective.documentId(),
                effective.accessMode(),
                effective.confirmed()
            )
        );
    }

    @PostMapping("/tenants/delete")
    public TenantKnowledgeService.TenantDeletionResult deleteTenant(
        @RequestParam(value = "sessionId", required = false) String sessionId,
        @RequestBody TenantGuardDeleteRequest request
    ) {
        TenantGuardDeleteRequest effective = request != null ? request : TenantGuardDeleteRequest.defaultRequest();
        return service.deleteTenant(
            sessionId,
            new TenantKnowledgeService.UserContext(effective.tenantId(), effective.role()),
            effective.targetTenantId()
        );
    }

    public record TenantGuardActionRequest(
        String tenantId,
        String role,
        String actionId,
        String documentId,
        ActionAccessMode accessMode,
        boolean confirmed
    ) {
        static TenantGuardActionRequest defaultRequest() {
            return new TenantGuardActionRequest(
                "tenant-a",
                "USER",
                "archive_document",
                "doc-b",
                ActionAccessMode.WRITE_ONLY,
                true
            );
        }
    }

    public record TenantGuardQueryRequest(String tenantId, String role, String query, int limit) {
        static TenantGuardQueryRequest defaultRequest() {
            return new TenantGuardQueryRequest("tenant-a", "USER", "How do I configure VPN?", 5);
        }
    }

    public record TenantGuardDeleteRequest(String tenantId, String role, String targetTenantId) {
        static TenantGuardDeleteRequest defaultRequest() {
            return new TenantGuardDeleteRequest("platform", "ADMIN", "tenant-b");
        }
    }
}
