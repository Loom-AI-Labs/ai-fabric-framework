package com.ai.fabric.realapps.livesync.service;

import ai.fabric.indexing.api.IndexingOutcome;
import ai.fabric.indexing.api.IndexingWorkQuery;
import ai.fabric.indexing.api.IndexingWorkStatus;
import com.ai.fabric.realapps.livesync.web.DemoModels.IndexingWorkView;
import com.ai.fabric.realapps.livesync.web.DemoModels.SyncEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class IndexingWorkProjectionService {

    private final IndexingWorkQuery workQuery;

    public IndexingWorkProjectionService(IndexingWorkQuery workQuery) {
        this.workQuery = Objects.requireNonNull(workQuery);
    }

    public IndexingWorkView requireForWorkspace(
        String workspaceId,
        String workId
    ) {
        String workspace = SyncEntitySupport.requireText(
            workspaceId,
            "workspaceId"
        );
        IndexingWorkStatus status = workQuery.findByWorkId(workId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Indexing work was not found"
            ));
        if (!status.entityId().startsWith(workspace + ":")) {
            throw new IllegalArgumentException(
                "Indexing work does not belong to this workspace"
            );
        }
        return view(status);
    }

    public IndexingWorkView requireOutcome(
        String workspaceId,
        IndexingOutcome outcome
    ) {
        Objects.requireNonNull(outcome, "indexing outcome is required");
        return requireForWorkspace(workspaceId, outcome.workId());
    }

    public List<IndexingWorkView> views(
        String workspaceId,
        List<SyncEvent> events
    ) {
        return events.stream()
            .map(SyncEvent::indexingWorkId)
            .filter(Objects::nonNull)
            .distinct()
            .map(workId -> requireForWorkspace(workspaceId, workId))
            .toList();
    }

    public Map<String, Object> metadata(
        IndexingOutcome outcome,
        IndexingWorkView status
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("indexingWorkId", outcome.workId());
        metadata.put("indexingDispatchStatus", outcome.status().name());
        metadata.put("indexingStrategy", outcome.strategy().name());
        metadata.put("indexingStatus", status.status());
        return Map.copyOf(metadata);
    }

    private IndexingWorkView view(IndexingWorkStatus status) {
        return new IndexingWorkView(
            status.workId(),
            status.entityType(),
            status.entityId(),
            status.workType().name(),
            status.sourceOperation().name(),
            status.strategy().name(),
            status.status().name(),
            status.retryCount(),
            status.maxRetries(),
            status.errorCode(),
            status.deadLetterReason(),
            status.correlationId(),
            status.isTerminal(),
            status.isSuccessfulTerminal(),
            status.isInProgress(),
            status.requiresOperatorReview(),
            status.requestedAt(),
            status.scheduledFor(),
            status.startedAt(),
            status.completedAt(),
            status.lastErrorAt(),
            status.updatedAt()
        );
    }
}
