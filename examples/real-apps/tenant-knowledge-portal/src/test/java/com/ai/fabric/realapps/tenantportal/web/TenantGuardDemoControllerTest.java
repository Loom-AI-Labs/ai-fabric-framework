package com.ai.fabric.realapps.tenantportal.web;

import ai.fabric.intent.action.ActionAccessMode;
import com.ai.fabric.realapps.tenantportal.service.TenantKnowledgeService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantGuardDemoControllerTest {

    private final TenantKnowledgeService service = new TenantKnowledgeService();
    private final TenantGuardDemoController controller = new TenantGuardDemoController(service);

    @Test
    void exposesDashboardAndResetForPublicDemo() {
        TenantKnowledgeService.TenantGuardDashboard dashboard = controller.dashboard();

        assertThat(dashboard.stats().totalDocuments()).isEqualTo(6);
        assertThat(dashboard.defaultComparison().tenantAResults())
            .extracting(TenantKnowledgeService.KnowledgeHit::tenantId)
            .containsOnly("tenant-a");

        controller.deleteTenant(new TenantGuardDemoController.TenantGuardDeleteRequest("platform", "ADMIN", "tenant-b"));
        assertThat(controller.dashboard().stats().totalDocuments()).isEqualTo(4);

        TenantKnowledgeService.TenantGuardDashboard reset = controller.reset();
        assertThat(reset.stats().totalDocuments()).isEqualTo(6);
    }

    @Test
    void exposesComparisonActionAndDeletionPaths() {
        TenantKnowledgeService.SearchComparison comparison = controller.compare("VPN");
        assertThat(comparison.tenantAResults())
            .extracting(TenantKnowledgeService.KnowledgeHit::id)
            .containsExactly("doc-a");
        assertThat(comparison.tenantBResults())
            .extracting(TenantKnowledgeService.KnowledgeHit::id)
            .containsExactly("doc-b");

        TenantKnowledgeService.ActionDecision rejected = controller.execute(
            new TenantGuardDemoController.TenantGuardActionRequest(
                "tenant-a",
                "USER",
                "archive_document",
                "doc-b",
                ActionAccessMode.WRITE_ONLY,
                true
            )
        );
        assertThat(rejected.errorCode()).isEqualTo("CROSS_TENANT_DENIED");

        TenantKnowledgeService.ActionDecision preview = controller.execute(
            new TenantGuardDemoController.TenantGuardActionRequest(
                "tenant-a",
                "ADMIN",
                "archive_document",
                "doc-a",
                ActionAccessMode.WRITE_ONLY,
                false
            )
        );
        assertThat(preview.confirmationRequired()).isTrue();

        TenantKnowledgeService.ActionDecision executed = controller.execute(
            new TenantGuardDemoController.TenantGuardActionRequest(
                "tenant-a",
                "ADMIN",
                "archive_document",
                "doc-a",
                ActionAccessMode.WRITE_ONLY,
                true
            )
        );
        assertThat(executed.success()).isTrue();

        TenantKnowledgeService.TenantDeletionResult deletion = controller.deleteTenant(
            new TenantGuardDemoController.TenantGuardDeleteRequest("platform", "ADMIN", "tenant-b")
        );
        assertThat(deletion.deletedIds()).containsExactly("doc-b", "doc-b-keys");
    }
}
