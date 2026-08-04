package com.ai.fabric.realapps.livesync.service;

import ai.fabric.indexing.api.AIEntityIndexingGateway;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingOutcome;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.model.AIIndexDocument;
import com.ai.fabric.realapps.livesync.domain.SyncGuide;
import com.ai.fabric.realapps.livesync.domain.SyncPolicy;
import com.ai.fabric.realapps.livesync.domain.SyncProduct;
import com.ai.fabric.realapps.livesync.web.DemoModels.IndexingWorkView;
import com.ai.fabric.realapps.livesync.web.DemoModels.LifecycleWorkResponse;
import com.ai.fabric.realapps.livesync.web.DemoModels.VectorProof;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class IndexingLifecycleLabService {

    private static final int PERMANENT_FAILURE_ATTEMPTS = 1_000_000;

    private final AIEntityIndexingGateway indexingGateway;
    private final IndexingWorkProjectionService workProjection;
    private final ControlledIndexAnalysisHandler analysisHandler;
    private final SyncProductService products;
    private final SyncPolicyService policies;
    private final SyncGuideService guides;
    private final DemoWorkspaceService workspaces;
    private final DemoStateService stateService;
    private final SyncAuditService auditService;

    public IndexingLifecycleLabService(
        AIEntityIndexingGateway indexingGateway,
        IndexingWorkProjectionService workProjection,
        ControlledIndexAnalysisHandler analysisHandler,
        SyncProductService products,
        SyncPolicyService policies,
        SyncGuideService guides,
        DemoWorkspaceService workspaces,
        DemoStateService stateService,
        SyncAuditService auditService
    ) {
        this.indexingGateway = Objects.requireNonNull(indexingGateway);
        this.workProjection = Objects.requireNonNull(workProjection);
        this.analysisHandler = Objects.requireNonNull(analysisHandler);
        this.products = Objects.requireNonNull(products);
        this.policies = Objects.requireNonNull(policies);
        this.guides = Objects.requireNonNull(guides);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.stateService = Objects.requireNonNull(stateService);
        this.auditService = Objects.requireNonNull(auditService);
    }

    public LifecycleWorkResponse start(
        String workspaceId,
        String scenario,
        EntityKind kind,
        String recordKey
    ) {
        workspaces.requireWorkspace(workspaceId);
        Object entity = entity(workspaceId, kind, recordKey);
        AIIndexDocument projected = indexingGateway.preview(
            entity,
            AIProcessOperation.UPDATE
        );
        String normalized = SyncEntitySupport.requireText(
            scenario,
            "scenario"
        ).toLowerCase(java.util.Locale.ROOT);
        IndexingOutcome outcome;
        String guidance;
        if ("superseded".equals(normalized)) {
            outcome = indexingGateway.submit(
                withSourceVersion(
                    projected,
                    Math.max(0L, projected.sourceVersion() - 1L),
                    AIIndexWorkType.UPSERT,
                    "superseded"
                ),
                IndexingStrategy.ASYNC
            );
            guidance = "This deliberately stale revision must finish as "
                + "SUPERSEDED and must not overwrite the current vector.";
        } else if ("retry-recovery".equals(normalized)) {
            AIIndexDocument analysis = withSourceVersion(
                projected,
                projected.sourceVersion(),
                AIIndexWorkType.ANALYZE,
                "retry-recovery"
            );
            analysisHandler.failNext(analysis.entityId(), 1);
            outcome = indexingGateway.submit(
                analysis,
                IndexingStrategy.ASYNC
            );
            guidance = "The controlled dependency fails once. The same work "
                + "ID remains durable and should complete on a bounded retry.";
        } else if ("dead-letter".equals(normalized)) {
            AIIndexDocument analysis = withSourceVersion(
                projected,
                projected.sourceVersion(),
                AIIndexWorkType.ANALYZE,
                "dead-letter"
            );
            analysisHandler.failNext(
                analysis.entityId(),
                PERMANENT_FAILURE_ATTEMPTS
            );
            outcome = indexingGateway.submit(
                analysis,
                IndexingStrategy.ASYNC
            );
            guidance = "The controlled dependency remains unavailable until "
                + "the configured retry budget is exhausted. No payload or "
                + "worker identity is exposed.";
        } else {
            throw new IllegalArgumentException(
                "Lifecycle scenario must be superseded, retry-recovery, or dead-letter"
            );
        }
        IndexingWorkView work = workProjection.requireOutcome(
            workspaceId,
            outcome
        );
        VectorProof vector = stateService.proof(
            kind,
            projected.entityId(),
            Math.toIntExact(projected.sourceVersion())
        );
        auditService.record(
            workspaceId,
            normalized.toUpperCase(java.util.Locale.ROOT),
            kind,
            recordKey,
            title(entity),
            Math.toIntExact(projected.sourceVersion()),
            true,
            vector.present(),
            vector.inSync(),
            0,
            outcome,
            guidance
        );
        return new LifecycleWorkResponse(
            normalized,
            guidance,
            workProjection.metadata(outcome, work),
            work,
            stateService.state(workspaceId)
        );
    }

    private Object entity(
        String workspaceId,
        EntityKind kind,
        String recordKey
    ) {
        return switch (kind) {
            case PRODUCT -> products.require(workspaceId, recordKey);
            case POLICY -> policies.require(workspaceId, recordKey);
            case GUIDE -> guides.require(workspaceId, recordKey);
        };
    }

    private String title(Object entity) {
        if (entity instanceof SyncProduct product) {
            return product.getTitle();
        }
        if (entity instanceof SyncPolicy policy) {
            return policy.getTitle();
        }
        return ((SyncGuide) entity).getTitle();
    }

    private AIIndexDocument withSourceVersion(
        AIIndexDocument source,
        long sourceVersion,
        AIIndexWorkType workType,
        String scenario
    ) {
        String entityId = workType == AIIndexWorkType.ANALYZE
            ? source.entityId() + ":lifecycle-"
                + java.util.UUID.randomUUID().toString().substring(0, 8)
            : source.entityId();
        return new AIIndexDocument(
            source.schemaVersion(),
            source.descriptorHash(),
            source.entityType(),
            entityId,
            workType,
            source.sourceOperation(),
            source.semanticSearchText(),
            source.ragContextText(),
            source.vectorMetadata(),
            source.llmContext(),
            source.responseMetadata(),
            sourceVersion,
            "live-sync-" + scenario + "-" + Instant.now().toEpochMilli(),
            Instant.now()
        );
    }
}
