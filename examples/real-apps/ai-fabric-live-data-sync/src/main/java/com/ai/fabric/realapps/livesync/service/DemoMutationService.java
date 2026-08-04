package com.ai.fabric.realapps.livesync.service;

import com.ai.fabric.realapps.livesync.domain.SyncGuide;
import com.ai.fabric.realapps.livesync.domain.SyncPolicy;
import com.ai.fabric.realapps.livesync.domain.SyncProduct;
import com.ai.fabric.realapps.livesync.web.DemoModels.EntityUpdateRequest;
import com.ai.fabric.realapps.livesync.web.DemoModels.IndexingWorkView;
import com.ai.fabric.realapps.livesync.web.DemoModels.MutationResponse;
import com.ai.fabric.realapps.livesync.web.DemoModels.SyncEvent;
import com.ai.fabric.realapps.livesync.web.DemoModels.VectorProof;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DemoMutationService {

    private final SyncProductService productService;
    private final SyncPolicyService policyService;
    private final SyncGuideService guideService;
    private final DemoWorkspaceService workspaceService;
    private final DemoStateService stateService;
    private final SyncAuditService auditService;
    private final IndexingWorkProjectionService indexingWorkProjectionService;

    public MutationResponse create(
        String workspaceId,
        EntityKind kind,
        String recordKey,
        EntityUpdateRequest request
    ) {
        workspaceService.requireWorkspace(workspaceId);
        long started = System.nanoTime();
        MutationTarget target = switch (kind) {
            case PRODUCT -> fromProduct(productService.createProductTracked(
                workspaceId,
                recordKey,
                request.title(),
                request.summary(),
                request.specification(),
                request.category(),
                request.price(),
                request.status()
            ));
            case POLICY -> fromPolicy(policyService.createPolicyTracked(
                workspaceId,
                recordKey,
                request.title(),
                request.guidance(),
                request.audience(),
                request.status(),
                request.effectiveDate()
            ));
            case GUIDE -> fromGuide(guideService.createGuideTracked(
                workspaceId,
                recordKey,
                request.title(),
                request.symptoms(),
                request.resolution(),
                request.productArea(),
                request.severity()
            ));
        };
        return response(
            workspaceId,
            "CREATE",
            kind,
            target,
            true,
            stateService.proof(kind, target.entityId(), target.revision()),
            started
        );
    }

    public MutationResponse update(
        String workspaceId,
        EntityKind kind,
        String recordKey,
        EntityUpdateRequest request
    ) {
        workspaceService.requireWorkspace(workspaceId);
        long started = System.nanoTime();
        MutationTarget target = switch (kind) {
            case PRODUCT -> fromProduct(productService.updateProductTracked(
                workspaceId,
                recordKey,
                request.title(),
                request.summary(),
                request.specification(),
                request.category(),
                request.price(),
                request.status()
            ));
            case POLICY -> fromPolicy(policyService.updatePolicyTracked(
                workspaceId,
                recordKey,
                request.title(),
                request.guidance(),
                request.audience(),
                request.status(),
                request.effectiveDate()
            ));
            case GUIDE -> fromGuide(guideService.updateGuideTracked(
                workspaceId,
                recordKey,
                request.title(),
                request.symptoms(),
                request.resolution(),
                request.productArea(),
                request.severity()
            ));
        };
        VectorProof vector = stateService.proof(kind, target.entityId(), target.revision());
        return response(
            workspaceId,
            "UPDATE",
            kind,
            target,
            true,
            vector,
            started
        );
    }

    public MutationResponse delete(String workspaceId, EntityKind kind, String recordKey) {
        workspaceService.requireWorkspace(workspaceId);
        long started = System.nanoTime();
        MutationTarget deleted = switch (kind) {
            case PRODUCT -> fromProduct(productService.deleteProductTracked(workspaceId, recordKey));
            case POLICY -> fromPolicy(policyService.deletePolicyTracked(workspaceId, recordKey));
            case GUIDE -> fromGuide(guideService.deleteGuideTracked(workspaceId, recordKey));
        };
        boolean vectorPresent = stateService.vectorPresent(kind, deleted.entityId());
        boolean inSync = !vectorPresent;
        return response(
            workspaceId,
            "DELETE",
            kind,
            deleted,
            false,
            new VectorProof(
                vectorPresent,
                inSync,
                null,
                null,
                java.util.Map.of(),
                inSync
                    ? "Database row and vector were removed"
                    : "Database row was removed, but a stale vector is still present"
            ),
            started
        );
    }

    private MutationTarget fromProduct(
        TrackedEntityMutation<SyncProduct> mutation
    ) {
        SyncProduct product = mutation.entity();
        return new MutationTarget(
            product.getId(),
            product.getRecordKey(),
            product.getTitle(),
            product.getRevision(),
            mutation.indexing()
        );
    }

    private MutationTarget fromPolicy(
        TrackedEntityMutation<SyncPolicy> mutation
    ) {
        SyncPolicy policy = mutation.entity();
        return new MutationTarget(
            policy.getId(),
            policy.getRecordKey(),
            policy.getTitle(),
            policy.getRevision(),
            mutation.indexing()
        );
    }

    private MutationTarget fromGuide(
        TrackedEntityMutation<SyncGuide> mutation
    ) {
        SyncGuide guide = mutation.entity();
        return new MutationTarget(
            guide.getId(),
            guide.getRecordKey(),
            guide.getTitle(),
            guide.getRevision(),
            mutation.indexing()
        );
    }

    private MutationResponse response(
        String workspaceId,
        String operation,
        EntityKind kind,
        MutationTarget target,
        boolean sourcePresent,
        VectorProof vector,
        long started
    ) {
        IndexingWorkView indexing = indexingWorkProjectionService
            .requireOutcome(workspaceId, target.indexing());
        SyncEvent event = auditService.record(
            workspaceId,
            operation,
            kind,
            target.recordKey(),
            target.title(),
            target.revision(),
            sourcePresent,
            vector.present(),
            vector.inSync(),
            elapsedMillis(started),
            target.indexing(),
            mutationMessage(operation, vector, indexing)
        );
        return new MutationResponse(
            event,
            stateService.state(workspaceId),
            indexingWorkProjectionService.metadata(
                target.indexing(),
                indexing
            ),
            indexing
        );
    }

    private String mutationMessage(
        String operation,
        VectorProof vector,
        IndexingWorkView indexing
    ) {
        if (indexing.inProgress()) {
            return "Source " + operation.toLowerCase()
                + " was accepted; derived indexing work is unfinished";
        }
        if (indexing.requiresOperatorReview()) {
            return "Source " + operation.toLowerCase()
                + " was accepted; derived indexing work requires operator review";
        }
        if (vector.inSync()) {
            return "Source and vector state are synchronized";
        }
        return "Source mutation completed, but vector proof did not match";
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private record MutationTarget(
        String entityId,
        String recordKey,
        String title,
        Integer revision,
        ai.fabric.indexing.api.IndexingOutcome indexing
    ) {
    }
}
