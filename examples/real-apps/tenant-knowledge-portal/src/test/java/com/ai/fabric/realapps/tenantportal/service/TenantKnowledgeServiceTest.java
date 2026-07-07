package com.ai.fabric.realapps.tenantportal.service;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.vector.memory.InMemoryVectorDatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantKnowledgeServiceTest {

    private TenantKnowledgeService service;

    @BeforeEach
    void setUp() {
        service = new TenantKnowledgeService();
    }

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

    @Test
    void aiFabricQueryUsesMetadataFilterAndReturnsOnlyCallerTenantEvidence() {
        VectorDatabaseService vectorDatabaseService = new InMemoryVectorDatabaseService(new AIProviderConfig());
        AICoreService aiCoreService = aiCoreServiceBackedBy(vectorDatabaseService);
        TenantKnowledgeService aiService = new TenantKnowledgeService(provider(aiCoreService), provider(vectorDatabaseService));

        TenantKnowledgeService.TenantRagResponse response = aiService.queryTenantKnowledge(
            "browser-ai",
            new TenantKnowledgeService.TenantQueryRequest("tenant-a", "USER", "VPN", 5)
        );

        assertThat(response.success()).isTrue();
        assertThat(response.metadataFilter())
            .containsEntry("sessionId", "browser-ai")
            .containsEntry("tenantId", "tenant-a")
            .containsEntry("visibleToUser", true);
        assertThat(response.hits())
            .extracting(TenantKnowledgeService.TenantRagHit::id)
            .contains("doc-a")
            .doesNotContain("doc-b", "doc-b-keys", "doc-admin", "doc-platform");
        assertThat(response.hits())
            .allSatisfy(hit -> {
                assertThat(hit.tenantId()).isEqualTo("tenant-a");
                assertThat(hit.visibility()).isNotEqualTo("restricted");
            });
        assertThat(response.boundaryProof().passed()).isTrue();
        assertThat(response.indexProof().available()).isTrue();
        assertThat(response.indexProof().indexedByTenant())
            .containsEntry("tenant-a", 3L)
            .containsEntry("tenant-b", 2L)
            .containsEntry("platform", 1L);

        ArgumentCaptor<AISearchRequest> requestCaptor = ArgumentCaptor.forClass(AISearchRequest.class);
        verify(aiCoreService).performSearch(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getMetadata())
            .containsEntry("sessionId", "browser-ai")
            .containsEntry("tenantId", "tenant-a")
            .containsEntry("visibleToUser", true);
    }

    @Test
    void aiFabricQueryFailsClosedIfReturnedEvidenceDoesNotPassAppBoundaryCheck() {
        VectorDatabaseService vectorDatabaseService = new InMemoryVectorDatabaseService(new AIProviderConfig());
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateEmbedding(any())).thenReturn(testEmbedding());
        when(aiCoreService.performSearch(any())).thenReturn(AISearchResponse.builder()
            .query("VPN")
            .results(List.of(Map.of(
                "id", "browser-ai:doc-b",
                "content", "Tenant B VPN requires hardware keys.",
                "score", 0.99d,
                "metadata", """
                    {"sessionId":"browser-ai","documentId":"doc-b","tenantId":"tenant-b","visibility":"internal","visibleToUser":true}
                    """
            )))
            .totalResults(1)
            .build());
        TenantKnowledgeService aiService = new TenantKnowledgeService(provider(aiCoreService), provider(vectorDatabaseService));

        TenantKnowledgeService.TenantRagResponse response = aiService.queryTenantKnowledge(
            "browser-ai",
            new TenantKnowledgeService.TenantQueryRequest("tenant-a", "USER", "VPN", 5)
        );

        assertThat(response.success()).isTrue();
        assertThat(response.hits()).isEmpty();
        assertThat(response.answer()).contains("retrieved no allowed evidence");
        assertThat(response.boundaryProof().passed()).isTrue();
    }

    @Test
    void tenantDeletionAlsoRemovesIndexedVectorsForTargetTenant() {
        VectorDatabaseService vectorDatabaseService = new InMemoryVectorDatabaseService(new AIProviderConfig());
        AICoreService aiCoreService = aiCoreServiceBackedBy(vectorDatabaseService);
        TenantKnowledgeService aiService = new TenantKnowledgeService(provider(aiCoreService), provider(vectorDatabaseService));
        aiService.seedAiIndex("browser-delete");

        TenantKnowledgeService.TenantDeletionResult result = aiService.deleteTenant(
            "browser-delete",
            new TenantKnowledgeService.UserContext("platform", "ADMIN"),
            "tenant-b"
        );

        assertThat(result.success()).isTrue();
        assertThat(result.deletedIds()).containsExactly("doc-b", "doc-b-keys");
        assertThat(result.deletedVectors()).isEqualTo(2);
        assertThat(result.deletedVectorEntityIds()).containsExactlyInAnyOrder("browser-delete:doc-b", "browser-delete:doc-b-keys");
        assertThat(result.indexProof().indexedByTenant()).doesNotContainKey("tenant-b");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static AICoreService aiCoreServiceBackedBy(VectorDatabaseService vectorDatabaseService) {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateEmbedding(any())).thenReturn(testEmbedding());
        when(aiCoreService.performSearch(any())).thenAnswer(invocation ->
            vectorDatabaseService.search(testEmbedding().getEmbedding(), invocation.getArgument(0))
        );
        return aiCoreService;
    }

    private static AIEmbeddingResponse testEmbedding() {
        return AIEmbeddingResponse.builder()
            .embedding(List.of(1.0d, 0.0d, 0.0d))
            .dimensions(3)
            .model("test")
            .processingTimeMs(0L)
            .build();
    }
}
