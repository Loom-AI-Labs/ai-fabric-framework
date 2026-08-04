package com.ai.fabric.realapps.livesync.service;

import ai.fabric.indexing.api.IndexingOutcome;
import com.ai.fabric.realapps.livesync.domain.SyncAuditEntry;
import com.ai.fabric.realapps.livesync.repository.SyncAuditEntryRepository;
import com.ai.fabric.realapps.livesync.web.DemoModels.SyncEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SyncAuditService {

    private final SyncAuditEntryRepository repository;

    @Transactional
    public SyncEvent record(
        String workspaceId,
        String operation,
        EntityKind kind,
        String recordKey,
        String title,
        Integer revision,
        boolean sourcePresent,
        boolean vectorPresent,
        boolean inSync,
        long elapsedMs,
        IndexingOutcome indexing,
        String message
    ) {
        SyncAuditEntry entry = new SyncAuditEntry();
        entry.setId(UUID.randomUUID().toString());
        entry.setWorkspaceId(workspaceId);
        entry.setOperation(operation);
        entry.setKind(kind.name());
        entry.setEntityType(kind.entityType());
        entry.setRecordKey(recordKey);
        entry.setTitle(title);
        entry.setRevision(revision);
        entry.setSourcePresent(sourcePresent);
        entry.setVectorPresent(vectorPresent);
        entry.setInSync(inSync);
        entry.setElapsedMs(elapsedMs);
        entry.setIndexingWorkId(
            indexing != null ? indexing.workId() : null
        );
        entry.setIndexingDispatchStatus(
            indexing != null ? indexing.status().name() : null
        );
        entry.setMessage(message);
        entry.setOccurredAt(Instant.now());
        return toEvent(repository.save(entry));
    }

    @Transactional(readOnly = true)
    public List<SyncEvent> events(String workspaceId) {
        return repository
            .findTop30ByWorkspaceIdOrderByOccurredAtDesc(workspaceId)
            .stream()
            .map(this::toEvent)
            .toList();
    }

    @Transactional
    public void clear(String workspaceId) {
        repository.deleteByWorkspaceId(workspaceId);
    }

    private SyncEvent toEvent(SyncAuditEntry entry) {
        return new SyncEvent(
            entry.getId(),
            entry.getOperation(),
            entry.getKind(),
            entry.getEntityType(),
            entry.getRecordKey(),
            entry.getTitle(),
            entry.getRevision(),
            entry.isSourcePresent(),
            entry.isVectorPresent(),
            entry.isInSync(),
            entry.getElapsedMs(),
            entry.getIndexingWorkId(),
            entry.getIndexingDispatchStatus(),
            entry.getMessage(),
            entry.getOccurredAt()
        );
    }
}
