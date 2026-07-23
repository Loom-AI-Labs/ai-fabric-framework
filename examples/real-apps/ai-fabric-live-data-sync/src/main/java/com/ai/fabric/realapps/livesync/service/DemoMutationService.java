package com.ai.fabric.realapps.livesync.service;

import com.ai.fabric.realapps.livesync.domain.SyncGuide;
import com.ai.fabric.realapps.livesync.domain.SyncPolicy;
import com.ai.fabric.realapps.livesync.domain.SyncProduct;
import com.ai.fabric.realapps.livesync.web.DemoModels.EntityUpdateRequest;
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

    public MutationResponse update(
        String workspaceId,
        EntityKind kind,
        String recordKey,
        EntityUpdateRequest request
    ) {
        workspaceService.requireWorkspace(workspaceId);
        long started = System.nanoTime();
        MutationTarget target = switch (kind) {
            case PRODUCT -> from(productService.updateProduct(
                workspaceId,
                recordKey,
                request.title(),
                request.summary(),
                request.specification(),
                request.category(),
                request.price(),
                request.status()
            ));
            case POLICY -> from(policyService.updatePolicy(
                workspaceId,
                recordKey,
                request.title(),
                request.guidance(),
                request.audience(),
                request.status(),
                request.effectiveDate()
            ));
            case GUIDE -> from(guideService.updateGuide(
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
        SyncEvent event = auditService.record(
            workspaceId,
            "UPDATE",
            kind,
            target.recordKey(),
            target.title(),
            target.revision(),
            true,
            vector.present(),
            vector.inSync(),
            elapsedMillis(started),
            vector.inSync()
                ? "Database update was reflected in the vector index"
                : "Database update completed, but vector proof did not match"
        );
        return new MutationResponse(event, stateService.state(workspaceId));
    }

    public MutationResponse delete(String workspaceId, EntityKind kind, String recordKey) {
        workspaceService.requireWorkspace(workspaceId);
        long started = System.nanoTime();
        MutationTarget deleted = switch (kind) {
            case PRODUCT -> from(productService.deleteProduct(workspaceId, recordKey));
            case POLICY -> from(policyService.deletePolicy(workspaceId, recordKey));
            case GUIDE -> from(guideService.deleteGuide(workspaceId, recordKey));
        };
        boolean vectorPresent = stateService.vectorPresent(kind, deleted.entityId());
        boolean inSync = !vectorPresent;
        SyncEvent event = auditService.record(
            workspaceId,
            "DELETE",
            kind,
            deleted.recordKey(),
            deleted.title(),
            deleted.revision(),
            false,
            vectorPresent,
            inSync,
            elapsedMillis(started),
            inSync
                ? "Database row and vector were removed"
                : "Database row was removed, but a stale vector is still present"
        );
        return new MutationResponse(event, stateService.state(workspaceId));
    }

    private MutationTarget from(SyncProduct product) {
        return new MutationTarget(product.getId(), product.getRecordKey(), product.getTitle(), product.getRevision());
    }

    private MutationTarget from(SyncPolicy policy) {
        return new MutationTarget(policy.getId(), policy.getRecordKey(), policy.getTitle(), policy.getRevision());
    }

    private MutationTarget from(SyncGuide guide) {
        return new MutationTarget(guide.getId(), guide.getRecordKey(), guide.getTitle(), guide.getRevision());
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private record MutationTarget(String entityId, String recordKey, String title, Integer revision) {
    }
}
