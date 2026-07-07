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
            .isEqualTo(6);
        assertThat(service.catalog(new TenantKnowledgeService.UserContext("tenant-a", "USER")).entries())
            .extracting(TenantKnowledgeService.CatalogEntry::id)
            .containsExactly("doc-a", "doc-a-billing");
    }

    @Test
    void crossTenantActionIsRejectedAndWriteActionIsConfirmed() {
        TenantKnowledgeService.ActionDecision crossTenant = service.executeAction(
            new TenantKnowledgeService.UserContext("tenant-a", "USER"),
            new TenantKnowledgeService.TenantActionRequest("archive_document", "doc-b", ActionAccessMode.WRITE_ONLY, true)
        );
        assertThat(crossTenant.errorCode()).isEqualTo("CROSS_TENANT_DENIED");
        assertThat(crossTenant.data())
            .containsEntry("policyDecision", "DENIED")
            .containsEntry("policyExplanation", "Cross-tenant write targets are rejected before execution.")
            .containsEntry("subjectTenantId", "tenant-a")
            .containsEntry("targetTenantId", "tenant-b");

        TenantKnowledgeService.ActionDecision gated = service.executeAction(
            new TenantKnowledgeService.UserContext("tenant-a", "ADMIN"),
            new TenantKnowledgeService.TenantActionRequest("archive_document", "doc-a", ActionAccessMode.WRITE_ONLY, false)
        );
        assertThat(gated.confirmationRequired()).isTrue();
        assertThat(gated.data())
            .containsEntry("policyDecision", "CONFIRMATION_REQUIRED")
            .containsEntry("policyExplanation", "Write actions require explicit confirmation after tenant and role checks pass.");
    }

    @Test
    void dashboardShowsTenantGuardEvidence() {
        TenantKnowledgeService.TenantGuardDashboard dashboard = service.dashboard();

        assertThat(dashboard.scenarios()).hasSize(3);
        assertThat(dashboard.stats().totalDocuments()).isEqualTo(6);
        assertThat(dashboard.defaultComparison().tenantAResults())
            .extracting(TenantKnowledgeService.KnowledgeHit::id)
            .containsExactly("doc-a");
        assertThat(dashboard.defaultComparison().tenantBResults())
            .extracting(TenantKnowledgeService.KnowledgeHit::id)
            .containsExactly("doc-b");
        assertThat(dashboard.crossTenantDenied().errorCode()).isEqualTo("CROSS_TENANT_DENIED");
        assertThat(dashboard.writeActionPreview().confirmationRequired()).isTrue();
        assertThat(dashboard.deletionPreview().documentIds()).containsExactly("doc-b", "doc-b-keys");
        assertThat(dashboard.boundaryProof().passed()).isTrue();
        assertThat(dashboard.boundaryProof().checks())
            .extracting(TenantKnowledgeService.ProofCheck::id)
            .containsExactly(
                "tenant-a-search-is-scoped",
                "tenant-b-search-is-scoped",
                "admin-catalog-is-broader",
                "cross-tenant-write-denied",
                "same-tenant-write-confirmation",
                "tenant-delete-is-scoped"
            );
    }

    @Test
    void tenantDeletionRemovesOnlyTargetTenant() {
        TenantKnowledgeService.TenantDeletionResult result = service.deleteTenant(
            new TenantKnowledgeService.UserContext("platform", "ADMIN"),
            "tenant-b"
        );

        assertThat(result.success()).isTrue();
        assertThat(result.deletedIds()).containsExactly("doc-b", "doc-b-keys");
        assertThat(result.message()).contains("Other tenant documents remain isolated");
        assertThat(result.policyDecision()).isEqualTo("APPROVED");
        assertThat(result.remainingTenantIds()).containsExactly("platform", "tenant-a");
        assertThat(service.search(new TenantKnowledgeService.UserContext("tenant-a", "USER"), "VPN"))
            .extracting(TenantKnowledgeService.KnowledgeHit::id)
            .containsExactly("doc-a");
        assertThat(service.search(new TenantKnowledgeService.UserContext("tenant-b", "USER"), "VPN")).isEmpty();
    }

    @Test
    void sessionScopedDeletionDoesNotAffectOtherVisitors() {
        service.dashboard("browser-a");
        service.dashboard("browser-b");

        TenantKnowledgeService.TenantDeletionResult result = service.deleteTenant(
            "browser-a",
            new TenantKnowledgeService.UserContext("platform", "ADMIN"),
            "tenant-b"
        );

        assertThat(result.success()).isTrue();
        assertThat(service.search("browser-a", new TenantKnowledgeService.UserContext("tenant-b", "USER"), "VPN"))
            .isEmpty();
        assertThat(service.search("browser-b", new TenantKnowledgeService.UserContext("tenant-b", "USER"), "VPN"))
            .extracting(TenantKnowledgeService.KnowledgeHit::id)
            .containsExactly("doc-b");
        assertThat(service.dashboard("browser-a").session().isolated()).isTrue();
        assertThat(service.dashboard("browser-a").session().ttlHours()).isEqualTo(6);
    }
}
