package com.ai.infrastructure.intent;

import com.ai.infrastructure.config.AIEntityConfigurationLoader;
import com.ai.infrastructure.dto.VectorRecord;
import com.ai.infrastructure.dto.VectorScanPage;
import com.ai.infrastructure.rag.VectorDatabaseService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseOverviewServiceTest {

    @Test
    void shouldBuildOverviewFromStatisticsWithoutExpensiveVectorWalk() {
        VectorDatabaseService vectorDatabaseService = Mockito.mock(VectorDatabaseService.class);
        AIEntityConfigurationLoader configurationLoader = Mockito.mock(AIEntityConfigurationLoader.class);
        LocalDateTime updatedAt = LocalDateTime.now();

        Mockito.when(vectorDatabaseService.getStatistics()).thenReturn(Map.of(
            "totalVectors", 12,
            "entityTypeCounts", Map.of("faq", 7, "policies", 5),
            "lastIndexUpdateTime", updatedAt
        ));

        KnowledgeBaseOverviewService service = new KnowledgeBaseOverviewService(vectorDatabaseService, configurationLoader);

        KnowledgeBaseOverview overview = service.getOverview();

        assertThat(overview.getTotalIndexedDocuments()).isEqualTo(12);
        assertThat(overview.getDocumentsByType()).containsEntry("faq", 7L);
        assertThat(overview.getLastIndexUpdateTime()).isEqualTo(updatedAt);
        Mockito.verify(vectorDatabaseService, Mockito.never()).getVectorsByEntityType(Mockito.anyString());
    }

    @Test
    void shouldUsePresenceChecksWhenProviderCountsAreExpensive() {
        VectorDatabaseService vectorDatabaseService = Mockito.mock(VectorDatabaseService.class);
        AIEntityConfigurationLoader configurationLoader = Mockito.mock(AIEntityConfigurationLoader.class);

        Mockito.when(vectorDatabaseService.getStatistics()).thenReturn(Map.of());
        Mockito.when(vectorDatabaseService.supportsEfficientEntityTypeCount()).thenReturn(false);
        Mockito.when(vectorDatabaseService.supportsVectorScan()).thenReturn(true);
        Mockito.when(configurationLoader.getSupportedEntityTypes()).thenReturn(Set.of("product", "review"));
        Mockito.when(vectorDatabaseService.scan(Mockito.argThat(request ->
            request != null && "product".equals(request.getEntityType()) && Integer.valueOf(1).equals(request.getLimit()))))
            .thenReturn(VectorScanPage.builder()
                .vectors(List.of(VectorRecord.builder().entityType("product").entityId("sku-1").build()))
                .hasMore(false)
                .build());
        Mockito.when(vectorDatabaseService.scan(Mockito.argThat(request ->
            request != null && "review".equals(request.getEntityType()) && Integer.valueOf(1).equals(request.getLimit()))))
            .thenReturn(VectorScanPage.builder()
                .vectors(List.of())
                .hasMore(false)
                .build());

        KnowledgeBaseOverviewService service = new KnowledgeBaseOverviewService(vectorDatabaseService, configurationLoader);

        KnowledgeBaseOverview overview = service.getOverview();

        assertThat(overview.getEntityTypes()).containsExactly("product");
        assertThat(overview.getDocumentsByType()).isEmpty();
        assertThat(overview.getTotalIndexedDocuments()).isZero();
        Mockito.verify(vectorDatabaseService, Mockito.never()).getVectorCountByEntityType(Mockito.anyString());
    }

    @Test
    void shouldCacheOverviewWithinTtl() {
        VectorDatabaseService vectorDatabaseService = Mockito.mock(VectorDatabaseService.class);
        AIEntityConfigurationLoader configurationLoader = Mockito.mock(AIEntityConfigurationLoader.class);

        Mockito.when(vectorDatabaseService.getStatistics()).thenReturn(Map.of(
            "totalVectors", 3,
            "entityTypeCounts", Map.of("faq", 3)
        ));

        KnowledgeBaseOverviewService service = new KnowledgeBaseOverviewService(vectorDatabaseService, configurationLoader);

        KnowledgeBaseOverview first = service.getOverview();
        KnowledgeBaseOverview second = service.getOverview();

        assertThat(second).isEqualTo(first);
        Mockito.verify(vectorDatabaseService, Mockito.times(1)).getStatistics();
    }

    @Test
    void shouldCacheExpensiveProviderOverviewWithoutRepeatingPresenceChecks() {
        VectorDatabaseService vectorDatabaseService = Mockito.mock(VectorDatabaseService.class);
        AIEntityConfigurationLoader configurationLoader = Mockito.mock(AIEntityConfigurationLoader.class);

        Mockito.when(vectorDatabaseService.getStatistics()).thenReturn(Map.of());
        Mockito.when(vectorDatabaseService.supportsEfficientEntityTypeCount()).thenReturn(false);
        Mockito.when(vectorDatabaseService.supportsVectorScan()).thenReturn(true);
        Mockito.when(configurationLoader.getSupportedEntityTypes()).thenReturn(Set.of("product"));
        Mockito.when(vectorDatabaseService.scan(Mockito.argThat(request ->
            request != null && "product".equals(request.getEntityType()) && Integer.valueOf(1).equals(request.getLimit()))))
            .thenReturn(VectorScanPage.builder()
                .vectors(List.of(VectorRecord.builder().entityType("product").entityId("sku-1").build()))
                .hasMore(false)
                .build());

        KnowledgeBaseOverviewService service = new KnowledgeBaseOverviewService(vectorDatabaseService, configurationLoader);

        KnowledgeBaseOverview first = service.getOverview();
        KnowledgeBaseOverview second = service.getOverview();

        assertThat(second).isEqualTo(first);
        Mockito.verify(vectorDatabaseService, Mockito.times(1)).scan(Mockito.any());
    }
}
