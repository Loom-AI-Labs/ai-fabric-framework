package ai.fabric.indexing;

import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.api.AIEntityIndexingGateway;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingDispatchStatus;
import ai.fabric.indexing.api.IndexingOutcome;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.indexing.model.AIEntityDescriptor;
import ai.fabric.indexing.model.AIIndexDocument;
import ai.fabric.indexing.observability.IndexingMetrics;
import ai.fabric.indexing.projection.AIEntityProjectionService;
import ai.fabric.indexing.queue.IndexingQueueService;
import ai.fabric.indexing.worker.IndexingExecutionException;
import ai.fabric.indexing.worker.IndexingWorkProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.Optional;

/**
 * Transaction-aware implementation of the public indexing gateway.
 */
public class DefaultAIEntityIndexingGateway
    implements AIEntityIndexingGateway {

    private static final Logger log = LoggerFactory.getLogger(
        DefaultAIEntityIndexingGateway.class
    );

    private final AIEntityProjectionService projectionService;
    private final AIEntityDescriptorRegistry descriptorRegistry;
    private final IndexingQueueService queueService;
    private final IndexingWorkProcessor workProcessor;
    private final IndexingMetrics metrics;

    public DefaultAIEntityIndexingGateway(
        AIEntityProjectionService projectionService,
        AIEntityDescriptorRegistry descriptorRegistry,
        IndexingQueueService queueService,
        IndexingWorkProcessor workProcessor,
        IndexingMetrics metrics
    ) {
        this.projectionService = Objects.requireNonNull(projectionService);
        this.descriptorRegistry = Objects.requireNonNull(descriptorRegistry);
        this.queueService = Objects.requireNonNull(queueService);
        this.workProcessor = Objects.requireNonNull(workProcessor);
        this.metrics = Objects.requireNonNull(metrics);
    }

    public DefaultAIEntityIndexingGateway(
        AIEntityProjectionService projectionService,
        AIEntityDescriptorRegistry descriptorRegistry,
        IndexingQueueService queueService,
        IndexingWorkProcessor workProcessor
    ) {
        this(
            projectionService,
            descriptorRegistry,
            queueService,
            workProcessor,
            new IndexingMetrics(null)
        );
    }

    @Override
    public IndexingOutcome upsert(Object entity, AIProcessOperation operation) {
        return upsert(entity, operation, IndexingStrategy.AUTO);
    }

    @Override
    public IndexingOutcome upsert(
        Object entity,
        AIProcessOperation operation,
        IndexingStrategy strategyOverride
    ) {
        if (operation != AIProcessOperation.CREATE
            && operation != AIProcessOperation.UPDATE) {
            throw new IllegalArgumentException("Upsert operation must be CREATE or UPDATE");
        }
        AIEntityDescriptor descriptor = descriptorRegistry.resolve(entity);
        IndexingStrategy strategy = strategy(descriptor, operation, strategyOverride);
        try {
            AIIndexDocument document = projectionService.project(
                entity,
                operation,
                correlationId()
            );
            return dispatch(document, strategy);
        } catch (RuntimeException exception) {
            metrics.projectionFailure(descriptor.entityType(), strategy, operation);
            throw exception;
        }
    }

    @Override
    public IndexingOutcome delete(Object entity) {
        return delete(entity, IndexingStrategy.AUTO);
    }

    @Override
    public IndexingOutcome delete(
        Object entity,
        IndexingStrategy strategyOverride
    ) {
        AIEntityDescriptor descriptor = descriptorRegistry.resolve(entity);
        IndexingStrategy strategy = strategy(
            descriptor,
            AIProcessOperation.DELETE,
            strategyOverride
        );
        try {
            AIIndexDocument document = projectionService.projectDelete(
                entity,
                AIProcessOperation.DELETE,
                correlationId()
            );
            return dispatch(document, strategy);
        } catch (RuntimeException exception) {
            metrics.projectionFailure(
                descriptor.entityType(),
                strategy,
                AIProcessOperation.DELETE
            );
            throw exception;
        }
    }

    @Override
    public IndexingOutcome delete(Class<?> entityClass, String entityId) {
        return delete(entityClass, entityId, IndexingStrategy.AUTO);
    }

    @Override
    public IndexingOutcome delete(
        Class<?> entityClass,
        String entityId,
        IndexingStrategy strategyOverride
    ) {
        AIEntityDescriptor descriptor = descriptorRegistry.resolve(entityClass);
        AIIndexDocument document = projectionService.projectDelete(
            entityClass,
            entityId,
            AIProcessOperation.DELETE,
            correlationId()
        );
        return dispatch(
            document,
            strategy(descriptor, AIProcessOperation.DELETE, strategyOverride)
        );
    }

    @Override
    public IndexingOutcome submit(
        AIIndexDocument document,
        IndexingStrategy strategy
    ) {
        Objects.requireNonNull(document, "document is required");
        if (strategy == null || strategy == IndexingStrategy.AUTO) {
            throw new IllegalArgumentException(
                "A concrete indexing strategy is required for projected documents"
            );
        }
        return dispatch(document, strategy, false);
    }

    @Override
    public AIIndexDocument preview(Object entity, AIProcessOperation operation) {
        return operation == AIProcessOperation.DELETE
            ? projectionService.projectDelete(entity, operation, correlationId())
            : projectionService.project(entity, operation, correlationId());
    }

    private IndexingOutcome dispatch(
        AIIndexDocument document,
        IndexingStrategy strategy
    ) {
        return dispatch(document, strategy, true);
    }

    private IndexingOutcome dispatch(
        AIIndexDocument document,
        IndexingStrategy strategy,
        boolean includeConfiguredAnalysis
    ) {
        IndexingQueueEntry primary = enqueue(document, strategy, null);
        IndexingDispatchStatus status = registerOrProcess(primary, strategy);
        if (includeConfiguredAnalysis) {
            enqueueAnalysisIfEnabled(document, strategy, primary.getId());
        }
        return outcome(primary, strategy, status);
    }

    private void enqueueAnalysisIfEnabled(
        AIIndexDocument primary,
        IndexingStrategy strategy,
        long primaryWorkId
    ) {
        AIEntityDescriptor descriptor = descriptorRegistry.getByEntityType(
            primary.entityType()
        );
        if (!descriptor.analysisPolicy().enabled()
            || !descriptor.analysisPolicy().after().contains(primary.sourceOperation())
            || primary.workType() != AIIndexWorkType.UPSERT) {
            return;
        }
        AIIndexDocument analysis = new AIIndexDocument(
            primary.schemaVersion(),
            primary.descriptorHash(),
            primary.entityType(),
            primary.entityId(),
            AIIndexWorkType.ANALYZE,
            primary.sourceOperation(),
            primary.semanticSearchText(),
            primary.ragContextText(),
            primary.vectorMetadata(),
            primary.llmContext(),
            primary.responseMetadata(),
            primary.sourceVersion(),
            primary.correlationId(),
            primary.occurredAt()
        );
        queueService.enqueue(analysis, strategy, primaryWorkId);
    }

    private IndexingDispatchStatus registerOrProcess(
        IndexingQueueEntry entry,
        IndexingStrategy strategy
    ) {
        if (strategy != IndexingStrategy.SYNC) {
            return IndexingDispatchStatus.QUEUED;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        processSynchronously(entry.getId());
                    }
                }
            );
            return IndexingDispatchStatus.QUEUED;
        }
        return processSynchronously(entry.getId());
    }

    private IndexingDispatchStatus processSynchronously(long workId) {
        try {
            Optional<IndexingQueueEntry> claimed = queueService.claimSynchronous(workId);
            if (claimed.isEmpty()) {
                return currentStatus(workId);
            }
            IndexingQueueEntry entry = claimed.get();
            IndexingWorkProcessor.WorkResult result = workProcessor.process(entry);
            if (result.status() == IndexingDispatchStatus.SKIPPED_STALE) {
                queueService.markSuperseded(workId);
                return IndexingDispatchStatus.SKIPPED_STALE;
            } else {
                queueService.markCompleted(workId, result.resultPayload());
                return IndexingDispatchStatus.COMPLETED;
            }
        } catch (Exception exception) {
            String code = exception instanceof IndexingExecutionException executionException
                ? executionException.getErrorCode()
                : "SYNC_INDEXING_" + exception.getClass().getSimpleName();
            try {
                queueService.markFailure(workId, code);
            } catch (RuntimeException acknowledgementFailure) {
                log.error(
                    "Synchronous indexing {} and failure acknowledgement both failed with code {}",
                    workId,
                    safeCode(acknowledgementFailure)
                );
                log.error("Synchronous indexing {} failed with {}", workId, code);
                return IndexingDispatchStatus.FAILED_RETRYABLE;
            }
            log.error("Synchronous indexing {} failed with {}", workId, code);
            return failureStatus(workId);
        }
    }

    private IndexingDispatchStatus failureStatus(long workId) {
        return switch (queueService.requireEntry(workId).getStatus()) {
            case DEAD_LETTER -> IndexingDispatchStatus.FAILED_PERMANENT;
            case COMMIT_PENDING, PENDING, PROCESSING ->
                IndexingDispatchStatus.FAILED_RETRYABLE;
            case COMPLETED -> IndexingDispatchStatus.COMPLETED;
            case SUPERSEDED -> IndexingDispatchStatus.SKIPPED_STALE;
        };
    }

    private IndexingDispatchStatus currentStatus(long workId) {
        return switch (queueService.requireEntry(workId).getStatus()) {
            case COMPLETED -> IndexingDispatchStatus.COMPLETED;
            case SUPERSEDED -> IndexingDispatchStatus.SKIPPED_STALE;
            case DEAD_LETTER -> IndexingDispatchStatus.FAILED_PERMANENT;
            case COMMIT_PENDING, PENDING, PROCESSING ->
                IndexingDispatchStatus.QUEUED;
        };
    }

    private IndexingQueueEntry enqueue(
        AIIndexDocument document,
        IndexingStrategy strategy,
        Long dependsOnWorkId
    ) {
        if (strategy == IndexingStrategy.SYNC) {
            return dependsOnWorkId == null
                ? queueService.enqueueForSynchronousDispatch(document)
                : queueService.enqueueForSynchronousDispatch(
                    document,
                    dependsOnWorkId
                );
        }
        return dependsOnWorkId == null
            ? queueService.enqueue(document, strategy)
            : queueService.enqueue(document, strategy, dependsOnWorkId);
    }

    private IndexingStrategy strategy(
        AIEntityDescriptor descriptor,
        AIProcessOperation operation,
        IndexingStrategy override
    ) {
        return override == null || override == IndexingStrategy.AUTO
            ? descriptor.strategyFor(operation)
            : override;
    }

    private IndexingOutcome outcome(
        IndexingQueueEntry entry,
        IndexingStrategy strategy,
        IndexingDispatchStatus status
    ) {
        return new IndexingOutcome(
            String.valueOf(entry.getId()),
            entry.getEntityType(),
            entry.getEntityId(),
            entry.getWorkType(),
            strategy,
            status
        );
    }

    private String correlationId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String safeCode(RuntimeException exception) {
        if (exception instanceof IndexingExecutionException executionException) {
            return executionException.getErrorCode();
        }
        return "INDEXING_" + exception.getClass()
            .getSimpleName()
            .toUpperCase(java.util.Locale.ROOT);
    }
}
