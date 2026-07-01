package com.ai.fabric.realapps.dbactionregistry.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DbActionRegistryLabServiceTest {

    @Autowired
    private DbActionRegistryLabService service;

    @BeforeEach
    void resetFixture() {
        service.resetRuntime();
    }

    @Test
    void approvalPublishesActionToDbAndRuntimeDiscovery() {
        DbActionRegistryLabService.ProposalSummary proposal = service.proposeTemplate("ticket.lookup");

        assertThat(proposal.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(service.discover().dbActions()).isEmpty();

        DbActionRegistryLabService.ProposalSummary approved = service.approve(proposal.proposalId());

        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(service.discover().dbActions()).extracting(DbActionRegistryLabService.ActionSummary::name)
            .containsExactly("ticket.lookup");
        assertThat(service.discover().runtimeActions()).extracting(DbActionRegistryLabService.ActionSummary::name)
            .containsExactly("ticket.lookup");
    }

    @Test
    void executesApprovedReadActionThroughConnectorHandler() {
        service.approve(service.proposeTemplate("ticket.lookup").proposalId());

        DbActionRegistryLabService.ExecutionSummary result = service.execute(
            "ticket.lookup",
            Map.of("ticketId", "TCK-1001"),
            false,
            "agent-1"
        );

        assertThat(result.success()).isTrue();
        assertThat(result.confirmationRequired()).isFalse();
        assertThat(result.data())
            .containsEntry("ticketId", "TCK-1001")
            .containsEntry("queue", "support");
    }

    @Test
    void writeActionRequiresConfirmationBeforeMutatingCustomerState() {
        service.approve(service.proposeTemplate("ticket.escalate").proposalId());

        DbActionRegistryLabService.ExecutionSummary confirmation = service.execute(
            "ticket.escalate",
            Map.of("ticketId", "TCK-1001", "targetQueue", "platform-sre"),
            false,
            "agent-1"
        );

        assertThat(confirmation.success()).isFalse();
        assertThat(confirmation.confirmationRequired()).isTrue();
        assertThat(confirmation.message()).isEqualTo("Escalate ticket TCK-1001 to platform-sre?");
        assertThat(service.customerTickets()).anySatisfy(ticket ->
            assertThat(ticket).containsEntry("ticketId", "TCK-1001").containsEntry("queue", "support")
        );

        DbActionRegistryLabService.ExecutionSummary executed = service.execute(
            "ticket.escalate",
            Map.of("ticketId", "TCK-1001", "targetQueue", "platform-sre"),
            true,
            "agent-1"
        );

        assertThat(executed.success()).isTrue();
        assertThat(executed.data())
            .containsEntry("ticketId", "TCK-1001")
            .containsEntry("queue", "platform-sre")
            .containsEntry("status", "ESCALATED");
    }

    @Test
    void deregisterRemovesRuntimeAvailability() {
        service.approve(service.proposeTemplate("ticket.lookup").proposalId());

        service.deregister("ticket.lookup");

        assertThat(service.discover().dbActions()).isEmpty();
        assertThat(service.execute("ticket.lookup", Map.of("ticketId", "TCK-1001"), false, "agent-1").errorCode())
            .isEqualTo("ACTION_NOT_REGISTERED");
    }
}
