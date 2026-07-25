package ai.fabric.indexing.worker;

import ai.fabric.entity.IndexingEntityState;
import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingDispatchStatus;
import ai.fabric.indexing.model.AIIndexDocument;
import ai.fabric.indexing.queue.IndexingQueueService;
import ai.fabric.repository.IndexingEntityStateRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Serializes work per entity and prevents stale queued writes from winning.
 */
public class IndexingWorkProcessor {

    private final IndexingQueueService queueService;
    private final IndexingEntityStateRepository stateRepository;
    private final IndexingOperationExecutor operationExecutor;
    private final Clock clock;

    public IndexingWorkProcessor(
        IndexingQueueService queueService,
        IndexingEntityStateRepository stateRepository,
        IndexingOperationExecutor operationExecutor,
        Clock clock
    ) {
        this.queueService = Objects.requireNonNull(queueService);
        this.stateRepository = Objects.requireNonNull(stateRepository);
        this.operationExecutor = Objects.requireNonNull(operationExecutor);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WorkResult process(IndexingQueueEntry entry) {
        Objects.requireNonNull(entry, "entry is required");
        if (entry.getId() == null) {
            throw new IllegalArgumentException("Persisted work id is required");
        }

        AIIndexDocument document = queueService.readDocument(entry);
        if (!queueService.isDependencyCompleted(entry)) {
            return new WorkResult(IndexingDispatchStatus.SKIPPED_STALE, null);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String stateKey = stateKey(document.entityType(), document.entityId());
        IndexingEntityState state = stateRepository.findForUpdate(stateKey)
            .orElseGet(() -> stateRepository.saveAndFlush(
                new IndexingEntityState(
                    stateKey,
                    document.entityType(),
                    document.entityId(),
                    now
                )
            ));

        if (isStale(entry.getId(), document, state)) {
            return new WorkResult(IndexingDispatchStatus.SKIPPED_STALE, null);
        }

        String resultPayload = operationExecutor.execute(document, entry.getId());
        state.markApplied(entry.getId(), document.sourceVersion(), now);
        stateRepository.save(state);
        return new WorkResult(IndexingDispatchStatus.COMPLETED, resultPayload);
    }

    public static String stateKey(String entityType, String entityId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (entityType + '\u0000' + entityId).getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create indexing state key", exception);
        }
    }

    private boolean isStale(
        long workId,
        AIIndexDocument document,
        IndexingEntityState state
    ) {
        if (workId <= state.getLastAppliedWorkId()) {
            return true;
        }
        return document.sourceOperation() == AIProcessOperation.UPDATE
            && document.sourceVersion() != null
            && state.getLastSourceVersion() != null
            && document.sourceVersion() < state.getLastSourceVersion();
    }

    public record WorkResult(
        IndexingDispatchStatus status,
        String resultPayload
    ) {
    }
}
