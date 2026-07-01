package com.ai.fabric.realapps.dbactionregistry.web;

import com.ai.fabric.realapps.dbactionregistry.service.DbActionRegistryLabService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/demo/db-action-registry")
public class DbActionRegistryLabController {

    private final DbActionRegistryLabService service;

    public DbActionRegistryLabController(DbActionRegistryLabService service) {
        this.service = service;
    }

    @GetMapping("/templates")
    public List<DbActionRegistryLabService.TemplateSummary> templates() {
        return service.templates();
    }

    @PostMapping("/proposals/{templateName}")
    public DbActionRegistryLabService.ProposalSummary propose(@PathVariable String templateName) {
        return service.proposeTemplate(templateName);
    }

    @PostMapping("/proposals/{proposalId}/approve")
    public DbActionRegistryLabService.ProposalSummary approve(@PathVariable String proposalId) {
        return service.approve(proposalId);
    }

    @GetMapping("/discovery")
    public DbActionRegistryLabService.DiscoverySummary discovery() {
        return service.discover();
    }

    @PostMapping("/execute/{actionName}")
    public DbActionRegistryLabService.ExecutionSummary execute(@PathVariable String actionName,
                                                               @RequestBody(required = false) ExecuteRequest request) {
        ExecuteRequest safeRequest = request != null ? request : new ExecuteRequest(Map.of(), false, null);
        return service.execute(actionName, safeRequest.params(), safeRequest.confirmed(), safeRequest.userId());
    }

    @DeleteMapping("/actions/{actionName}")
    public DbActionRegistryLabService.DiscoverySummary deregister(@PathVariable String actionName) {
        return service.deregister(actionName);
    }

    @GetMapping("/tickets")
    public List<Map<String, Object>> tickets() {
        return service.customerTickets();
    }

    public record ExecuteRequest(Map<String, Object> params, boolean confirmed, String userId) {
        public ExecuteRequest {
            params = params != null ? Map.copyOf(params) : Map.of();
        }
    }
}
