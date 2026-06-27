package ai.fabric.governance.vector;

import ai.fabric.dto.VectorRecord;
import ai.fabric.governance.catalog.IndexCatalog;
import ai.fabric.governance.catalog.IndexCatalogEntry;
import ai.fabric.governance.metadata.GovernanceVectorMetadata;
import ai.fabric.rag.VectorDatabaseService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GovernanceVectorDatabaseServiceDecoratorTest {

    @Test
    void storeVectorShouldEnrichMetadataAndUpsertCatalog() {
        VectorDatabaseService delegate = mock(VectorDatabaseService.class);
        IndexCatalog catalog = mock(IndexCatalog.class);

        when(delegate.getVectorByEntity("product", "1")).thenReturn(Optional.empty());
        when(delegate.storeVector(eq("product"), eq("1"), eq("hello"), eq(List.of(1.0d)), anyMap()))
            .thenReturn("vec-1");

        GovernanceVectorDatabaseServiceDecorator decorator = new GovernanceVectorDatabaseServiceDecorator(
            delegate,
            providerOf(catalog)
        );

        String result = decorator.storeVector("product", "1", "hello", List.of(1.0d), Map.of("brand", "Nike"));
        assertThat(result).isEqualTo("vec-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(delegate).storeVector(eq("product"), eq("1"), eq("hello"), eq(List.of(1.0d)), metaCaptor.capture());
        Map<String, Object> enriched = metaCaptor.getValue();
        assertThat(enriched).containsEntry("brand", "Nike");
        assertThat(enriched).containsKeys(GovernanceVectorMetadata.INDEXED_CREATED_AT, GovernanceVectorMetadata.INDEXED_UPDATED_AT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<IndexCatalogEntry> entryCaptor = ArgumentCaptor.forClass(IndexCatalogEntry.class);
        verify(catalog).upsert(entryCaptor.capture());
        IndexCatalogEntry entry = entryCaptor.getValue();
        assertThat(entry.getEntityType()).isEqualTo("product");
        assertThat(entry.getEntityId()).isEqualTo("1");
        assertThat(entry.getVectorId()).isEqualTo("vec-1");
        assertThat(entry.getMetadata()).containsEntry("brand", "Nike");
    }

    @Test
    void storeVectorShouldRetryCatalogUpsert() {
        VectorDatabaseService delegate = mock(VectorDatabaseService.class);
        IndexCatalog catalog = mock(IndexCatalog.class);

        when(delegate.getVectorByEntity("product", "1")).thenReturn(Optional.empty());
        when(delegate.storeVector(eq("product"), eq("1"), eq("hello"), eq(List.of(1.0d)), anyMap()))
            .thenReturn("vec-1");

        doThrow(new RuntimeException("catalog down"))
            .doNothing()
            .when(catalog)
            .upsert(any(IndexCatalogEntry.class));

        GovernanceVectorDatabaseServiceDecorator decorator = new GovernanceVectorDatabaseServiceDecorator(
            delegate,
            providerOf(catalog)
        );

        String result = decorator.storeVector("product", "1", "hello", List.of(1.0d), Map.of());
        assertThat(result).isEqualTo("vec-1");
        verify(catalog, times(2)).upsert(any(IndexCatalogEntry.class));
    }

    @Test
    void storeVectorShouldPropagateCatalogFailureAfterRetries() {
        VectorDatabaseService delegate = mock(VectorDatabaseService.class);
        IndexCatalog catalog = mock(IndexCatalog.class);

        when(delegate.getVectorByEntity("product", "1")).thenReturn(Optional.empty());
        when(delegate.storeVector(eq("product"), eq("1"), eq("hello"), eq(List.of(1.0d)), anyMap()))
            .thenReturn("vec-1");
        doThrow(new RuntimeException("catalog down")).when(catalog).upsert(any(IndexCatalogEntry.class));

        GovernanceVectorDatabaseServiceDecorator decorator = new GovernanceVectorDatabaseServiceDecorator(
            delegate,
            providerOf(catalog)
        );

        assertThatThrownBy(() -> decorator.storeVector("product", "1", "hello", List.of(1.0d), Map.of()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("catalog down");

        verify(delegate, times(1)).storeVector(eq("product"), eq("1"), eq("hello"), eq(List.of(1.0d)), anyMap());
        verify(catalog, times(3)).upsert(any(IndexCatalogEntry.class));
    }

    @Test
    void removeVectorShouldRetryCatalogDelete() {
        VectorDatabaseService delegate = mock(VectorDatabaseService.class);
        IndexCatalog catalog = mock(IndexCatalog.class);

        when(delegate.removeVector("product", "1")).thenReturn(true);
        doThrow(new RuntimeException("catalog busy"))
            .doNothing()
            .when(catalog)
            .delete("product", "1");

        GovernanceVectorDatabaseServiceDecorator decorator = new GovernanceVectorDatabaseServiceDecorator(
            delegate,
            providerOf(catalog)
        );

        boolean removed = decorator.removeVector("product", "1");
        assertThat(removed).isTrue();
        verify(catalog, times(2)).delete("product", "1");
    }

    @Test
    void removeVectorByIdShouldDeleteCatalogUsingResolvedEntityInfo() {
        VectorDatabaseService delegate = mock(VectorDatabaseService.class);
        IndexCatalog catalog = mock(IndexCatalog.class);

        VectorRecord existing = VectorRecord.builder()
            .vectorId("vec-1")
            .entityType("product")
            .entityId("1")
            .build();
        when(delegate.getVector("vec-1")).thenReturn(Optional.of(existing));
        when(delegate.removeVectorById("vec-1")).thenReturn(true);

        GovernanceVectorDatabaseServiceDecorator decorator = new GovernanceVectorDatabaseServiceDecorator(
            delegate,
            providerOf(catalog)
        );

        assertThat(decorator.removeVectorById("vec-1")).isTrue();
        verify(catalog).delete("product", "1");
    }

    @Test
    void adminDiagnosticsShouldPreserveDelegateScopeAndAddGovernanceMarkers() {
        VectorDatabaseService delegate = mock(VectorDatabaseService.class);
        IndexCatalog catalog = mock(IndexCatalog.class);

        when(delegate.adminDiagnostics()).thenReturn(Map.of(
            "provider", "pinecone",
            "providerClass", "ai.fabric.vector.pinecone.PineconeVectorDatabaseService",
            "scopePrefix", "customer-a--tenant-b",
            "supportsSearchMetadataFiltering", true,
            "supportsScanMetadataFiltering", false,
            "metadataFilteredSearch", true,
            "metadataFilteredScan", false
        ));

        GovernanceVectorDatabaseServiceDecorator decorator = new GovernanceVectorDatabaseServiceDecorator(
            delegate,
            providerOf(catalog)
        );

        assertThat(decorator.adminDiagnostics())
            .containsEntry("provider", "pinecone")
            .containsEntry("providerClass", "ai.fabric.vector.pinecone.PineconeVectorDatabaseService")
            .containsEntry("scopePrefix", "customer-a--tenant-b")
            .containsEntry("supportsSearchMetadataFiltering", true)
            .containsEntry("supportsScanMetadataFiltering", false)
            .containsEntry("metadataFilteredSearch", true)
            .containsEntry("metadataFilteredScan", false)
            .containsEntry("governanceDecorated", true)
            .containsEntry("governanceCatalogWriteThrough", true)
            .containsEntry("governanceDecoratorClass", GovernanceVectorDatabaseServiceDecorator.class.getName());
    }

    private static ObjectProvider<IndexCatalog> providerOf(IndexCatalog catalog) {
        return new ObjectProvider<>() {
            @Override
            public IndexCatalog getObject(Object... args) {
                return catalog;
            }

            @Override
            public IndexCatalog getIfAvailable() {
                return catalog;
            }

            @Override
            public IndexCatalog getIfUnique() {
                return catalog;
            }

            @Override
            public IndexCatalog getObject() {
                return catalog;
            }

            @Override
            public java.util.stream.Stream<IndexCatalog> stream() {
                return java.util.stream.Stream.of(catalog);
            }

            @Override
            public java.util.stream.Stream<IndexCatalog> orderedStream() {
                return java.util.stream.Stream.of(catalog);
            }
        };
    }
}
