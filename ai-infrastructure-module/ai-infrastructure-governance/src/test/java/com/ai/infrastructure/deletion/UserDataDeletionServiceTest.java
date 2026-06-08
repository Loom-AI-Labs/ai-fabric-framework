package com.ai.infrastructure.deletion;

import com.ai.infrastructure.deletion.policy.UserDataDeletionProvider;
import com.ai.infrastructure.deletion.policy.UserDataDeletionProvider.UserEntityReference;
import com.ai.infrastructure.deletion.port.BehaviorDeletionPort;
import com.ai.infrastructure.governance.catalog.IndexCatalog;
import com.ai.infrastructure.governance.catalog.IndexCatalogEntry;
import com.ai.infrastructure.rag.VectorDatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserDataDeletionServiceTest {

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";

    @Mock
    private VectorDatabaseService vectorDatabaseService;
    @Mock
    private IndexCatalog indexCatalog;
    @Mock
    private UserDataDeletionProvider provider;
    @Mock
    private BehaviorDeletionPort behaviorDeletionPort;

    private Clock clock;
    private UserDataDeletionService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);
        service = new UserDataDeletionService(
            vectorDatabaseService,
            indexCatalog,
            clock,
            provider,
            behaviorDeletionPort
        );
    }

    @Test
    void shouldDeleteDataWhenProviderApproves() {
        when(provider.canDeleteUser(USER_ID)).thenReturn(true);
        when(provider.findIndexedEntities(USER_ID))
            .thenReturn(List.of(new UserEntityReference("doc", "id-1")));
        when(vectorDatabaseService.removeVector("doc", "id-1")).thenReturn(true);
        when(behaviorDeletionPort.deleteUserBehaviors(UUID.fromString(USER_ID))).thenReturn(1);
        when(provider.deleteUserDomainData(USER_ID)).thenReturn(3);

        UserDataDeletionResult result = service.deleteUser(USER_ID);

        assertThat(result.getStatus()).isEqualTo(UserDataDeletionResult.Status.COMPLETED);
        assertThat(result.getBehaviorsDeleted()).isEqualTo(1);
        assertThat(result.getIndexedEntitiesDeleted()).isEqualTo(1);
        assertThat(result.getVectorsDeleted()).isEqualTo(1);
        assertThat(result.getDomainRecordsDeleted()).isEqualTo(3);
        assertThat(result.getAuditEntriesDeleted()).isEqualTo(0);

        verify(behaviorDeletionPort).deleteUserBehaviors(UUID.fromString(USER_ID));
        verify(indexCatalog).delete("doc", "id-1");
        verify(provider).notifyAfterDeletion(USER_ID);
    }

    @Test
    void shouldUseCatalogSnippetFallbackWhenProviderDoesNotEnumerateEntities() {
        when(provider.canDeleteUser(USER_ID)).thenReturn(true);
        when(provider.findIndexedEntities(USER_ID)).thenReturn(List.of());
        when(indexCatalog.findByMetadataContainingSnippet("\"" + USER_ID + "\"", 2_000))
            .thenReturn(List.of(IndexCatalogEntry.builder()
                .entityType("doc")
                .entityId("id-9")
                .vectorId("vec-9")
                .build()));
        when(vectorDatabaseService.removeVector("doc", "id-9")).thenReturn(true);

        UserDataDeletionResult result = service.deleteUser(USER_ID);

        assertThat(result.getStatus()).isEqualTo(UserDataDeletionResult.Status.COMPLETED);
        assertThat(result.getIndexedEntitiesDeleted()).isEqualTo(1);
        assertThat(result.getVectorsDeleted()).isEqualTo(1);

        verify(indexCatalog).delete("doc", "id-9");
    }

    @Test
    void shouldSkipWhenProviderBlocksDeletion() {
        when(provider.canDeleteUser(USER_ID)).thenReturn(false);

        UserDataDeletionResult result = service.deleteUser(USER_ID);

        assertThat(result.getStatus()).isEqualTo(UserDataDeletionResult.Status.SKIPPED);
        verifyNoInteractions(indexCatalog, vectorDatabaseService, behaviorDeletionPort);
    }

    @Test
    void shouldThrowWhenProviderMissing() {
        UserDataDeletionService noProviderService = new UserDataDeletionService(
            vectorDatabaseService,
            indexCatalog,
            clock,
            null,
            behaviorDeletionPort
        );

        assertThatThrownBy(() -> noProviderService.deleteUser(USER_ID))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("UserDataDeletionProvider");
    }
}
