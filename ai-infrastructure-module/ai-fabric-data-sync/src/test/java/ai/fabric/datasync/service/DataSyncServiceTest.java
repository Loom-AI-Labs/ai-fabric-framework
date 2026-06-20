package ai.fabric.datasync.service;

import ai.fabric.access.AIAccessControlService;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.datasync.AIDataSyncProperties;
import ai.fabric.datasync.dto.DataSyncBatchRequest;
import ai.fabric.datasync.dto.DataSyncBatchResponse;
import ai.fabric.datasync.dto.DataSyncDeleteRequest;
import ai.fabric.datasync.dto.DataSyncIdentity;
import ai.fabric.datasync.dto.DataSyncOperation;
import ai.fabric.datasync.dto.DataSyncOperationResponse;
import ai.fabric.datasync.dto.DataSyncOperationType;
import ai.fabric.datasync.dto.DataSyncTrace;
import ai.fabric.datasync.dto.DataSyncUpsertRequest;
import ai.fabric.datasync.dto.DataSyncVerifiedAuthContext;
import ai.fabric.datasync.normalize.DataSyncEntityNormalizer;
import ai.fabric.dto.AIAccessControlRequest;
import ai.fabric.dto.AIAccessControlResponse;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

class DataSyncServiceTest {

    @Test
    void upsert_shouldStoreVector_whenAccessGranted() {
        AIDataSyncProperties props = new AIDataSyncProperties();
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        AIEmbeddingService embeddingService = mock(AIEmbeddingService.class);
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        AIAccessControlService accessControlService = mock(AIAccessControlService.class);

        AIEntityConfig config = AIEntityConfig.builder()
            .entityType("product")
            .indexable(true)
            .build();
        when(loader.getEntityConfig("product")).thenReturn(config);

        when(accessControlService.checkAccess(any())).thenReturn(AIAccessControlResponse.builder()
            .accessGranted(true)
            .build());

        when(embeddingService.generateEmbedding(any())).thenReturn(AIEmbeddingResponse.builder()
            .embedding(List.of(0.1, 0.2))
            .build());

        when(vectorManagementService.storeVector(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn("vec_1");

        DataSyncEntityNormalizer normalizer = new DataSyncEntityNormalizer(props, null);
        Clock clock = Clock.fixed(Instant.parse("2026-02-12T00:00:00Z"), ZoneOffset.UTC);

        DataSyncService service = new DataSyncService(
            props,
            loader,
            embeddingService,
            vectorManagementService,
            accessControlService,
            normalizer,
            clock
        );

        DataSyncTrace trace = verifiedTrace("system", null, "req1");

        DataSyncUpsertRequest request = new DataSyncUpsertRequest();
        request.setVectorSpace("product");
        request.setId("p1");
        request.setContent("hello");
        request.setTrace(trace);

        DataSyncOperationResponse response = service.upsert(request);

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getVectorId()).isEqualTo("vec_1");
        verify(vectorManagementService).storeVector(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void upsert_shouldFailClosed_whenAccessDenied() {
        AIDataSyncProperties props = new AIDataSyncProperties();
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        AIEmbeddingService embeddingService = mock(AIEmbeddingService.class);
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        AIAccessControlService accessControlService = mock(AIAccessControlService.class);

        when(loader.getEntityConfig("product")).thenReturn(AIEntityConfig.builder()
            .entityType("product")
            .indexable(true)
            .build());

        when(accessControlService.checkAccess(any())).thenReturn(AIAccessControlResponse.builder()
            .accessGranted(false)
            .build());

        DataSyncEntityNormalizer normalizer = new DataSyncEntityNormalizer(props, null);
        Clock clock = Clock.fixed(Instant.parse("2026-02-12T00:00:00Z"), ZoneOffset.UTC);

        DataSyncService service = new DataSyncService(
            props,
            loader,
            embeddingService,
            vectorManagementService,
            accessControlService,
            normalizer,
            clock
        );

        DataSyncTrace trace = verifiedTrace("system", null, "req1");

        DataSyncUpsertRequest request = new DataSyncUpsertRequest();
        request.setVectorSpace("product");
        request.setId("p1");
        request.setContent("hello");
        request.setTrace(trace);

        DataSyncOperationResponse response = service.upsert(request);

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void upsert_shouldUseDeterministicChunkIdentityAndMetadata() {
        AIDataSyncProperties props = new AIDataSyncProperties();
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        AIEmbeddingService embeddingService = mock(AIEmbeddingService.class);
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        AIAccessControlService accessControlService = mock(AIAccessControlService.class);

        when(loader.getEntityConfig("product")).thenReturn(AIEntityConfig.builder()
            .entityType("product")
            .indexable(true)
            .build());
        when(accessControlService.checkAccess(any())).thenReturn(AIAccessControlResponse.builder()
            .accessGranted(true)
            .build());
        when(embeddingService.generateEmbedding(any())).thenReturn(AIEmbeddingResponse.builder()
            .embedding(List.of(0.1, 0.2))
            .build());
        when(vectorManagementService.storeVector(eq("product"), eq("p1::chunk:segment-0001"), anyString(), any(), any()))
            .thenReturn("vec_2");

        DataSyncEntityNormalizer normalizer = new DataSyncEntityNormalizer(props, null);
        DataSyncService service = new DataSyncService(
            props,
            loader,
            embeddingService,
            vectorManagementService,
            accessControlService,
            normalizer,
            Clock.fixed(Instant.parse("2026-02-12T00:00:00Z"), ZoneOffset.UTC)
        );

        DataSyncTrace trace = verifiedTrace("vectorization-runner", null, "req2");

        DataSyncIdentity identity = new DataSyncIdentity();
        identity.setSourceRecordId("source-product-1");
        identity.setSourceRecordVersion("42");
        identity.setChunkId("Segment 0001");
        identity.setChunkCount(3);
        identity.setContentFingerprint("sha256:abc");

        DataSyncUpsertRequest request = new DataSyncUpsertRequest();
        request.setVectorSpace("product");
        request.setId("p1");
        request.setContent("hello");
        request.setIdentity(identity);
        request.setTrace(trace);

        DataSyncOperationResponse response = service.upsert(request);

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getId()).isEqualTo("p1::chunk:segment-0001");
        assertThat(response.getMetadata())
            .containsEntry("_dataSyncSourceRecordId", "source-product-1")
            .containsEntry("_dataSyncSourceRecordVersion", "42")
            .containsEntry("_dataSyncChunkId", "segment-0001")
            .containsEntry("_dataSyncChunkCount", 3)
            .containsEntry("_dataSyncContentFingerprint", "sha256:abc")
            .containsEntry("_dataSyncTargetId", "p1::chunk:segment-0001");
    }

    @Test
    void upsert_shouldPreferVerifiedAuthContextForAccessControl() {
        AIDataSyncProperties props = new AIDataSyncProperties();
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        AIEmbeddingService embeddingService = mock(AIEmbeddingService.class);
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        AIAccessControlService accessControlService = mock(AIAccessControlService.class);

        when(loader.getEntityConfig("product")).thenReturn(AIEntityConfig.builder()
            .entityType("product")
            .indexable(true)
            .build());
        when(accessControlService.checkAccess(any())).thenReturn(AIAccessControlResponse.builder()
            .accessGranted(true)
            .build());
        when(embeddingService.generateEmbedding(any())).thenReturn(AIEmbeddingResponse.builder()
            .embedding(List.of(0.1, 0.2))
            .build());
        when(vectorManagementService.storeVector(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn("vec_3");

        DataSyncService service = new DataSyncService(
            props,
            loader,
            embeddingService,
            vectorManagementService,
            accessControlService,
            new DataSyncEntityNormalizer(props, null),
            Clock.fixed(Instant.parse("2026-02-12T00:00:00Z"), ZoneOffset.UTC)
        );

        DataSyncTrace trace = verifiedTrace("verified-system", "verified-session", "req-auth");
        trace.getAuthContext().setDeploymentId("dep-123");
        trace.getAuthContext().setCustomerId("cus-123");
        trace.getAuthContext().setTenantId("ten-123");
        trace.getAuthContext().setIssuer("runtime-test");
        trace.getAuthContext().setGrantedScopes(List.of("data-sync:upsert"));

        DataSyncUpsertRequest request = new DataSyncUpsertRequest();
        request.setVectorSpace("product");
        request.setId("p-auth");
        request.setContent("hello");
        request.setTrace(trace);

        DataSyncOperationResponse response = service.upsert(request);

        assertThat(response.getSuccess()).isTrue();

        ArgumentCaptor<AIAccessControlRequest> captor = ArgumentCaptor.forClass(AIAccessControlRequest.class);
        verify(accessControlService, atLeastOnce()).checkAccess(captor.capture());
        AIAccessControlRequest accessRequest = captor.getValue();
        assertThat(accessRequest.getAuthContext()).isNotNull();
        assertThat(accessRequest.getAuthContext().getSubjectId()).isEqualTo("verified-system");
        assertThat(accessRequest.getAuthContext().getSessionId()).isEqualTo("verified-session");
        assertThat(accessRequest.getMetadata()).containsEntry("identitySource", "verifiedAuthContext");
        assertThat(accessRequest.getMetadata())
            .extractingByKey("authContext")
            .asInstanceOf(MAP)
            .containsEntry("subjectId", "verified-system")
            .containsEntry("authMode", "PRIVATE_RUNTIME_BACKEND_MEDIATED")
            .containsEntry("deploymentId", "dep-123");
    }

    @Test
    void batch_shouldReturnProviderRequestIdFromTrace() {
        AIDataSyncProperties props = new AIDataSyncProperties();
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        AIEmbeddingService embeddingService = mock(AIEmbeddingService.class);
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        AIAccessControlService accessControlService = mock(AIAccessControlService.class);

        when(loader.getEntityConfig("product")).thenReturn(AIEntityConfig.builder()
            .entityType("product")
            .indexable(true)
            .build());
        when(accessControlService.checkAccess(any())).thenReturn(AIAccessControlResponse.builder()
            .accessGranted(true)
            .build());
        when(embeddingService.generateEmbedding(any())).thenReturn(AIEmbeddingResponse.builder()
            .embedding(List.of(0.1, 0.2))
            .build());
        when(vectorManagementService.storeVector(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn("vec_batch");

        DataSyncService service = new DataSyncService(
            props,
            loader,
            embeddingService,
            vectorManagementService,
            accessControlService,
            new DataSyncEntityNormalizer(props, null),
            Clock.fixed(Instant.parse("2026-02-12T00:00:00Z"), ZoneOffset.UTC)
        );

        DataSyncTrace trace = verifiedTrace("system", null, "example-safe-knowledge-sync-req-1");
        DataSyncOperation operation = new DataSyncOperation(
            DataSyncOperationType.UPSERT,
            "product",
            "p-batch",
            "hello",
            null,
            Map.of("source", "test"),
            null
        );

        DataSyncBatchResponse response = service.batch(new DataSyncBatchRequest(trace, List.of(operation)));

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getProviderRequestId()).isEqualTo("example-safe-knowledge-sync-req-1");
        assertThat(response.getTotalOperations()).isEqualTo(1);
        assertThat(response.getSucceededOperations()).isEqualTo(1);
        assertThat(response.getFailedOperations()).isZero();
    }

    @Test
    void batch_shouldReusePreflightAccessDecisions_duringExecution() {
        AIDataSyncProperties props = new AIDataSyncProperties();
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        AIEmbeddingService embeddingService = mock(AIEmbeddingService.class);
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        AIAccessControlService accessControlService = mock(AIAccessControlService.class);

        when(loader.getEntityConfig("product")).thenReturn(AIEntityConfig.builder()
            .entityType("product")
            .indexable(true)
            .build());
        when(accessControlService.checkAccess(any())).thenReturn(AIAccessControlResponse.builder()
            .accessGranted(true)
            .build());
        when(embeddingService.generateEmbedding(any())).thenReturn(AIEmbeddingResponse.builder()
            .embedding(List.of(0.1, 0.2))
            .build());
        when(vectorManagementService.storeVector(eq("product"), eq("sku-1"), anyString(), any(), any()))
            .thenReturn("vec-sku-1");
        when(vectorManagementService.removeVector("product", "sku-2")).thenReturn(true);

        DataSyncService service = new DataSyncService(
            props,
            loader,
            embeddingService,
            vectorManagementService,
            accessControlService,
            new DataSyncEntityNormalizer(props, null),
            Clock.fixed(Instant.parse("2026-02-12T00:00:00Z"), ZoneOffset.UTC)
        );

        DataSyncOperation upsert = new DataSyncOperation(
            DataSyncOperationType.UPSERT,
            "product",
            "sku-1",
            "gaming laptop",
            null,
            null,
            null
        );
        DataSyncOperation delete = new DataSyncOperation(
            DataSyncOperationType.DELETE,
            "product",
            "sku-2",
            null,
            null,
            null,
            null
        );

        DataSyncBatchResponse response = service.batch(new DataSyncBatchRequest(
            verifiedTrace("system", null, "req-batch-preflight-only"),
            List.of(upsert, delete)
        ));

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getTotalOperations()).isEqualTo(2);
        assertThat(response.getSucceededOperations()).isEqualTo(2);
        assertThat(response.getFailedOperations()).isZero();
        verify(accessControlService, times(2)).checkAccess(any());
    }

    @Test
    void batch_shouldExposeStructuredDeniedDetails_whenAccessEvaluationFailsClosed() {
        AIDataSyncProperties props = new AIDataSyncProperties();
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        AIEmbeddingService embeddingService = mock(AIEmbeddingService.class);
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        AIAccessControlService accessControlService = mock(AIAccessControlService.class);

        when(loader.getEntityConfig("product")).thenReturn(AIEntityConfig.builder()
            .entityType("product")
            .indexable(true)
            .build());
        when(accessControlService.checkAccess(any())).thenThrow(new IllegalStateException("policy backend offline"));

        DataSyncService service = new DataSyncService(
            props,
            loader,
            embeddingService,
            vectorManagementService,
            accessControlService,
            new DataSyncEntityNormalizer(props, null),
            Clock.fixed(Instant.parse("2026-02-12T00:00:00Z"), ZoneOffset.UTC)
        );

        DataSyncOperation operation = new DataSyncOperation(
            DataSyncOperationType.UPSERT,
            "product",
            "sku-denied",
            "gaming laptop",
            null,
            null,
            null
        );

        DataSyncBatchResponse response = service.batch(new DataSyncBatchRequest(
            verifiedTrace("system", null, "req-batch-auth-failure"),
            List.of(operation)
        ));

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("ACCESS_DENIED");
        assertThat(response.getTotalOperations()).isEqualTo(1);
        assertThat(response.getFailedOperations()).isEqualTo(1);
        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getMetadata())
            .containsKey("deniedOperations")
            .containsKey("deniedOperationDetails");

        Object details = response.getResults().get(0).getMetadata().get("deniedOperationDetails");
        assertThat(details).asList().hasSize(1);
        assertThat(((List<?>) details).get(0))
            .asInstanceOf(MAP)
            .containsEntry("index", 0)
            .containsEntry("vectorSpace", "product")
            .containsEntry("id", "sku-denied")
            .containsEntry("operationType", "WRITE")
            .containsEntry("accessDecisionSource", "accessControlService")
            .containsEntry("accessEvaluationStatus", "failedClosed")
            .containsEntry("accessEvaluationFailure", "policy backend offline");
        verifyNoInteractions(embeddingService, vectorManagementService);
    }

    @Test
    void upsert_shouldBypassAccessControl_forTrustedPlatformInternalSync() {
        AIDataSyncProperties props = new AIDataSyncProperties();
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        AIEmbeddingService embeddingService = mock(AIEmbeddingService.class);
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        AIAccessControlService accessControlService = mock(AIAccessControlService.class);

        when(loader.getEntityConfig("product")).thenReturn(AIEntityConfig.builder()
            .entityType("product")
            .indexable(true)
            .build());
        when(embeddingService.generateEmbedding(any())).thenReturn(AIEmbeddingResponse.builder()
            .embedding(List.of(0.1, 0.2))
            .build());
        when(vectorManagementService.storeVector(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn("vec-platform");

        DataSyncService service = new DataSyncService(
            props,
            loader,
            embeddingService,
            vectorManagementService,
            accessControlService,
            new DataSyncEntityNormalizer(props, null),
            Clock.fixed(Instant.parse("2026-02-12T00:00:00Z"), ZoneOffset.UTC)
        );

        DataSyncTrace trace = verifiedTrace("system:platform-marketplace-dataset-sync", "verified-session", "req-platform-auth");
        trace.getAuthContext().setDeploymentId("dep-123");
        trace.getAuthContext().setIssuer("platform-marketplace-dataset-sync");
        trace.getAuthContext().setGrantedScopes(List.of("data-sync:upsert", "vectorization:verification"));

        DataSyncUpsertRequest request = new DataSyncUpsertRequest();
        request.setVectorSpace("product");
        request.setId("p-platform-auth");
        request.setContent("hello");
        request.setTrace(trace);

        DataSyncOperationResponse response = service.upsert(request);

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getMetadata()).containsEntry("accessDecisionSource", "trustedPlatformInternalSync");
        verifyNoInteractions(accessControlService);
    }

    @Test
    void upsert_shouldNotBypassAccessControl_whenTrustedPlatformSyncScopeMissing() {
        AIDataSyncProperties props = new AIDataSyncProperties();
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        AIEmbeddingService embeddingService = mock(AIEmbeddingService.class);
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        AIAccessControlService accessControlService = mock(AIAccessControlService.class);

        when(loader.getEntityConfig("product")).thenReturn(AIEntityConfig.builder()
            .entityType("product")
            .indexable(true)
            .build());
        when(accessControlService.checkAccess(any())).thenReturn(AIAccessControlResponse.builder()
            .accessGranted(false)
            .build());

        DataSyncService service = new DataSyncService(
            props,
            loader,
            embeddingService,
            vectorManagementService,
            accessControlService,
            new DataSyncEntityNormalizer(props, null),
            Clock.fixed(Instant.parse("2026-02-12T00:00:00Z"), ZoneOffset.UTC)
        );

        DataSyncTrace trace = verifiedTrace("system:platform-marketplace-dataset-sync", "verified-session", "req-platform-auth-missing-scope");
        trace.getAuthContext().setDeploymentId("dep-123");
        trace.getAuthContext().setIssuer("platform-marketplace-dataset-sync");
        trace.getAuthContext().setGrantedScopes(List.of("vectorization:verification"));

        DataSyncUpsertRequest request = new DataSyncUpsertRequest();
        request.setVectorSpace("product");
        request.setId("p-platform-auth-missing-scope");
        request.setContent("hello");
        request.setTrace(trace);

        DataSyncOperationResponse response = service.upsert(request);

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("ACCESS_DENIED");
        verify(accessControlService).checkAccess(any());
        verify(vectorManagementService, never()).storeVector(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void delete_shouldBypassAccessControl_forTrustedPlatformInternalSyncWithDeleteScope() {
        AIDataSyncProperties props = new AIDataSyncProperties();
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        AIEmbeddingService embeddingService = mock(AIEmbeddingService.class);
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        AIAccessControlService accessControlService = mock(AIAccessControlService.class);

        when(loader.getEntityConfig("product")).thenReturn(AIEntityConfig.builder()
            .entityType("product")
            .indexable(true)
            .build());
        when(vectorManagementService.removeVector("product", "p-platform-delete")).thenReturn(true);

        DataSyncService service = new DataSyncService(
            props,
            loader,
            embeddingService,
            vectorManagementService,
            accessControlService,
            new DataSyncEntityNormalizer(props, null),
            Clock.fixed(Instant.parse("2026-02-12T00:00:00Z"), ZoneOffset.UTC)
        );

        DataSyncTrace trace = verifiedTrace("system:platform-marketplace-dataset-sync", "verified-session", "req-platform-delete");
        trace.getAuthContext().setDeploymentId("dep-123");
        trace.getAuthContext().setIssuer("platform-marketplace-dataset-sync");
        trace.getAuthContext().setGrantedScopes(List.of("data-sync:delete", "vectorization:verification"));

        var request = new ai.fabric.datasync.dto.DataSyncDeleteRequest();
        request.setVectorSpace("product");
        request.setId("p-platform-delete");
        request.setTrace(trace);

        DataSyncOperationResponse response = service.delete(request);

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getMetadata()).containsEntry("accessDecisionSource", "trustedPlatformInternalSync");
        verifyNoInteractions(accessControlService);
    }

    @Test
    void upsert_shouldFailClosed_whenVerifiedAuthContextSubjectMissing() {
        AIDataSyncProperties props = new AIDataSyncProperties();
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        AIEmbeddingService embeddingService = mock(AIEmbeddingService.class);
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        AIAccessControlService accessControlService = mock(AIAccessControlService.class);

        when(loader.getEntityConfig("product")).thenReturn(AIEntityConfig.builder()
            .entityType("product")
            .indexable(true)
            .build());

        DataSyncService service = new DataSyncService(
            props,
            loader,
            embeddingService,
            vectorManagementService,
            accessControlService,
            new DataSyncEntityNormalizer(props, null),
            Clock.fixed(Instant.parse("2026-02-12T00:00:00Z"), ZoneOffset.UTC)
        );

        DataSyncTrace trace = new DataSyncTrace();
        trace.setRequestId("req-missing-auth");

        DataSyncUpsertRequest request = new DataSyncUpsertRequest();
        request.setVectorSpace("product");
        request.setId("p-auth-missing");
        request.setContent("hello");
        request.setTrace(trace);

        DataSyncOperationResponse response = service.upsert(request);

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void upsert_shouldExposeVectorStoreCauseInFailureMetadata() {
        AIDataSyncProperties props = new AIDataSyncProperties();
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        AIEmbeddingService embeddingService = mock(AIEmbeddingService.class);
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        AIAccessControlService accessControlService = mock(AIAccessControlService.class);

        when(loader.getEntityConfig("product")).thenReturn(AIEntityConfig.builder()
            .entityType("product")
            .indexable(true)
            .build());
        when(accessControlService.checkAccess(any())).thenReturn(AIAccessControlResponse.builder()
            .accessGranted(true)
            .build());
        when(embeddingService.generateEmbedding(any())).thenReturn(AIEmbeddingResponse.builder()
            .embedding(List.of(0.1, 0.2))
            .build());
        when(vectorManagementService.storeVector(anyString(), anyString(), anyString(), any(), any()))
            .thenThrow(new IllegalStateException("Field [vector] vector's dimensions must be <= [1024]; got 1536"));

        DataSyncService service = new DataSyncService(
            props,
            loader,
            embeddingService,
            vectorManagementService,
            accessControlService,
            new DataSyncEntityNormalizer(props, null),
            Clock.fixed(Instant.parse("2026-02-12T00:00:00Z"), ZoneOffset.UTC)
        );

        DataSyncUpsertRequest request = new DataSyncUpsertRequest();
        request.setVectorSpace("product");
        request.setId("sku-1");
        request.setContent("gaming laptop");
        request.setTrace(verifiedTrace("system", null, "req-store-failure"));

        DataSyncOperationResponse response = service.upsert(request);

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("VECTOR_STORE_FAILED");
        assertThat(response.getMessage()).isEqualTo("Vector store failed.");
        assertThat(response.getMetadata())
            .isEqualTo(Map.of("cause", "Field [vector] vector's dimensions must be <= [1024]; got 1536"));
    }

    @Test
    void delete_shouldExposeVectorStoreCauseInFailureMetadata() {
        AIDataSyncProperties props = new AIDataSyncProperties();
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        AIEmbeddingService embeddingService = mock(AIEmbeddingService.class);
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        AIAccessControlService accessControlService = mock(AIAccessControlService.class);

        when(loader.getEntityConfig("product")).thenReturn(AIEntityConfig.builder()
            .entityType("product")
            .indexable(true)
            .build());
        when(accessControlService.checkAccess(any())).thenReturn(AIAccessControlResponse.builder()
            .accessGranted(true)
            .build());
        when(vectorManagementService.removeVector("product", "sku-1"))
            .thenThrow(new IllegalStateException("delete timeout"));

        DataSyncService service = new DataSyncService(
            props,
            loader,
            embeddingService,
            vectorManagementService,
            accessControlService,
            new DataSyncEntityNormalizer(props, null),
            Clock.fixed(Instant.parse("2026-02-12T00:00:00Z"), ZoneOffset.UTC)
        );

        DataSyncDeleteRequest request = new DataSyncDeleteRequest();
        request.setVectorSpace("product");
        request.setId("sku-1");
        request.setTrace(verifiedTrace("system", null, "req-delete-failure"));

        DataSyncOperationResponse response = service.delete(request);

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("VECTOR_STORE_FAILED");
        assertThat(response.getMessage()).isEqualTo("Vector delete failed.");
        assertThat(response.getMetadata()).isEqualTo(Map.of("cause", "delete timeout"));
    }

    @Test
    void upsert_shouldReturnInvalidRequest_whenChunkIdentityHasNoSafeCharacters() {
        AIDataSyncProperties props = new AIDataSyncProperties();
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        AIEmbeddingService embeddingService = mock(AIEmbeddingService.class);
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        AIAccessControlService accessControlService = mock(AIAccessControlService.class);

        DataSyncService service = new DataSyncService(
            props,
            loader,
            embeddingService,
            vectorManagementService,
            accessControlService,
            new DataSyncEntityNormalizer(props, null),
            Clock.fixed(Instant.parse("2026-02-12T00:00:00Z"), ZoneOffset.UTC)
        );

        DataSyncIdentity identity = new DataSyncIdentity();
        identity.setChunkId("!!!");

        DataSyncUpsertRequest request = new DataSyncUpsertRequest();
        request.setVectorSpace("product");
        request.setId("sku-1");
        request.setContent("gaming laptop");
        request.setIdentity(identity);
        request.setTrace(verifiedTrace("system", null, "req-invalid-chunk"));

        DataSyncOperationResponse response = service.upsert(request);

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getMessage()).contains("identity.chunkId");
        assertThat(response.getId()).isEqualTo("sku-1");
        verifyNoInteractions(loader, embeddingService, vectorManagementService, accessControlService);
    }

    @Test
    void delete_shouldReturnInvalidRequest_whenChunkIdentityHasNoSafeCharacters() {
        AIDataSyncProperties props = new AIDataSyncProperties();
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        AIEmbeddingService embeddingService = mock(AIEmbeddingService.class);
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        AIAccessControlService accessControlService = mock(AIAccessControlService.class);

        DataSyncService service = new DataSyncService(
            props,
            loader,
            embeddingService,
            vectorManagementService,
            accessControlService,
            new DataSyncEntityNormalizer(props, null),
            Clock.fixed(Instant.parse("2026-02-12T00:00:00Z"), ZoneOffset.UTC)
        );

        DataSyncIdentity identity = new DataSyncIdentity();
        identity.setChunkId("!!!");

        DataSyncDeleteRequest request = new DataSyncDeleteRequest();
        request.setVectorSpace("product");
        request.setId("sku-1");
        request.setIdentity(identity);
        request.setTrace(verifiedTrace("system", null, "req-invalid-delete-chunk"));

        DataSyncOperationResponse response = service.delete(request);

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getMessage()).contains("identity.chunkId");
        assertThat(response.getId()).isEqualTo("sku-1");
        verifyNoInteractions(loader, embeddingService, vectorManagementService, accessControlService);
    }

    @Test
    void batch_shouldReportOperationCounts_whenPreflightValidationFails() {
        AIDataSyncProperties props = new AIDataSyncProperties();
        props.setMaxBatchSize(1);
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        AIEmbeddingService embeddingService = mock(AIEmbeddingService.class);
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        AIAccessControlService accessControlService = mock(AIAccessControlService.class);

        DataSyncService service = new DataSyncService(
            props,
            loader,
            embeddingService,
            vectorManagementService,
            accessControlService,
            new DataSyncEntityNormalizer(props, null),
            Clock.fixed(Instant.parse("2026-02-12T00:00:00Z"), ZoneOffset.UTC)
        );

        DataSyncOperation first = new DataSyncOperation(
            DataSyncOperationType.UPSERT,
            "product",
            "sku-1",
            "gaming laptop",
            null,
            null,
            null
        );
        DataSyncOperation second = new DataSyncOperation(
            DataSyncOperationType.DELETE,
            "product",
            "sku-2",
            null,
            null,
            null,
            null
        );

        DataSyncBatchResponse response = service.batch(new DataSyncBatchRequest(
            verifiedTrace("system", null, "req-too-large"),
            List.of(first, second)
        ));

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("BATCH_TOO_LARGE");
        assertThat(response.getTotalOperations()).isEqualTo(2);
        assertThat(response.getSucceededOperations()).isZero();
        assertThat(response.getFailedOperations()).isEqualTo(2);
        assertThat(response.getResults()).hasSize(1);
        verifyNoInteractions(loader, embeddingService, vectorManagementService, accessControlService);
    }

    @Test
    void batch_shouldReturnInvalidRequest_whenOperationChunkIdentityIsInvalid() {
        AIDataSyncProperties props = new AIDataSyncProperties();
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        AIEmbeddingService embeddingService = mock(AIEmbeddingService.class);
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        AIAccessControlService accessControlService = mock(AIAccessControlService.class);

        DataSyncService service = new DataSyncService(
            props,
            loader,
            embeddingService,
            vectorManagementService,
            accessControlService,
            new DataSyncEntityNormalizer(props, null),
            Clock.fixed(Instant.parse("2026-02-12T00:00:00Z"), ZoneOffset.UTC)
        );

        DataSyncIdentity identity = new DataSyncIdentity();
        identity.setChunkId("!!!");
        DataSyncOperation invalid = new DataSyncOperation(
            DataSyncOperationType.UPSERT,
            "product",
            "sku-1",
            "gaming laptop",
            null,
            null,
            identity
        );
        DataSyncOperation valid = new DataSyncOperation(
            DataSyncOperationType.DELETE,
            "product",
            "sku-2",
            null,
            null,
            null,
            null
        );

        DataSyncBatchResponse response = service.batch(new DataSyncBatchRequest(
            verifiedTrace("system", null, "req-invalid-batch-chunk"),
            List.of(invalid, valid)
        ));

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getMessage()).contains("Operation at index 0").contains("identity.chunkId");
        assertThat(response.getTotalOperations()).isEqualTo(2);
        assertThat(response.getSucceededOperations()).isZero();
        assertThat(response.getFailedOperations()).isEqualTo(2);
        verifyNoInteractions(loader, embeddingService, vectorManagementService, accessControlService);
    }

    @Test
    void batch_shouldReportOperationCount_whenTraceIsMissing() {
        AIDataSyncProperties props = new AIDataSyncProperties();
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        AIEmbeddingService embeddingService = mock(AIEmbeddingService.class);
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        AIAccessControlService accessControlService = mock(AIAccessControlService.class);

        DataSyncService service = new DataSyncService(
            props,
            loader,
            embeddingService,
            vectorManagementService,
            accessControlService,
            new DataSyncEntityNormalizer(props, null),
            Clock.fixed(Instant.parse("2026-02-12T00:00:00Z"), ZoneOffset.UTC)
        );

        DataSyncOperation operation = new DataSyncOperation(
            DataSyncOperationType.UPSERT,
            "product",
            "sku-1",
            "gaming laptop",
            null,
            null,
            null
        );

        DataSyncBatchResponse response = service.batch(new DataSyncBatchRequest(null, List.of(operation)));

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getTotalOperations()).isEqualTo(1);
        assertThat(response.getSucceededOperations()).isZero();
        assertThat(response.getFailedOperations()).isEqualTo(1);
        verifyNoInteractions(loader, embeddingService, vectorManagementService, accessControlService);
    }

    private DataSyncTrace verifiedTrace(String subjectId, String sessionId, String requestId) {
        DataSyncVerifiedAuthContext authContext = new DataSyncVerifiedAuthContext();
        authContext.setSubjectId(subjectId);
        authContext.setSubjectType("SYSTEM_PROCESS");
        authContext.setAuthMode("PRIVATE_RUNTIME_BACKEND_MEDIATED");
        authContext.setCallerType("SYSTEM_PROCESS");
        authContext.setSessionId(sessionId);
        authContext.setIssuer("runtime-test");
        authContext.setGrantedScopes(List.of("data-sync:upsert"));

        DataSyncTrace trace = new DataSyncTrace();
        trace.setRequestId(requestId);
        trace.setAuthContext(authContext);
        return trace;
    }
}
