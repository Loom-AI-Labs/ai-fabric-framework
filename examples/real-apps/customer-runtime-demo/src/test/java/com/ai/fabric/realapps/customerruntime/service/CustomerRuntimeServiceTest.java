package com.ai.fabric.realapps.customerruntime.service;

import ai.fabric.intent.action.ActionAccessMode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerRuntimeServiceTest {

    private final CustomerRuntimeService service = new CustomerRuntimeService();

    @Test
    void domainCreateUpdateDeleteProducesDataSyncPayloads() {
        CustomerRuntimeService.SyncEvidence upsert = service.upsertDomainRecord(record("acct-1", "tenant-a"));

        assertThat(upsert.operation()).isEqualTo("UPSERT");
        assertThat(upsert.upsertRequest().getVectorSpace()).isEqualTo("customer-record");
        assertThat(upsert.upsertRequest().getMetadata()).containsEntry("tenantId", "tenant-a");

        CustomerRuntimeService.SyncEvidence delete = service.deleteDomainRecord("acct-1");

        assertThat(delete.operation()).isEqualTo("DELETE");
        assertThat(delete.deleteRequest().getId()).isEqualTo("acct-1");
        assertThat(service.search("tenant-a", "security")).isEmpty();
    }

    @Test
    void searchIsTenantScoped() {
        service.upsertDomainRecord(record("acct-1", "tenant-a"));
        service.upsertDomainRecord(record("acct-2", "tenant-b"));

        assertThat(service.search("tenant-a", "security"))
            .extracting(CustomerRuntimeService.SearchHit::id)
            .containsExactly("acct-1");
    }

    @Test
    void writeActionsRequireConfirmationAndOutageIsStructured() {
        service.upsertDomainRecord(record("acct-1", "tenant-a"));

        CustomerRuntimeService.ActionOutcome gated = service.executeAction(new CustomerRuntimeService.ActionRequest(
            "create_follow_up_task",
            "acct-1",
            ActionAccessMode.WRITE_ONLY,
            Map.of("assignee", "maya"),
            false
        ));
        assertThat(gated.confirmationRequired()).isTrue();
        assertThat(gated.success()).isFalse();

        CustomerRuntimeService.ActionOutcome executed = service.executeAction(new CustomerRuntimeService.ActionRequest(
            "create_follow_up_task",
            "acct-1",
            ActionAccessMode.WRITE_ONLY,
            Map.of("assignee", "maya"),
            true
        ));
        assertThat(executed.success()).isTrue();
        assertThat(executed.data()).containsEntry("recordId", "acct-1");

        service.setConnectorAvailable(false);
        CustomerRuntimeService.ActionOutcome outage = service.executeAction(new CustomerRuntimeService.ActionRequest(
            "create_follow_up_task",
            "acct-1",
            ActionAccessMode.WRITE_ONLY,
            Map.of(),
            true
        ));
        assertThat(outage.success()).isFalse();
        assertThat(outage.errorCode()).isEqualTo("CONNECTOR_UNAVAILABLE");
    }

    private static CustomerRuntimeService.DomainRecord record(String id, String tenantId) {
        return new CustomerRuntimeService.DomainRecord(
            id,
            tenantId,
            "Security review",
            "Security review is required before rollout.",
            "1",
            false
        );
    }
}
