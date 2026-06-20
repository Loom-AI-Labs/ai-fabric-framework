package ai.fabric.retention;

import ai.fabric.governance.catalog.IndexCatalog;
import ai.fabric.governance.catalog.IndexCatalogEntry;
import ai.fabric.governance.catalog.IndexCatalogScanPage;
import ai.fabric.governance.catalog.IndexCatalogScanRequest;
import ai.fabric.governance.catalog.disabled.DisabledIndexCatalog;
import ai.fabric.governance.config.AIGovernanceProperties;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.retention.policy.RetentionPolicyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetentionCleanupSchedulerTest {

    @Mock
    private IndexCatalog indexCatalog;
    @Mock
    private VectorDatabaseService vectorDatabaseService;
    @Mock
    private RetentionPolicyProvider policyProvider;

    private Clock clock;
    private AIGovernanceProperties properties;
    private RetentionCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2025-01-10T00:00:00Z"), ZoneOffset.UTC);

        properties = new AIGovernanceProperties();
        properties.setEnabled(true);
        properties.getRetention().setEnabled(true);
        properties.getRetention().setEntityTypes(List.of("product"));
        properties.getRetention().setRetentionDays(Map.of("product", 1));
        properties.getRetention().setScanLimit(50);

        scheduler = new RetentionCleanupScheduler(
            properties,
            indexCatalog,
            vectorDatabaseService,
            emptyProvider(),
            clock
        );
    }

    @Test
    void deletesEntriesOlderThanRetentionDays() {
        IndexCatalogEntry oldEntry = IndexCatalogEntry.builder()
            .entityType("product")
            .entityId("p-1")
            .indexedCreatedAt(LocalDateTime.of(2025, 1, 1, 0, 0))
            .metadata(Map.of("dataClassification", "default"))
            .build();

        IndexCatalogEntry freshEntry = IndexCatalogEntry.builder()
            .entityType("product")
            .entityId("p-2")
            .indexedCreatedAt(LocalDateTime.of(2025, 1, 10, 0, 0))
            .metadata(Map.of("dataClassification", "default"))
            .build();

        when(indexCatalog.scan(any(IndexCatalogScanRequest.class))).thenReturn(IndexCatalogScanPage.builder()
            .entries(List.of(oldEntry, freshEntry))
            .hasMore(false)
            .nextCursor(null)
            .build());
        when(vectorDatabaseService.removeVector("product", "p-1")).thenReturn(true);

        RetentionCleanupResult result = scheduler.runCleanupByRetentionPolicy();

        assertThat(result.getStatus()).isEqualTo(RetentionCleanupResult.Status.COMPLETED);
        assertThat(result.getEntityTypesScanned()).isEqualTo(1);
        assertThat(result.getEntriesEvaluated()).isEqualTo(2);
        assertThat(result.getEntriesEligible()).isEqualTo(1);
        assertThat(result.getVectorsDeleted()).isEqualTo(1);
        assertThat(result.getCleanupFailures()).isZero();
        assertThat(result.getFailureMessages()).isEmpty();
        verify(vectorDatabaseService).removeVector("product", "p-1");
        verify(vectorDatabaseService, never()).removeVector("product", "p-2");

        ArgumentCaptor<IndexCatalogScanRequest> requestCaptor = ArgumentCaptor.forClass(IndexCatalogScanRequest.class);
        verify(indexCatalog).scan(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getEntityType()).isEqualTo("product");
        assertThat(requestCaptor.getValue().getLimit()).isEqualTo(50);
    }

    @Test
    void skipsCleanupWhenCatalogIsDisabled() {
        RetentionCleanupScheduler disabledCatalogScheduler = new RetentionCleanupScheduler(
            properties,
            new DisabledIndexCatalog(),
            vectorDatabaseService,
            emptyProvider(),
            clock
        );

        RetentionCleanupResult result = disabledCatalogScheduler.runCleanupByRetentionPolicy();

        assertThat(result.getStatus()).isEqualTo(RetentionCleanupResult.Status.SKIPPED);
        assertThat(result.getMessage()).contains("IndexCatalog is DISABLED");
        verifyNoInteractions(vectorDatabaseService);
    }

    @Test
    void reportsPartialWhenVectorDeletionFails() {
        IndexCatalogEntry oldEntry = IndexCatalogEntry.builder()
            .entityType("product")
            .entityId("p-1")
            .indexedCreatedAt(LocalDateTime.of(2025, 1, 1, 0, 0))
            .metadata(Map.of("dataClassification", "default"))
            .build();

        when(indexCatalog.scan(any(IndexCatalogScanRequest.class))).thenReturn(IndexCatalogScanPage.builder()
            .entries(List.of(oldEntry))
            .hasMore(false)
            .nextCursor(null)
            .build());
        when(vectorDatabaseService.removeVector("product", "p-1"))
            .thenThrow(new IllegalStateException("vector unavailable"));

        RetentionCleanupResult result = scheduler.runCleanupByRetentionPolicy();

        assertThat(result.getStatus()).isEqualTo(RetentionCleanupResult.Status.PARTIAL);
        assertThat(result.getEntriesEvaluated()).isEqualTo(1);
        assertThat(result.getEntriesEligible()).isEqualTo(1);
        assertThat(result.getVectorsDeleted()).isZero();
        assertThat(result.getCleanupFailures()).isEqualTo(1);
        assertThat(result.getFailureMessages())
            .containsExactly("vector removal failed for product::p-1: vector unavailable");
        assertThat(result.getMessage()).contains("Retention cleanup completed with 1 non-fatal failure");
    }

    @Test
    void reportsPartialWhenCatalogScanFails() {
        when(indexCatalog.scan(any(IndexCatalogScanRequest.class)))
            .thenThrow(new IllegalStateException("catalog unavailable"));

        RetentionCleanupResult result = scheduler.runCleanupByRetentionPolicy();

        assertThat(result.getStatus()).isEqualTo(RetentionCleanupResult.Status.PARTIAL);
        assertThat(result.getEntityTypesScanned()).isEqualTo(1);
        assertThat(result.getEntriesEvaluated()).isZero();
        assertThat(result.getVectorsDeleted()).isZero();
        assertThat(result.getCleanupFailures()).isEqualTo(1);
        assertThat(result.getFailureMessages())
            .containsExactly("catalog scan failed for product: catalog unavailable");
    }

    @Test
    void reportsPartialWhenPolicyHookFails() {
        RetentionCleanupScheduler policyScheduler = new RetentionCleanupScheduler(
            properties,
            indexCatalog,
            vectorDatabaseService,
            provider(policyProvider),
            clock
        );
        IndexCatalogEntry oldEntry = IndexCatalogEntry.builder()
            .entityType("product")
            .entityId("p-1")
            .indexedCreatedAt(LocalDateTime.of(2025, 1, 1, 0, 0))
            .metadata(Map.of("dataClassification", "CONFIDENTIAL"))
            .build();

        when(indexCatalog.scan(any(IndexCatalogScanRequest.class))).thenReturn(IndexCatalogScanPage.builder()
            .entries(List.of(oldEntry))
            .hasMore(false)
            .nextCursor(null)
            .build());
        when(policyProvider.getRetentionDays("CONFIDENTIAL", "product")).thenReturn(1);
        when(policyProvider.shouldDelete(oldEntry)).thenThrow(new IllegalStateException("policy unavailable"));

        RetentionCleanupResult result = policyScheduler.runCleanupByRetentionPolicy();

        assertThat(result.getStatus()).isEqualTo(RetentionCleanupResult.Status.PARTIAL);
        assertThat(result.getEntriesEvaluated()).isEqualTo(1);
        assertThat(result.getEntriesEligible()).isEqualTo(1);
        assertThat(result.getVectorsDeleted()).isZero();
        assertThat(result.getCleanupFailures()).isEqualTo(1);
        assertThat(result.getFailureMessages())
            .containsExactly("retention shouldDelete failed for product::p-1: policy unavailable");
        verifyNoInteractions(vectorDatabaseService);
    }

    private static ObjectProvider<RetentionPolicyProvider> emptyProvider() {
        return provider(null);
    }

    private static ObjectProvider<RetentionPolicyProvider> provider(RetentionPolicyProvider retentionPolicyProvider) {
        return new ObjectProvider<>() {
            @Override
            public RetentionPolicyProvider getObject(Object... args) {
                return retentionPolicyProvider;
            }

            @Override
            public RetentionPolicyProvider getObject() {
                return retentionPolicyProvider;
            }

            @Override
            public RetentionPolicyProvider getIfAvailable() {
                return retentionPolicyProvider;
            }

            @Override
            public RetentionPolicyProvider getIfAvailable(java.util.function.Supplier<RetentionPolicyProvider> defaultSupplier) {
                return retentionPolicyProvider != null ? retentionPolicyProvider : defaultSupplier != null ? defaultSupplier.get() : null;
            }

            @Override
            public RetentionPolicyProvider getIfUnique() {
                return retentionPolicyProvider;
            }

            @Override
            public RetentionPolicyProvider getIfUnique(java.util.function.Supplier<RetentionPolicyProvider> defaultSupplier) {
                return retentionPolicyProvider != null ? retentionPolicyProvider : defaultSupplier != null ? defaultSupplier.get() : null;
            }

            @Override
            public java.util.stream.Stream<RetentionPolicyProvider> orderedStream() {
                return retentionPolicyProvider == null
                    ? java.util.stream.Stream.empty()
                    : java.util.stream.Stream.of(retentionPolicyProvider);
            }

            @Override
            public java.util.stream.Stream<RetentionPolicyProvider> stream() {
                return retentionPolicyProvider == null
                    ? java.util.stream.Stream.empty()
                    : java.util.stream.Stream.of(retentionPolicyProvider);
            }
        };
    }
}
