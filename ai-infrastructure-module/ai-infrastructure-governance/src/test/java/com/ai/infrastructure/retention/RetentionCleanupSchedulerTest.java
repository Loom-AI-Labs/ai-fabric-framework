package com.ai.infrastructure.retention;

import com.ai.infrastructure.governance.catalog.IndexCatalog;
import com.ai.infrastructure.governance.catalog.IndexCatalogEntry;
import com.ai.infrastructure.governance.catalog.IndexCatalogScanPage;
import com.ai.infrastructure.governance.catalog.IndexCatalogScanRequest;
import com.ai.infrastructure.governance.config.AIGovernanceProperties;
import com.ai.infrastructure.rag.VectorDatabaseService;
import com.ai.infrastructure.retention.policy.RetentionPolicyProvider;
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

        scheduler.cleanupByRetentionPolicy();

        verify(vectorDatabaseService).removeVector("product", "p-1");
        verify(vectorDatabaseService, never()).removeVector("product", "p-2");

        ArgumentCaptor<IndexCatalogScanRequest> requestCaptor = ArgumentCaptor.forClass(IndexCatalogScanRequest.class);
        verify(indexCatalog).scan(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getEntityType()).isEqualTo("product");
        assertThat(requestCaptor.getValue().getLimit()).isEqualTo(50);
    }

    private static ObjectProvider<RetentionPolicyProvider> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public RetentionPolicyProvider getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public RetentionPolicyProvider getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public RetentionPolicyProvider getIfAvailable() {
                return null;
            }

            @Override
            public RetentionPolicyProvider getIfAvailable(java.util.function.Supplier<RetentionPolicyProvider> defaultSupplier) {
                return defaultSupplier != null ? defaultSupplier.get() : null;
            }

            @Override
            public RetentionPolicyProvider getIfUnique() {
                return null;
            }

            @Override
            public RetentionPolicyProvider getIfUnique(java.util.function.Supplier<RetentionPolicyProvider> defaultSupplier) {
                return defaultSupplier != null ? defaultSupplier.get() : null;
            }

            @Override
            public java.util.stream.Stream<RetentionPolicyProvider> orderedStream() {
                return java.util.stream.Stream.empty();
            }

            @Override
            public java.util.stream.Stream<RetentionPolicyProvider> stream() {
                return java.util.stream.Stream.empty();
            }
        };
    }
}

