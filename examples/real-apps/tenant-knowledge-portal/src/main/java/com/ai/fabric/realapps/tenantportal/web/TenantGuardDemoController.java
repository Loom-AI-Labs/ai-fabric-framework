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
    public TenantKnowledgeService.TenantGuardDashboard dashboard() {
        return service.dashboard();
    }

    @PostMapping("/reset")
    public TenantKnowledgeService.TenantGuardDashboard reset() {
        return service.resetDemoData();
    }

    @GetMapping("/compare")
    public TenantKnowledgeService.SearchComparison compare(@RequestParam(defaultValue = "VPN") String q) {
        return service.compareSearch(q);
    }

    @PostMapping("/actions/execute")
    public TenantKnowledgeService.ActionDecision execute(@RequestBody TenantGuardActionRequest request) {
        TenantGuardActionRequest effective = request != null ? request : TenantGuardActionRequest.defaultRequest();
        return service.executeAction(
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
    public TenantKnowledgeService.TenantDeletionResult deleteTenant(@RequestBody TenantGuardDeleteRequest request) {
        TenantGuardDeleteRequest effective = request != null ? request : TenantGuardDeleteRequest.defaultRequest();
        return service.deleteTenant(
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

    public record TenantGuardDeleteRequest(String tenantId, String role, String targetTenantId) {
        static TenantGuardDeleteRequest defaultRequest() {
            return new TenantGuardDeleteRequest("platform", "ADMIN", "tenant-b");
        }
    }
}
