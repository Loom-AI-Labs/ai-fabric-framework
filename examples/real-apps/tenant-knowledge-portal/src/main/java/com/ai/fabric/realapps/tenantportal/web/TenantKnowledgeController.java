package com.ai.fabric.realapps.tenantportal.web;

import com.ai.fabric.realapps.tenantportal.service.TenantKnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tenant-knowledge")
@RequiredArgsConstructor
public class TenantKnowledgeController {

    private final TenantKnowledgeService service;

    @PostMapping("/documents")
    public TenantKnowledgeService.KnowledgeDocument seed(@RequestBody TenantKnowledgeService.KnowledgeDocument document) {
        return service.seed(document);
    }

    @GetMapping("/search")
    public List<TenantKnowledgeService.KnowledgeHit> search(
        @RequestHeader("X-Tenant-Id") String tenantId,
        @RequestHeader(value = "X-Role", defaultValue = "USER") String role,
        @RequestParam String q
    ) {
        return service.search(new TenantKnowledgeService.UserContext(tenantId, role), q);
    }

    @GetMapping("/catalog")
    public TenantKnowledgeService.CatalogSummary catalog(
        @RequestHeader("X-Tenant-Id") String tenantId,
        @RequestHeader(value = "X-Role", defaultValue = "USER") String role
    ) {
        return service.catalog(new TenantKnowledgeService.UserContext(tenantId, role));
    }

    @PostMapping("/actions/execute")
    public TenantKnowledgeService.ActionDecision execute(
        @RequestHeader("X-Tenant-Id") String tenantId,
        @RequestHeader(value = "X-Role", defaultValue = "USER") String role,
        @RequestBody TenantKnowledgeService.TenantActionRequest request
    ) {
        return service.executeAction(new TenantKnowledgeService.UserContext(tenantId, role), request);
    }

    @DeleteMapping("/tenant")
    public TenantKnowledgeService.TenantDeletionResult deleteTenant(
        @RequestHeader("X-Tenant-Id") String tenantId,
        @RequestHeader(value = "X-Role", defaultValue = "USER") String role,
        @RequestParam String targetTenantId
    ) {
        return service.deleteTenant(new TenantKnowledgeService.UserContext(tenantId, role), targetTenantId);
    }
}
