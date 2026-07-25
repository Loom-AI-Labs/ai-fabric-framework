package ai.fabric.migration.service;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.indexing.api.AIEntityIndexingGateway;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.indexing.model.AIEntityDescriptor;
import ai.fabric.indexing.projection.AIProjectionValidationException;
import ai.fabric.migration.config.MigrationProperties;
import ai.fabric.migration.config.MigrationFieldConfig;
import ai.fabric.migration.domain.MigrationFilters;
import ai.fabric.migration.domain.MigrationJob;
import ai.fabric.migration.domain.MigrationProgress;
import ai.fabric.migration.domain.MigrationRequest;
import ai.fabric.migration.domain.MigrationStatus;
import ai.fabric.migration.repository.MigrationJobRepository;
import ai.fabric.rag.VectorDatabaseService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

@Slf4j
public class DataMigrationService {

    private final AIEntityConfigurationLoader configLoader;
    private final AIEntityDescriptorRegistry descriptorRegistry;
    private final AIEntityIndexingGateway indexingGateway;
    private final EntityRepositoryRegistry repositoryRegistry;
    private final MigrationJobRepository jobRepository;
    private final VectorDatabaseService vectorDatabaseService;
    private final MigrationProgressTracker progressTracker;
    private final MigrationProperties migrationProperties;
    private final ExecutorService executorService;
    private final Clock clock;
    private final List<MigrationFilterPolicy> filterPolicies;

    public DataMigrationService(
        AIEntityConfigurationLoader configLoader,
        AIEntityDescriptorRegistry descriptorRegistry,
        AIEntityIndexingGateway indexingGateway,
        EntityRepositoryRegistry repositoryRegistry,
        MigrationJobRepository jobRepository,
        VectorDatabaseService vectorDatabaseService,
        MigrationProgressTracker progressTracker,
        MigrationProperties migrationProperties,
        ExecutorService executorService,
        Clock clock,
        List<MigrationFilterPolicy> filterPolicies
    ) {
        this.configLoader = configLoader;
        this.descriptorRegistry = descriptorRegistry;
        this.indexingGateway = indexingGateway;
        this.repositoryRegistry = repositoryRegistry;
        this.jobRepository = jobRepository;
        this.vectorDatabaseService = vectorDatabaseService;
        this.progressTracker = progressTracker;
        this.migrationProperties = migrationProperties;
        this.executorService = executorService;
        this.clock = clock;
        this.filterPolicies = filterPolicies != null ? filterPolicies : List.of();
    }

    /**
     * Convenience API for migrating all entities with defaults.
     */
    public MigrationJob indexAllEntities(String entityType) {
        MigrationRequest request = MigrationRequest.builder()
            .entityType(entityType)
            .batchSize(migrationProperties.getDefaultBatchSize())
            .rateLimit(migrationProperties.getDefaultRateLimit())
            .reindexExisting(false)
            .build();
        return startMigration(request);
    }

    /**
     * Starts a migration job asynchronously.
     */
    public MigrationJob startMigration(@Valid MigrationRequest request) {
        String entityType = requireEntityType(request);
        EntityRegistration registration = repositoryRegistry.getRegistration(entityType);
        AIEntityDescriptor descriptor = descriptorRegistry.resolve(
            registration.entityClass()
        );
        if (!descriptor.entityType().equals(entityType)) {
            throw new IllegalStateException(
                "Migration registration entity type does not match descriptor"
            );
        }
        if (!descriptor.indexingEnabled()) {
            throw new IllegalStateException(
                "Indexing is disabled for migration entity type: " + entityType
            );
        }
        JpaRepository<?, ?> repository = registration.repository();

        enforceFilterSupport(entityType, request.getFilters());

        long total = repository.count();
        MigrationJob job = createJob(request, entityType, total);
        jobRepository.save(job);

        executorService.submit(() -> processJob(
            job.getId(),
            request,
            registration,
            descriptor
        ));
        return job;
    }

    public MigrationProgress getProgress(String jobId) {
        MigrationJob job = jobRepository.findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        return progressTracker.toProgress(job);
    }

    @Transactional
    public void pauseMigration(String jobId) {
        updateStatus(jobId, MigrationStatus.PAUSED, MigrationStatus.RUNNING);
    }

    @Transactional
    public void resumeMigration(String jobId) {
        MigrationJob job = findJob(jobId);
        validateTransition(job, MigrationStatus.RUNNING, MigrationStatus.PAUSED);
        EntityRegistration registration = repositoryRegistry.getRegistration(job.getEntityType());
        AIEntityDescriptor descriptor = descriptorRegistry.resolve(
            registration.entityClass()
        );
        MigrationRequest resumeRequest = MigrationRequest.builder()
            .entityType(job.getEntityType())
            .batchSize(job.getBatchSize())
            .rateLimit(job.getRateLimit())
            .reindexExisting(job.getReindexExisting())
            .filters(job.getFilters())
            .createdBy(job.getCreatedBy())
            .build();
        job.setStatus(MigrationStatus.RUNNING);
        job.setLastUpdatedAt(LocalDateTime.now(clock));
        jobRepository.save(job);
        executorService.submit(() -> processJob(
            job.getId(),
            resumeRequest,
            registration,
            descriptor
        ));
    }

    @Transactional
    public void cancelMigration(String jobId) {
        MigrationJob job = updateStatus(
            jobId,
            MigrationStatus.CANCELLED,
            MigrationStatus.PENDING,
            MigrationStatus.RUNNING,
            MigrationStatus.PAUSED
        );
        job.setCompletedAt(LocalDateTime.now(clock));
        jobRepository.save(job);
    }

    public Iterable<MigrationJob> listJobs() {
        return jobRepository.findAll();
    }

    private MigrationJob createJob(MigrationRequest request, String entityType, long totalCount) {
        LocalDateTime now = LocalDateTime.now(clock);
        return MigrationJob.builder()
            .id("mig-" + UUID.randomUUID())
            .entityType(entityType)
            .status(MigrationStatus.RUNNING)
            .totalEntities(totalCount)
            .processedEntities(0L)
            .failedEntities(0L)
            .projectionFailures(0L)
            .enqueueFailures(0L)
            .currentPage(0)
            .batchSize(defaultBatchSize(request))
            .rateLimit(request.getRateLimit())
            .reindexExisting(Boolean.TRUE.equals(request.getReindexExisting()))
            .filters(request.getFilters())
            .createdBy(request.getCreatedBy())
            .startedAt(now)
            .lastUpdatedAt(now)
            .build();
    }

    private Integer defaultBatchSize(MigrationRequest request) {
        if (request.getBatchSize() != null && request.getBatchSize() > 0) {
            return request.getBatchSize();
        }
        return migrationProperties.getDefaultBatchSize();
    }

    private void processJob(
        String jobId,
        MigrationRequest request,
        EntityRegistration registration,
        AIEntityDescriptor descriptor
    ) {
        try {
            while (true) {
                MigrationJob job = jobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

                if (job.isPaused()) {
                    log.info("Migration job {} paused at page {}", jobId, job.getCurrentPage());
                    return;
                }
                if (job.isCancelled()) {
                    log.info("Migration job {} cancelled", jobId);
                    return;
                }

                Page<?> page = registration.repository()
                    .findAll(PageRequest.of(job.getCurrentPage(), job.getBatchSize()));
                if (page.isEmpty()) {
                    markCompleted(job);
                    return;
                }

                int processed = 0;
                int projectionFailures = 0;
                int enqueueFailures = 0;

                MigrationFieldConfig fieldConfig = migrationProperties.getEntityFields().get(job.getEntityType());
                MigrationFilterPolicy policy = resolvePolicy(job.getEntityType());
                AIEntityConfig config = configLoader.getEntityConfig(
                    job.getEntityType()
                );

                for (Object entity : page.getContent()) {
                    processed++;
                    try {
                        if (!matchesFilters(entity, request, config, fieldConfig, policy)) {
                            continue;
                        }

                        String entityId = resolveEntityId(descriptor, entity);
                        if (entityId == null || entityId.isBlank()) {
                            log.debug("Skipping entity without resolvable id: {}", entity.getClass().getSimpleName());
                            continue;
                        }

                        if (!Boolean.TRUE.equals(request.getReindexExisting())
                            && alreadyIndexed(descriptor.entityType(), entityId)) {
                            continue;
                        }

                        indexingGateway.upsert(
                            entity,
                            AIProcessOperation.CREATE,
                            IndexingStrategy.ASYNC
                        );
                    } catch (AIProjectionValidationException ex) {
                        log.warn(
                            "Projection rejected migration entity type={} code={}",
                            job.getEntityType(),
                            ex.getErrorCode()
                        );
                        projectionFailures++;
                    } catch (Exception ex) {
                        log.warn(
                            "Failed to enqueue migration entity type={} cause={}",
                            job.getEntityType(),
                            ex.getClass().getSimpleName()
                        );
                        enqueueFailures++;
                    }
                }

                job.setProcessedEntities(job.getProcessedEntities() + processed);
                job.setProjectionFailures(
                    count(job.getProjectionFailures()) + projectionFailures
                );
                job.setEnqueueFailures(
                    count(job.getEnqueueFailures()) + enqueueFailures
                );
                job.setFailedEntities(
                    count(job.getFailedEntities())
                        + projectionFailures
                        + enqueueFailures
                );
                job.setCurrentPage(job.getCurrentPage() + 1);
                job.setLastUpdatedAt(LocalDateTime.now(clock));
                jobRepository.save(job);

                applyRateLimit(job);
            }
        } catch (Exception ex) {
            log.error("Migration job {} failed", jobId, ex);
            MigrationJob job = jobRepository.findById(jobId).orElse(null);
            if (job != null) {
                job.setStatus(MigrationStatus.FAILED);
                job.setErrorMessage(exceptionSummary(ex));
                job.setLastUpdatedAt(LocalDateTime.now(clock));
                jobRepository.save(job);
            }
        }
    }

    private void markCompleted(MigrationJob job) {
        job.setStatus(MigrationStatus.COMPLETED);
        job.setCompletedAt(LocalDateTime.now(clock));
        job.setLastUpdatedAt(job.getCompletedAt());
        jobRepository.save(job);
    }

    private boolean matchesFilters(Object entity,
                                   MigrationRequest request,
                                   AIEntityConfig config,
                                   MigrationFieldConfig fieldConfig,
                                   MigrationFilterPolicy policy) {
        MigrationFilters filters = request.getFilters();
        if (filters == null || filters.isEmpty()) {
            return true;
        }

        if (policy != null) {
            return policy.shouldMigrate(entity, request, config);
        }

        requireFieldConfig(request.getEntityType(), fieldConfig);

        AIEntityDescriptor descriptor = descriptorRegistry.resolve(entity);
        String entityId = resolveEntityId(descriptor, entity);
        if (!filters.safeEntityIds().isEmpty() && !filters.safeEntityIds().contains(entityId)) {
            return false;
        }

        LocalDate createdDate = resolveCreatedDate(entity, fieldConfig.getCreatedAtField());
        if (filters.getCreatedBefore() != null) {
            if (createdDate == null) {
                throw new IllegalStateException("createdAt field '%s' missing or null for entity %s"
                    .formatted(fieldConfig.getCreatedAtField(), entity.getClass().getName()));
            }
            if (!createdDate.isBefore(filters.getCreatedBefore())) {
                return false;
            }
        }
        if (filters.getCreatedAfter() != null) {
            if (createdDate == null) {
                throw new IllegalStateException("createdAt field '%s' missing or null for entity %s"
                    .formatted(fieldConfig.getCreatedAtField(), entity.getClass().getName()));
            }
            if (!createdDate.isAfter(filters.getCreatedAfter())) {
                return false;
            }
        }
        return true;
    }

    private LocalDate resolveCreatedDate(Object entity, String fieldName) {
        try {
            Field field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(entity);
            if (value instanceof LocalDateTime dateTime) {
                return dateTime.toLocalDate();
            }
            if (value instanceof LocalDate date) {
                return date;
            }
            throw new IllegalStateException("Field '%s' on %s is not LocalDate/LocalDateTime"
                .formatted(fieldName, entity.getClass().getName()));
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("Field '%s' not found on %s".formatted(fieldName, entity.getClass().getName()), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access field '%s' on %s".formatted(fieldName, entity.getClass().getName()), e);
        }
    }

    private void enforceFilterSupport(
        String entityType,
        MigrationFilters filters
    ) {
        if (filters == null || filters.isEmpty()) {
            return;
        }
        boolean hasPolicy = resolvePolicy(entityType) != null;
        boolean hasConfig = migrationProperties.getEntityFields().containsKey(entityType);
        if (!hasPolicy && !hasConfig) {
            throw new IllegalStateException("Migration requires a policy or entityFields config for entityType: " + entityType);
        }
    }

    private MigrationFilterPolicy resolvePolicy(String entityType) {
        return filterPolicies.stream()
            .filter(p -> p.supports(entityType))
            .findFirst()
            .orElse(null);
    }

    private void requireFieldConfig(String entityType, MigrationFieldConfig fieldConfig) {
        if (fieldConfig == null) {
            throw new IllegalStateException("Missing migration entityFields config for entityType: " + entityType);
        }
        if (fieldConfig.getCreatedAtField() == null || fieldConfig.getCreatedAtField().isBlank()) {
            throw new IllegalStateException("createdAtField must be configured for entityType: " + entityType);
        }
    }

    private boolean alreadyIndexed(String entityType, String entityId) {
        return vectorDatabaseService.vectorExists(entityType, entityId);
    }

    private String resolveEntityId(
        AIEntityDescriptor descriptor,
        Object entity
    ) {
        Object identity = descriptor.identityResolver().resolveIdentity(entity);
        if (identity == null || identity.toString().isBlank()) {
            return null;
        }
        return identity.toString().trim();
    }

    private long count(Long value) {
        return value == null ? 0L : value;
    }

    private void applyRateLimit(MigrationJob job) {
        Integer limit = job.getRateLimit() != null ? job.getRateLimit() : migrationProperties.getDefaultRateLimit();
        if (limit == null || limit <= 0) {
            return;
        }
        long delayMs = Math.max(1, (60_000L / limit));
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private MigrationJob updateStatus(String jobId, MigrationStatus status, MigrationStatus... allowedCurrentStatuses) {
        MigrationJob job = findJob(jobId);
        validateTransition(job, status, allowedCurrentStatuses);
        job.setStatus(status);
        job.setLastUpdatedAt(LocalDateTime.now(clock));
        return jobRepository.save(job);
    }

    private MigrationJob findJob(String jobId) {
        return jobRepository.findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
    }

    private void validateTransition(MigrationJob job,
                                    MigrationStatus targetStatus,
                                    MigrationStatus... allowedCurrentStatuses) {
        MigrationStatus currentStatus = job.getStatus();
        if (currentStatus == null) {
            throw new IllegalStateException("Migration job " + job.getId() + " has no current status.");
        }
        boolean allowed = Arrays.asList(allowedCurrentStatuses).contains(currentStatus);
        if (!allowed) {
            throw new IllegalStateException("Cannot transition migration job %s from %s to %s."
                .formatted(job.getId(), currentStatus, targetStatus));
        }
    }

    private String requireEntityType(MigrationRequest request) {
        if (request == null || request.getEntityType() == null || request.getEntityType().isBlank()) {
            throw new IllegalArgumentException("entityType is required for migration.");
        }
        return request.getEntityType().trim();
    }

    private String exceptionSummary(Exception ex) {
        if (ex == null) {
            return "Exception";
        }
        return ex.getMessage() == null || ex.getMessage().isBlank()
            ? ex.getClass().getSimpleName()
            : ex.getMessage().trim();
    }
}
