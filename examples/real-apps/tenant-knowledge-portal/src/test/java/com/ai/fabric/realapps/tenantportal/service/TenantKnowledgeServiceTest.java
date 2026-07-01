package com.ai.fabric.realapps.tenantportal.service;

import ai.fabric.intent.action.ActionAccessMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantKnowledgeServiceTest {

    private final TenantKnowledgeService service = new TenantKnowledgeService();

    @Test
    void tenantSearchReturnsOnlyCallerTenant() {
        assertThat(service.search(new TenantKnowledgeService.UserContext("tenant-a", "USER"), "VPN"))
            .extracting(TenantKnowledgeService.KnowledgeHit::id)
            .containsExactly("doc-a");
    }

    @Test
    void adminCatalogSeesAllAndUserCatalogIsScoped() {
        assertThat(service.catalog(new TenantKnowledgeService.UserContext("platform", "ADMIN")).visibleDocuments())
            .isEqualTo(3);
        assertThat(service.catalog(new TenantKnowledgeService.UserContext("tenant-a", "USER")).entries())
            .extracting(TenantKnowledgeService.CatalogEntry::id)
            .containsExactly("doc-a", "doc-admin");
    }

    @Test
    void crossTenantActionIsRejectedAndWriteActionIsConfirmed() {
        TenantKnowledgeService.ActionDecision crossTenant = service.executeAction(
            new TenantKnowledgeService.UserContext("tenant-a", "USER"),
            new TenantKnowledgeService.TenantActionRequest("archive_document", "doc-b", ActionAccessMode.WRITE_ONLY, true)
        );
        assertThat(crossTenant.errorCode()).isEqualTo("CROSS_TENANT_DENIED");

        TenantKnowledgeService.ActionDecision gated = service.executeAction(
            new TenantKnowledgeService.UserContext("tenant-a", "ADMIN"),
            new TenantKnowledgeService.TenantActionRequest("archive_document", "doc-a", ActionAccessMode.WRITE_ONLY, false)
        );
        assertThat(gated.confirmationRequired()).isTrue();
    }

    @Test
    void tenantDeletionRemovesOnlyTargetTenant() {
        TenantKnowledgeService.TenantDeletionResult result = service.deleteTenant(
            new TenantKnowledgeService.UserContext("platform", "ADMIN"),
            "tenant-b"
        );

        assertThat(result.success()).isTrue();
        assertThat(result.deletedIds()).containsExactly("doc-b");
        assertThat(service.search(new TenantKnowledgeService.UserContext("tenant-a", "USER"), "VPN"))
            .extracting(TenantKnowledgeService.KnowledgeHit::id)
            .containsExactly("doc-a");
        assertThat(service.search(new TenantKnowledgeService.UserContext("tenant-b", "USER"), "VPN")).isEmpty();
    }
}
