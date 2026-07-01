package com.ai.fabric.realapps.itsupport.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SupportOperationsServiceTest {

    private final SupportOperationsService service = new SupportOperationsService();

    @Test
    void supportOpsRetrievesRunbookEvidenceAndGatesActions() {
        SupportOperationsService.SupportOpsResult result = service.assist(new SupportOperationsService.TicketAssistRequest(
            "T-1001",
            "VPN outage blocking login for alex@example.com",
            Map.of("internalSlaNotes", "enterprise escalation"),
            true
        ));

        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.runbookEvidence())
            .extracting(SupportOperationsService.RunbookEvidence::id)
            .containsExactly("rb-vpn-outage");
        assertThat(result.suggestedActions()).contains("assign_ticket", "escalate_ticket");
        assertThat(result.ticketActionsRequireConfirmation()).isTrue();
    }

    @Test
    void customerSafeSummaryDoesNotLeakPiiOrInternalFields() {
        SupportOperationsService.SupportOpsResult result = service.assist(new SupportOperationsService.TicketAssistRequest(
            "T-1002",
            "Password reset needed for alex@example.com",
            Map.of("internalResolution", "tell engineering about identity incident"),
            true
        ));

        assertThat(result.customerSafeSummary()).contains("[REDACTED_EMAIL]");
        assertThat(result.customerSafeSummary()).doesNotContain("alex@example.com", "internalResolution", "engineering");
    }

    @Test
    void providerOnlyPathStillWorksWhenRagIsDisabled() {
        SupportOperationsService.SupportOpsResult result = service.assist(new SupportOperationsService.TicketAssistRequest(
            "T-1003",
            "Slow response in the admin console",
            Map.of(),
            false
        ));

        assertThat(result.runbookEvidence()).isEmpty();
        assertThat(result.suggestedActions()).contains("assign_ticket");
    }
}
