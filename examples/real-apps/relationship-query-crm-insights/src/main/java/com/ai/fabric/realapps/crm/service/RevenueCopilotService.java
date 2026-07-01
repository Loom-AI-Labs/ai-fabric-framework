package com.ai.fabric.realapps.crm.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RevenueCopilotService {

    private final ObjectMapper objectMapper;
    private final Set<String> knownAccountIds = Set.of("acct-1001", "acct-2001");
    private final Set<String> knownDealIds = Set.of("deal-9001", "deal-9002");

    public RevenueCopilotService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PlannerOutput parsePlannerOutput(String rawJson) {
        try {
            return objectMapper.readValue(cleanJson(rawJson), PlannerOutput.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Planner output could not be parsed by the shared structured-output path", ex);
        }
    }

    public RevenueWorkspace buildWorkspace(RevenueQueryRequest request, PlannerOutput plannerOutput) {
        if (request == null || plannerOutput == null) {
            throw new IllegalArgumentException("request and plannerOutput are required");
        }
        Set<String> allowlist = new LinkedHashSet<>(request.allowedEntityTypes());
        List<String> denied = plannerOutput.entityTypes().stream()
            .filter(entityType -> !allowlist.contains(entityType))
            .toList();
        if (!denied.isEmpty()) {
            throw new IllegalArgumentException("Planner requested entity types outside allowlist: " + denied);
        }
        return new RevenueWorkspace(
            List.of("deal-9001", "deal-9002"),
            List.of("ticket-7001"),
            List.of("note-3001"),
            Map.of("plannerFilters", plannerOutput.filters())
        );
    }

    public FollowUpTaskResult createFollowUpTask(FollowUpTaskRequest request) {
        if (request == null
            || !knownAccountIds.contains(request.accountId())
            || !knownDealIds.contains(request.dealId())
            || !StringUtils.hasText(request.owner())) {
            return new FollowUpTaskResult(false, "INVALID_TARGET", Map.of());
        }
        return new FollowUpTaskResult(true, null, Map.of(
            "taskId", "task-" + request.dealId(),
            "accountId", request.accountId(),
            "dealId", request.dealId(),
            "owner", request.owner()
        ));
    }

    public String accountTeamSummary(RevenueWorkspace workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException("workspace is required");
        }
        return "Workspace used deals " + workspace.dealIds()
            + ", tickets " + workspace.ticketIds()
            + ", and notes " + workspace.noteIds() + ".";
    }

    private String cleanJson(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            throw new IllegalArgumentException("rawJson is required");
        }
        String trimmed = rawJson.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring("```json".length()).trim();
        }
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring("```".length()).trim();
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        return trimmed;
    }

    public record RevenueQueryRequest(String query, List<String> allowedEntityTypes) {}

    public record PlannerOutput(List<String> entityTypes, Map<String, Object> filters) {
        public PlannerOutput {
            entityTypes = entityTypes == null ? List.of() : List.copyOf(entityTypes);
            filters = filters == null ? Map.of() : Map.copyOf(filters);
        }
    }

    public record RevenueWorkspace(
        List<String> dealIds,
        List<String> ticketIds,
        List<String> noteIds,
        Map<String, Object> diagnostics
    ) {}

    public record FollowUpTaskRequest(String accountId, String dealId, String owner) {}

    public record FollowUpTaskResult(boolean success, String errorCode, Map<String, Object> data) {}
}
