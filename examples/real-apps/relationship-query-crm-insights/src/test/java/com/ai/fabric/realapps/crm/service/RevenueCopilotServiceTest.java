package com.ai.fabric.realapps.crm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RevenueCopilotServiceTest {

    private final RevenueCopilotService service = new RevenueCopilotService(new ObjectMapper());

    @Test
    void plannerOutputUsesStructuredJsonPathAndAllowlist() {
        RevenueCopilotService.PlannerOutput output = service.parsePlannerOutput("""
            ```json
            {"entityTypes":["account","deal","ticket"],"filters":{"segment":"enterprise"}}
            ```
            """);

        RevenueCopilotService.RevenueWorkspace workspace = service.buildWorkspace(
            new RevenueCopilotService.RevenueQueryRequest(
                "open enterprise deals with support risk",
                List.of("account", "deal", "ticket", "note")
            ),
            output
        );

        assertThat(workspace.dealIds()).containsExactly("deal-9001", "deal-9002");
        assertThat(workspace.ticketIds()).containsExactly("ticket-7001");
    }

    @Test
    void queryPlannerCannotAccessEntityTypesOutsideAllowlist() {
        RevenueCopilotService.PlannerOutput output = new RevenueCopilotService.PlannerOutput(
            List.of("account", "invoice"),
            Map.of()
        );

        assertThatThrownBy(() -> service.buildWorkspace(
            new RevenueCopilotService.RevenueQueryRequest("show invoices", List.of("account", "deal")),
            output
        )).hasMessageContaining("outside allowlist");
    }

    @Test
    void followUpTaskRequiresValidTargetsAndSummaryIncludesEvidenceIds() {
        RevenueCopilotService.FollowUpTaskResult invalid = service.createFollowUpTask(
            new RevenueCopilotService.FollowUpTaskRequest("acct-1001", "deal-missing", "maya")
        );
        assertThat(invalid.success()).isFalse();
        assertThat(invalid.errorCode()).isEqualTo("INVALID_TARGET");

        RevenueCopilotService.FollowUpTaskResult valid = service.createFollowUpTask(
            new RevenueCopilotService.FollowUpTaskRequest("acct-1001", "deal-9001", "maya")
        );
        assertThat(valid.success()).isTrue();
        assertThat(valid.data()).containsEntry("dealId", "deal-9001");

        String summary = service.accountTeamSummary(new RevenueCopilotService.RevenueWorkspace(
            List.of("deal-9001"),
            List.of("ticket-7001"),
            List.of("note-3001"),
            Map.of()
        ));
        assertThat(summary).contains("deal-9001", "ticket-7001", "note-3001");
    }
}
