package ai.fabric.datasync.service;

import ai.fabric.access.AIAccessControlService;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.datasync.AIDataSyncProperties;
import ai.fabric.datasync.dto.DataSyncBatchRequest;
import ai.fabric.datasync.dto.DataSyncDeleteRequest;
import ai.fabric.datasync.dto.DataSyncIdentity;
import ai.fabric.datasync.dto.DataSyncOperation;
import ai.fabric.datasync.dto.DataSyncOperationType;
import ai.fabric.datasync.dto.DataSyncTrace;
import ai.fabric.datasync.dto.DataSyncUpsertRequest;
import ai.fabric.datasync.dto.DataSyncVerifiedAuthContext;
import ai.fabric.datasync.normalize.DataSyncEntityNormalizer;
import ai.fabric.dto.AIAccessControlRequest;
import ai.fabric.dto.AIAccessControlResponse;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIEntityIndexingPolicy;
import ai.fabric.dto.AISearchableField;
import ai.fabric.indexing.api.AIEntityIndexingGateway;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.IndexingDispatchStatus;
import ai.fabric.indexing.api.IndexingOutcome;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.api.AISearchDestination;
import ai.fabric.indexing.api.AISearchPreprocessing;
import ai.fabric.indexing.model.AIIndexDocument;
import ai.fabric.indexing.projection.AIConfiguredEntityProjectionService;
import ai.fabric.privacy.pii.PIIDetectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DataSyncServiceTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-07-24T12:00:00Z"),
        ZoneOffset.UTC
    );

    @Test
    void upsertSubmitsOneCanonicalDocumentWhenAccessGranted() {
        Fixture fixture = fixture();

        var response = fixture.service().upsert(
            upsert("product", "p1", "hello", verifiedTrace("system", null, "req1"))
        );

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getVectorId()).isEqualTo("p1");
        assertThat(response.getMetadata())
            .containsEntry("indexingWorkId", "work-p1")
            .containsEntry("indexingStatus", "COMPLETED")
            .containsEntry("indexingStrategy", "SYNC");

        ArgumentCaptor<AIIndexDocument> document =
            ArgumentCaptor.forClass(AIIndexDocument.class);
        verify(fixture.gateway()).submit(document.capture(), eq(IndexingStrategy.SYNC));
        assertThat(document.getValue().entityType()).isEqualTo("product");
        assertThat(document.getValue().entityId()).isEqualTo("p1");
        assertThat(document.getValue().semanticSearchText())
            .isEqualTo("content: hello");
        assertThat(document.getValue().correlationId()).isEqualTo("req1");
    }

    @Test
    void upsertFailsClosedAndDoesNotSubmitWhenAccessDenied() {
        Fixture fixture = fixture();
        when(fixture.accessControl().checkAccess(any())).thenReturn(
            AIAccessControlResponse.builder().accessGranted(false).build()
        );

        var response = fixture.service().upsert(
            upsert("product", "p1", "hello", verifiedTrace("system", null, "req1"))
        );

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("ACCESS_DENIED");
        verifyNoInteractions(fixture.gateway());
    }

    @Test
    void upsertFailsClosedAndReportsSafeMetadataWhenAccessEvaluationThrows() {
        Fixture fixture = fixture();
        when(fixture.accessControl().checkAccess(any()))
            .thenThrow(new IllegalStateException("policy unavailable"));

        var response = fixture.service().upsert(
            upsert("product", "p1", "hello", verifiedTrace("system", null, "req1"))
        );

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("ACCESS_DENIED");
        assertThat(response.getMetadata())
            .containsEntry("accessEvaluationStatus", "failedClosed")
            .containsEntry("accessDecisionSource", "accessControlService");
        verifyNoInteractions(fixture.gateway());
    }

    @Test
    void upsertUsesDeterministicChunkIdentityAndApprovedMetadata() {
        Fixture fixture = fixture();
        DataSyncIdentity identity = new DataSyncIdentity(
            "source-product-1",
            "42",
            "Segment 0001",
            3,
            "sha256:abc"
        );
        DataSyncUpsertRequest request = upsert(
            "product",
            "p1",
            "hello",
            verifiedTrace("vectorization-runner", null, "req2")
        );
        request.setIdentity(identity);

        var response = fixture.service().upsert(request);

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getId()).isEqualTo("p1::chunk:segment-0001");
        assertThat(response.getMetadata())
            .containsEntry("_dataSyncSourceRecordId", "source-product-1")
            .containsEntry("_dataSyncSourceRecordVersion", "42")
            .containsEntry("_dataSyncChunkId", "segment-0001")
            .containsEntry("_dataSyncChunkCount", 3)
            .containsEntry("_dataSyncContentFingerprint", "sha256:abc")
            .containsEntry("_dataSyncTargetId", "p1::chunk:segment-0001");

        ArgumentCaptor<AIIndexDocument> document =
            ArgumentCaptor.forClass(AIIndexDocument.class);
        verify(fixture.gateway()).submit(document.capture(), eq(IndexingStrategy.SYNC));
        assertThat(document.getValue().entityId())
            .isEqualTo("p1::chunk:segment-0001");
        assertThat(document.getValue().sourceVersion()).isEqualTo(42L);
        assertThat(document.getValue().vectorMetadata())
            .containsEntry("_dataSyncTargetId", "p1::chunk:segment-0001")
            .containsKey("_dataSyncIdempotencyKey");
    }

    @Test
    void verifiedAuthContextIsPropagatedToAccessControl() {
        Fixture fixture = fixture();
        DataSyncTrace trace = verifiedTrace(
            "verified-system",
            "verified-session",
            "req-auth"
        );
        trace.getAuthContext().setDeploymentId("dep-123");
        trace.getAuthContext().setCustomerId("cus-123");
        trace.getAuthContext().setTenantId("ten-123");
        trace.getAuthContext().setIssuer("runtime-test");

        assertThat(fixture.service().upsert(
            upsert("product", "p-auth", "hello", trace)
        ).getSuccess()).isTrue();

        ArgumentCaptor<AIAccessControlRequest> request =
            ArgumentCaptor.forClass(AIAccessControlRequest.class);
        verify(fixture.accessControl(), atLeastOnce()).checkAccess(request.capture());
        assertThat(request.getValue().getAuthContext().getSubjectId())
            .isEqualTo("verified-system");
        assertThat(request.getValue().getAuthContext().getSessionId())
            .isEqualTo("verified-session");
        assertThat(request.getValue().getMetadata())
            .containsEntry("identitySource", "verifiedAuthContext")
            .extractingByKey("authContext")
            .asInstanceOf(MAP)
            .containsEntry("deploymentId", "dep-123")
            .containsEntry("tenantId", "ten-123");
    }

    @Test
    void trustedInternalBypassRequiresExplicitFlagExactShapeAndScope() {
        Fixture fixture = fixture();
        fixture.properties().setAllowTrustedPlatformInternalSyncBypass(true);
        DataSyncTrace trace = verifiedTrace(
            "system:platform-vectorizer",
            null,
            "req-internal"
        );
        trace.getAuthContext().setIssuer("platform-runtime");
        trace.getAuthContext().setDeploymentId("dep-1");
        trace.getAuthContext().setGrantedScopes(List.of("data-sync:upsert"));

        var response = fixture.service().upsert(
            upsert("product", "p1", "hello", trace)
        );

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getMetadata())
            .containsEntry("accessDecisionSource", "trustedPlatformInternalSync");
        verifyNoInteractions(fixture.accessControl());

        DataSyncTrace missingScope = verifiedTrace(
            "system:platform-vectorizer",
            null,
            "req-no-scope"
        );
        missingScope.getAuthContext().setIssuer("platform-runtime");
        missingScope.getAuthContext().setDeploymentId("dep-1");
        missingScope.getAuthContext().setGrantedScopes(List.of());
        fixture.service().upsert(
            upsert("product", "p2", "hello", missingScope)
        );
        verify(fixture.accessControl()).checkAccess(any());
    }

    @Test
    void missingVerifiedSubjectFailsBeforeAccessOrIndexing() {
        Fixture fixture = fixture();
        DataSyncTrace trace = verifiedTrace("system", null, "req1");
        trace.getAuthContext().setSubjectId(" ");

        var response = fixture.service().upsert(
            upsert("product", "p1", "hello", trace)
        );

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("ACCESS_DENIED");
        assertThat(response.getMetadata())
            .containsEntry("identitySource", "missingVerifiedAuthContext")
            .containsEntry("accessEvaluationStatus", "missingSubject");
        verifyNoInteractions(fixture.accessControl(), fixture.gateway());
    }

    @Test
    void deleteSubmitsCanonicalDeleteDocumentAndRequiresDeleteScopeForBypass() {
        Fixture fixture = fixture();
        fixture.properties().setAllowTrustedPlatformInternalSyncBypass(true);
        DataSyncTrace trace = verifiedTrace(
            "system:platform-vectorizer",
            null,
            "req-delete"
        );
        trace.getAuthContext().setIssuer("platform-runtime");
        trace.getAuthContext().setDeploymentId("dep-1");
        trace.getAuthContext().setGrantedScopes(List.of("data-sync:delete"));
        DataSyncDeleteRequest request = new DataSyncDeleteRequest();
        request.setVectorSpace("product");
        request.setId("p1");
        request.setTrace(trace);

        var response = fixture.service().delete(request);

        assertThat(response.getSuccess()).isTrue();
        ArgumentCaptor<AIIndexDocument> document =
            ArgumentCaptor.forClass(AIIndexDocument.class);
        verify(fixture.gateway()).submit(document.capture(), eq(IndexingStrategy.SYNC));
        assertThat(document.getValue().workType()).isEqualTo(AIIndexWorkType.DELETE);
        assertThat(document.getValue().semanticSearchText()).isNull();
        verifyNoInteractions(fixture.accessControl());
    }

    @Test
    void batchPreflightsEveryOperationAndSkipsAllWritesWhenOneIsDenied() {
        Fixture fixture = fixture();
        when(fixture.accessControl().checkAccess(any()))
            .thenReturn(AIAccessControlResponse.builder().accessGranted(true).build())
            .thenReturn(AIAccessControlResponse.builder().accessGranted(false).build());

        var response = fixture.service().batch(new DataSyncBatchRequest(
            verifiedTrace("system", null, "req-batch"),
            List.of(
                operation(DataSyncOperationType.UPSERT, "sku-1", "laptop"),
                operation(DataSyncOperationType.DELETE, "sku-2", null)
            )
        ));

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("ACCESS_DENIED");
        assertThat(response.getTotalOperations()).isEqualTo(2);
        assertThat(response.getSucceededOperations()).isZero();
        assertThat(response.getFailedOperations()).isEqualTo(2);
        assertThat(response.getResults().getFirst().getMetadata())
            .extractingByKey("deniedOperationDetails")
            .asInstanceOf(LIST)
            .hasSize(1);
        verify(fixture.accessControl(), times(2)).checkAccess(any());
        verifyNoInteractions(fixture.gateway());
    }

    @Test
    void batchReusesAccessDecisionsAndReturnsProviderRequestId() {
        Fixture fixture = fixture();

        var response = fixture.service().batch(new DataSyncBatchRequest(
            verifiedTrace("system", null, "provider-request-1"),
            List.of(
                operation(DataSyncOperationType.UPSERT, "sku-1", "laptop"),
                operation(DataSyncOperationType.DELETE, "sku-2", null)
            )
        ));

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getProviderRequestId()).isEqualTo("provider-request-1");
        assertThat(response.getTotalOperations()).isEqualTo(2);
        assertThat(response.getSucceededOperations()).isEqualTo(2);
        assertThat(response.getFailedOperations()).isZero();
        verify(fixture.accessControl(), times(2)).checkAccess(any());
        verify(fixture.gateway(), times(2)).submit(any(), eq(IndexingStrategy.SYNC));
    }

    @Test
    void retryableGatewayOutcomeIsVisibleAndNotReportedAsSuccess() {
        Fixture fixture = fixture();
        when(fixture.gateway().submit(any(), eq(IndexingStrategy.SYNC)))
            .thenAnswer(invocation -> {
                AIIndexDocument document = invocation.getArgument(0);
                return outcome(document, IndexingDispatchStatus.FAILED_RETRYABLE);
            });

        var response = fixture.service().upsert(
            upsert("product", "p1", "hello", verifiedTrace("system", null, "req1"))
        );

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("INDEXING_RETRYABLE");
        assertThat(response.getMetadata())
            .containsEntry("indexingStatus", "FAILED_RETRYABLE");
    }

    @Test
    void permanentGatewayOutcomeIsVisibleAndNotReportedAsSuccess() {
        Fixture fixture = fixture();
        when(fixture.gateway().submit(any(), eq(IndexingStrategy.SYNC)))
            .thenAnswer(invocation -> {
                AIIndexDocument document = invocation.getArgument(0);
                return outcome(document, IndexingDispatchStatus.FAILED_PERMANENT);
            });

        var response = fixture.service().upsert(
            upsert("product", "p1", "hello", verifiedTrace("system", null, "req1"))
        );

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("INDEXING_PERMANENT");
        assertThat(response.getMessage()).contains("operator review");
        assertThat(response.getMetadata())
            .containsEntry("indexingStatus", "FAILED_PERMANENT");
    }

    @Test
    void permanentDeleteFailureIsNotReportedAsDeleted() {
        Fixture fixture = fixture();
        when(fixture.gateway().submit(any(), eq(IndexingStrategy.SYNC)))
            .thenAnswer(invocation -> {
                AIIndexDocument document = invocation.getArgument(0);
                return outcome(document, IndexingDispatchStatus.FAILED_PERMANENT);
            });
        DataSyncDeleteRequest request = new DataSyncDeleteRequest();
        request.setVectorSpace("product");
        request.setId("p1");
        request.setTrace(verifiedTrace("system", null, "req-delete"));

        var response = fixture.service().delete(request);

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("INDEXING_PERMANENT");
        assertThat(response.getMessage()).contains("operator review");
    }

    @Test
    void gatewayExceptionReturnsSanitizedSubmissionFailure() {
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("secret provider response"))
            .when(fixture.gateway())
            .submit(any(), eq(IndexingStrategy.SYNC));

        var response = fixture.service().upsert(
            upsert("product", "p1", "hello", verifiedTrace("system", null, "req1"))
        );

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorCode())
            .isEqualTo("INDEXING_SUBMISSION_FAILED");
        assertThat(response.getMessage())
            .isEqualTo("Indexing submission failed.")
            .doesNotContain("secret");
    }

    @Test
    void projectionFailureIsVisibleAndDoesNotReachGateway() {
        Fixture fixture = fixture();
        fixture.config().getSearchableFields().getFirst().setRequired(true);

        DataSyncUpsertRequest request = new DataSyncUpsertRequest();
        request.setVectorSpace("product");
        request.setId("p1");
        request.setEntity(Map.of("other", "not allowlisted"));
        request.setTrace(verifiedTrace("system", null, "req1"));

        var response = fixture.service().upsert(request);

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("PROJECTION_REJECTED");
        assertThat(response.getMessage()).contains("REQUIRED_FIELD_MISSING");
        verifyNoInteractions(fixture.gateway());
    }

    @Test
    void invalidChunkIdentityAndMissingTraceHaveNoSideEffects() {
        Fixture fixture = fixture();
        DataSyncUpsertRequest invalidChunk = upsert(
            "product",
            "p1",
            "hello",
            verifiedTrace("system", null, "req1")
        );
        invalidChunk.setIdentity(new DataSyncIdentity(
            null,
            null,
            "!!!",
            null,
            null
        ));

        var chunkResponse = fixture.service().upsert(invalidChunk);
        var traceResponse = fixture.service().batch(new DataSyncBatchRequest(
            null,
            List.of(operation(DataSyncOperationType.UPSERT, "p1", "hello"))
        ));

        assertThat(chunkResponse.getErrorCode()).isEqualTo("INVALID_REQUEST");
        assertThat(traceResponse.getErrorCode()).isEqualTo("INVALID_REQUEST");
        assertThat(traceResponse.getTotalOperations()).isEqualTo(1);
        verifyNoInteractions(fixture.accessControl(), fixture.gateway());
    }

    @Test
    void listVectorSpacesOnlyExposesExplicitlyEnabledEntityTypes() {
        Fixture fixture = fixture();
        AIEntityConfig disabled = configuredEntity("internal", false);
        when(fixture.loader().getSupportedEntityTypes())
            .thenReturn(Set.of("product", "internal"));
        when(fixture.loader().getEntityConfig("internal")).thenReturn(disabled);

        var response = fixture.service().listVectorSpaces();

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getVectorSpaces()).containsExactly("product");
    }

    private Fixture fixture() {
        AIDataSyncProperties properties = new AIDataSyncProperties();
        AIEntityConfigurationLoader loader =
            mock(AIEntityConfigurationLoader.class);
        AIEntityIndexingGateway gateway =
            mock(AIEntityIndexingGateway.class);
        AIAccessControlService accessControl =
            mock(AIAccessControlService.class);
        AIEntityConfig config = configuredEntity("product", true);
        when(loader.getEntityConfig("product")).thenReturn(config);
        when(accessControl.checkAccess(any())).thenReturn(
            AIAccessControlResponse.builder().accessGranted(true).build()
        );
        when(gateway.submit(any(), eq(IndexingStrategy.SYNC)))
            .thenAnswer(invocation -> outcome(
                invocation.getArgument(0),
                IndexingDispatchStatus.COMPLETED
            ));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        ObjectProvider<PIIDetectionService> piiProvider =
            beanFactory.getBeanProvider(PIIDetectionService.class);
        AIConfiguredEntityProjectionService projectionService =
            new AIConfiguredEntityProjectionService(
                piiProvider,
                new ObjectMapper(),
                CLOCK
            );
        DataSyncEntityNormalizer normalizer =
            new DataSyncEntityNormalizer(properties, projectionService);
        DataSyncService service = new DataSyncService(
            properties,
            loader,
            gateway,
            accessControl,
            normalizer,
            CLOCK
        );
        return new Fixture(
            properties,
            loader,
            gateway,
            accessControl,
            config,
            service
        );
    }

    private AIEntityConfig configuredEntity(
        String entityType,
        boolean enabled
    ) {
        return AIEntityConfig.builder()
            .entityType(entityType)
            .indexing(AIEntityIndexingPolicy.builder()
                .enabled(enabled)
                .maxCharacters(8_000)
                .build())
            .searchableFields(List.of(AISearchableField.builder()
                .name("content")
                .destinations(Set.of(
                    AISearchDestination.SEMANTIC_SEARCH,
                    AISearchDestination.RAG_CONTEXT
                ))
                .preprocessing(AISearchPreprocessing.NORMALIZE)
                .maxLength(-1)
                .priority(50)
                .required(true)
                .build()))
            .metadataFields(List.of())
            .build();
    }

    private DataSyncUpsertRequest upsert(
        String vectorSpace,
        String id,
        String content,
        DataSyncTrace trace
    ) {
        DataSyncUpsertRequest request = new DataSyncUpsertRequest();
        request.setVectorSpace(vectorSpace);
        request.setId(id);
        request.setContent(content);
        request.setTrace(trace);
        return request;
    }

    private DataSyncOperation operation(
        DataSyncOperationType type,
        String id,
        String content
    ) {
        return new DataSyncOperation(
            type,
            "product",
            id,
            content,
            null,
            null,
            null
        );
    }

    private IndexingOutcome outcome(
        AIIndexDocument document,
        IndexingDispatchStatus status
    ) {
        return new IndexingOutcome(
            "work-" + document.entityId(),
            document.entityType(),
            document.entityId(),
            document.workType(),
            IndexingStrategy.SYNC,
            status
        );
    }

    private DataSyncTrace verifiedTrace(
        String subjectId,
        String sessionId,
        String requestId
    ) {
        DataSyncVerifiedAuthContext authContext =
            new DataSyncVerifiedAuthContext();
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

    private record Fixture(
        AIDataSyncProperties properties,
        AIEntityConfigurationLoader loader,
        AIEntityIndexingGateway gateway,
        AIAccessControlService accessControl,
        AIEntityConfig config,
        DataSyncService service
    ) {
    }
}
