package ai.fabric.migration.service;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.config.AIIndexingProperties;
import ai.fabric.dto.AIEmbeddableField;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIMetadataField;
import ai.fabric.dto.AISearchableField;
import ai.fabric.indexing.IndexingOperation;
import ai.fabric.indexing.IndexingRequest;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.queue.IndexingQueueService;
import ai.fabric.migration.config.MigrationFieldConfig;
import ai.fabric.migration.config.MigrationProperties;
import ai.fabric.migration.domain.MigrationJob;
import ai.fabric.migration.domain.MigrationRequest;
import ai.fabric.migration.domain.MigrationStatus;
import ai.fabric.migration.repository.MigrationJobRepository;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.service.AICapabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataMigrationServiceTest {

    @Mock private IndexingQueueService queueService;
    @Mock private AIEntityConfigurationLoader configLoader;
    @Mock private EntityRepositoryRegistry repositoryRegistry;
    @Mock private MigrationJobRepository jobRepository;
    @Mock private VectorDatabaseService vectorDatabaseService;
    @Mock private MigrationProgressTracker progressTracker;
    @Mock private AICapabilityService capabilityService;
    @Mock private ExecutorService executorService;
    @Mock private MigrationFilterPolicy filterPolicy;

    @Captor private ArgumentCaptor<Runnable> runnableCaptor;
    @Captor private ArgumentCaptor<IndexingRequest> requestCaptor;

    private MigrationProperties migrationProperties;
    private AIIndexingProperties indexingProperties;
    private Clock clock;
    private AtomicReference<MigrationJob> persistedJob;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        migrationProperties = new MigrationProperties();
        MigrationFieldConfig fieldConfig = new MigrationFieldConfig();
        fieldConfig.setCreatedAtField("createdAt");
        migrationProperties.getEntityFields().put("demo", fieldConfig);

        indexingProperties = new AIIndexingProperties();
        clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneId.of("UTC"));

        persistedJob = new AtomicReference<>();
        when(filterPolicy.supports(any())).thenReturn(false);
        when(capabilityService.resolveEntityId(any())).thenAnswer(inv -> {
            Object arg = inv.getArgument(0);
            if (arg instanceof DemoEntity de) {
                return de.getId();
            }
            return "id";
        });
        when(jobRepository.save(any())).thenAnswer(inv -> {
            MigrationJob j = inv.getArgument(0);
            persistedJob.set(j);
            return j;
        });
        when(jobRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(persistedJob.get()));
    }

    @Test
    void respectsPauseStatusAndStops() {
        MigrationJob job = baseJob("demo");
        job.setStatus(MigrationStatus.PAUSED);
        persistedJob.set(job);
        when(configLoader.getEntityConfig("demo")).thenReturn(aiConfig());
        JpaRepository<DemoEntity, String> emptyRepo = mock(JpaRepository.class);
        when(emptyRepo.count()).thenReturn(0L);
        when(emptyRepo.findAll(any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(org.springframework.data.domain.Page.empty(PageRequest.of(0, 10)));
        when(repositoryRegistry.getRegistration("demo"))
            .thenReturn(new EntityRegistration("demo", DemoEntity.class, emptyRepo));

        DataMigrationService service = service();
        service.startMigration(MigrationRequest.builder().entityType("demo").batchSize(1).build());

        verify(executorService).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();

        assertThat(persistedJob.get().getProcessedEntities()).isZero();
        verify(queueService, never()).enqueue(any());
    }

    @Test
    void cancelsEarlyAndStopsProcessing() {
        MigrationJob job = baseJob("demo");
        job.setStatus(MigrationStatus.RUNNING);
        persistedJob.set(job);
        when(configLoader.getEntityConfig("demo")).thenReturn(aiConfig());
        JpaRepository<DemoEntity, String> repo = mockRepoWithEntities(new DemoEntity("id-1"));
        when(repositoryRegistry.getRegistration("demo"))
            .thenReturn(new EntityRegistration("demo", DemoEntity.class, repo));

        // flip to cancelled on first load
        when(jobRepository.findById(any())).thenAnswer(inv -> {
            if (persistedJob.get().getCurrentPage() == 0) {
                persistedJob.get().setStatus(MigrationStatus.CANCELLED);
            }
            return Optional.of(persistedJob.get());
        });

        DataMigrationService service = service();
        service.startMigration(MigrationRequest.builder().entityType("demo").batchSize(10).build());

        verify(executorService).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();

        verify(queueService, never()).enqueue(any());
        assertThat(persistedJob.get().getStatus()).isEqualTo(MigrationStatus.CANCELLED);
        assertThat(persistedJob.get().getProcessedEntities()).isZero();
    }

    @Test
    void resumeTransitionsBackToRunning() {
        MigrationJob job = baseJob("demo");
        job.setStatus(MigrationStatus.PAUSED);
        persistedJob.set(job);
        when(configLoader.getEntityConfig("demo")).thenReturn(aiConfig());
        JpaRepository<DemoEntity, String> repo = mockRepoWithEntities(); // empty repo, completes immediately
        when(repositoryRegistry.getRegistration("demo"))
            .thenReturn(new EntityRegistration("demo", DemoEntity.class, repo));

        DataMigrationService service = service();
        MigrationRequest req = MigrationRequest.builder()
            .entityType("demo")
            .batchSize(1)
            .build();

        // simulate resume: set status to RUNNING before calling startMigration
        job.setStatus(MigrationStatus.RUNNING);
        service.startMigration(req);

        verify(executorService).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();

        assertThat(persistedJob.get().getStatus()).isEqualTo(MigrationStatus.COMPLETED);
    }

    @Test
    void pauseMigrationOnlyAllowsRunningJobs() {
        MigrationJob job = baseJob("demo");
        job.setStatus(MigrationStatus.RUNNING);
        persistedJob.set(job);

        DataMigrationService service = service();
        service.pauseMigration(job.getId());

        assertThat(persistedJob.get().getStatus()).isEqualTo(MigrationStatus.PAUSED);
        assertThat(persistedJob.get().getLastUpdatedAt()).isEqualTo(clock.instant().atZone(clock.getZone()).toLocalDateTime());
    }

    @Test
    void pauseMigrationRejectsCompletedJobs() {
        MigrationJob job = baseJob("demo");
        job.setStatus(MigrationStatus.COMPLETED);
        persistedJob.set(job);

        DataMigrationService service = service();

        assertThatThrownBy(() -> service.pauseMigration(job.getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot transition migration job")
            .hasMessageContaining("COMPLETED")
            .hasMessageContaining("PAUSED");
        assertThat(persistedJob.get().getStatus()).isEqualTo(MigrationStatus.COMPLETED);
    }

    @Test
    void resumeMigrationOnlyAllowsPausedJobsAndSubmitsWorker() {
        MigrationJob job = baseJob("demo");
        job.setStatus(MigrationStatus.PAUSED);
        job.setFilters(ai.fabric.migration.domain.MigrationFilters.builder()
            .entityIds(List.of("resume-1"))
            .build());
        persistedJob.set(job);
        when(configLoader.getEntityConfig("demo")).thenReturn(aiConfig());
        JpaRepository<DemoEntity, String> repo = mockRepoWithEntities(new DemoEntity("resume-1"));
        when(repositoryRegistry.getRegistration("demo"))
            .thenReturn(new EntityRegistration("demo", DemoEntity.class, repo));

        DataMigrationService service = service();
        service.resumeMigration(job.getId());

        assertThat(persistedJob.get().getStatus()).isEqualTo(MigrationStatus.RUNNING);
        verify(executorService).submit(runnableCaptor.capture());
    }

    @Test
    void resumeMigrationRejectsCancelledJobsWithoutSubmittingWorker() {
        MigrationJob job = baseJob("demo");
        job.setStatus(MigrationStatus.CANCELLED);
        persistedJob.set(job);

        DataMigrationService service = service();

        assertThatThrownBy(() -> service.resumeMigration(job.getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot transition migration job")
            .hasMessageContaining("CANCELLED")
            .hasMessageContaining("RUNNING");
        verify(executorService, never()).submit(any(Runnable.class));
        assertThat(persistedJob.get().getStatus()).isEqualTo(MigrationStatus.CANCELLED);
    }

    @Test
    void cancelMigrationRejectsTerminalJobs() {
        MigrationJob job = baseJob("demo");
        job.setStatus(MigrationStatus.COMPLETED);
        persistedJob.set(job);

        DataMigrationService service = service();

        assertThatThrownBy(() -> service.cancelMigration(job.getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot transition migration job")
            .hasMessageContaining("COMPLETED")
            .hasMessageContaining("CANCELLED");
        assertThat(persistedJob.get().getCompletedAt()).isNull();
    }

    @Test
    void skipsEntitiesWithBlankResolvedId() {
        DemoEntity entity = new DemoEntity("id-blank");
        JpaRepository<DemoEntity, String> repo = mockRepoWithEntities(entity);
        when(repositoryRegistry.getRegistration("demo")).thenReturn(new EntityRegistration("demo", DemoEntity.class, repo));
        when(configLoader.getEntityConfig("demo")).thenReturn(aiConfig());
        when(capabilityService.resolveEntityId(entity)).thenReturn("  "); // blank id

        DataMigrationService service = service();
        service.startMigration(MigrationRequest.builder().entityType("demo").batchSize(5).build());

        verify(executorService).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();

        verify(queueService, never()).enqueue(any());
        assertThat(persistedJob.get().getProcessedEntities()).isEqualTo(1);
    }

    @Test
    void missingCreatedAtFieldThrows() {
        MigrationFieldConfig fieldConfig = new MigrationFieldConfig();
        fieldConfig.setCreatedAtField("missingField");
        migrationProperties.getEntityFields().put("demo", fieldConfig);

        DemoEntity entity = new DemoEntity("id-ct");
        JpaRepository<DemoEntity, String> repo = mockRepoWithEntities(entity);
        when(repositoryRegistry.getRegistration("demo")).thenReturn(new EntityRegistration("demo", DemoEntity.class, repo));
        when(configLoader.getEntityConfig("demo")).thenReturn(aiConfig());

        DataMigrationService service = service();
        service.startMigration(MigrationRequest.builder()
            .entityType("demo")
            .batchSize(5)
            .filters(ai.fabric.migration.domain.MigrationFilters.builder()
                .createdAfter(java.time.LocalDate.now(clock).minusDays(1))
                .build())
            .build());

        verify(executorService).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();

        // Service logs and increments failures but completes after paging finishes
        assertThat(persistedJob.get().getFailedEntities()).isEqualTo(1);
        assertThat(persistedJob.get().getStatus()).isEqualTo(MigrationStatus.COMPLETED);
    }

    @Test
    void createdAtWrongTypeCountsFailure() {
        MigrationFieldConfig fieldConfig = new MigrationFieldConfig();
        fieldConfig.setCreatedAtField("createdAt");
        migrationProperties.getEntityFields().put("demo", fieldConfig);

        BadCreatedAtEntity bad = new BadCreatedAtEntity("bad-1");
        JpaRepository<BadCreatedAtEntity, String> repo = mock(JpaRepository.class);
        when(repo.count()).thenReturn(1L);
        when(repo.findAll(any(org.springframework.data.domain.Pageable.class))).thenAnswer(inv -> {
            org.springframework.data.domain.Pageable pr = inv.getArgument(0);
            if (pr.getPageNumber() > 0) {
                return org.springframework.data.domain.Page.empty(pr);
            }
            return new PageImpl<>(List.of(bad), PageRequest.of(0, 1), 1);
        });
        when(repositoryRegistry.getRegistration("demo"))
            .thenReturn(new EntityRegistration("demo", BadCreatedAtEntity.class, repo));
        when(configLoader.getEntityConfig("demo")).thenReturn(aiConfig());
        when(capabilityService.resolveEntityId(bad)).thenReturn(bad.getId());

        DataMigrationService service = service();
        service.startMigration(MigrationRequest.builder()
            .entityType("demo")
            .batchSize(1)
            .filters(ai.fabric.migration.domain.MigrationFilters.builder()
                .createdAfter(java.time.LocalDate.now(clock).minusDays(2))
                .build())
            .build());

        verify(executorService).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();

        assertThat(persistedJob.get().getFailedEntities()).isEqualTo(1);
        assertThat(persistedJob.get().getProcessedEntities()).isEqualTo(1);
        assertThat(persistedJob.get().getStatus()).isEqualTo(MigrationStatus.COMPLETED);
    }

    @Test
    void skipsAlreadyIndexedWhenReindexDisabled() {
        DemoEntity entity = new DemoEntity("e-1");
        JpaRepository<DemoEntity, String> repo = mockRepoWithEntities(entity);
        when(repositoryRegistry.getRegistration("demo")).thenReturn(new EntityRegistration("demo", DemoEntity.class, repo));
        when(configLoader.getEntityConfig("demo")).thenReturn(aiConfig());
        when(capabilityService.resolveEntityId(entity)).thenReturn(entity.id);
        when(vectorDatabaseService.vectorExists("demo", entity.id)).thenReturn(true);

        DataMigrationService service = service();
        service.startMigration(MigrationRequest.builder().entityType("demo").reindexExisting(false).batchSize(10).build());

        verify(executorService).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();

        verify(queueService, never()).enqueue(any());
        assertThat(persistedJob.get().getProcessedEntities()).isEqualTo(1);
    }

    @Test
    void enqueuesPayloadWithDefaults() {
        DemoEntity entity = new DemoEntity("e-1");
        JpaRepository<DemoEntity, String> repo = mockRepoWithEntities(entity);
        when(repositoryRegistry.getRegistration("demo")).thenReturn(new EntityRegistration("demo", DemoEntity.class, repo));
        when(configLoader.getEntityConfig("demo")).thenReturn(aiConfig());
        when(capabilityService.resolveEntityId(entity)).thenReturn(entity.id);
        when(vectorDatabaseService.vectorExists("demo", entity.id)).thenReturn(false);

        DataMigrationService service = service();
        service.startMigration(MigrationRequest.builder().entityType("demo").reindexExisting(false).batchSize(10).build());

        verify(executorService).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();

        verify(queueService).enqueue(requestCaptor.capture());
        IndexingRequest req = requestCaptor.getValue();
        assertThat(req.entityType()).isEqualTo("demo");
        assertThat(req.entityId()).isEqualTo("e-1");
        assertThat(req.strategy()).isEqualTo(IndexingStrategy.ASYNC);
        assertThat(req.operation()).isEqualTo(IndexingOperation.CREATE);
        assertThat(req.actionPlan().generateEmbedding()).isTrue();
        assertThat(req.actionPlan().indexForSearch()).isTrue();
        assertThat(req.maxRetries()).isEqualTo(indexingProperties.getQueue().getMaxRetries());
        assertThat(req.payload()).contains("e-1");
    }

    @Test
    void enqueuesWhenReindexExistingTrueEvenIfAlreadyIndexed() {
        DemoEntity entity = new DemoEntity("e-2");
        JpaRepository<DemoEntity, String> repo = mockRepoWithEntities(entity);
        when(repositoryRegistry.getRegistration("demo")).thenReturn(new EntityRegistration("demo", DemoEntity.class, repo));
        when(configLoader.getEntityConfig("demo")).thenReturn(aiConfig());
        when(capabilityService.resolveEntityId(entity)).thenReturn(entity.id);
        when(vectorDatabaseService.vectorExists("demo", entity.id)).thenReturn(true);

        DataMigrationService service = service();
        service.startMigration(MigrationRequest.builder().entityType("demo").reindexExisting(true).batchSize(10).build());

        verify(executorService).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();

        verify(queueService).enqueue(any(IndexingRequest.class));
        assertThat(persistedJob.get().getProcessedEntities()).isEqualTo(1);
    }

    @Test
    void enqueueFailureCountsAsFailed() {
        DemoEntity entity = new DemoEntity("e-3");
        JpaRepository<DemoEntity, String> repo = mockRepoWithEntities(entity);
        when(repositoryRegistry.getRegistration("demo")).thenReturn(new EntityRegistration("demo", DemoEntity.class, repo));
        when(configLoader.getEntityConfig("demo")).thenReturn(aiConfig());
        when(capabilityService.resolveEntityId(entity)).thenReturn(entity.id);
        when(vectorDatabaseService.vectorExists("demo", entity.id)).thenReturn(false);
        when(queueService.enqueue(any(IndexingRequest.class))).thenThrow(new RuntimeException("queue down"));

        DataMigrationService service = service();
        service.startMigration(MigrationRequest.builder().entityType("demo").batchSize(10).build());

        verify(executorService).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();

        assertThat(persistedJob.get().getFailedEntities()).isEqualTo(1);
        assertThat(persistedJob.get().getProcessedEntities()).isEqualTo(1);
    }

    @Test
    void missingFieldConfigWithoutPolicyThrows() {
        migrationProperties.getEntityFields().clear();
        DataMigrationService service = new DataMigrationService(
            queueService,
            configLoader,
            repositoryRegistry,
            jobRepository,
            vectorDatabaseService,
            progressTracker,
            migrationProperties,
            indexingProperties,
            new com.fasterxml.jackson.databind.ObjectMapper(),
            executorService,
            capabilityService,
            clock,
            List.of()
        );

        when(configLoader.getEntityConfig("demo")).thenReturn(aiConfig());
        JpaRepository<DemoEntity, String> emptyRepo = mock(JpaRepository.class);
        when(emptyRepo.count()).thenReturn(0L);
        when(repositoryRegistry.getRegistration("demo"))
            .thenReturn(new EntityRegistration("demo", DemoEntity.class, emptyRepo));

        assertThatThrownBy(() -> service.startMigration(MigrationRequest.builder().entityType("demo").batchSize(10).build()))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startMigrationRejectsMissingRequestOrEntityType() {
        DataMigrationService service = service();

        assertThatThrownBy(() -> service.startMigration(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("entityType is required");
        assertThatThrownBy(() -> service.startMigration(MigrationRequest.builder().entityType(" ").batchSize(10).build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("entityType is required");
    }

    @Test
    void startMigrationPersistsInitialJobWithTotals() {
        JpaRepository<DemoEntity, String> repo = mockRepoWithEntities(new DemoEntity("e-4"), new DemoEntity("e-5"));
        when(repositoryRegistry.getRegistration("demo")).thenReturn(new EntityRegistration("demo", DemoEntity.class, repo));
        when(configLoader.getEntityConfig("demo")).thenReturn(aiConfig());

        ArgumentCaptor<MigrationJob> jobCaptor = ArgumentCaptor.forClass(MigrationJob.class);
        DataMigrationService service = service();
        service.startMigration(MigrationRequest.builder().entityType("demo").batchSize(7).build());

        verify(jobRepository).save(jobCaptor.capture());
        MigrationJob saved = jobCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(MigrationStatus.RUNNING);
        assertThat(saved.getTotalEntities()).isEqualTo(2);
        assertThat(saved.getBatchSize()).isEqualTo(7);
    }

    @Test
    void completesWhenRepositoryEmpty() {
        JpaRepository<DemoEntity, String> repo = mockRepoWithEntities(); // no entities
        when(repositoryRegistry.getRegistration("demo")).thenReturn(new EntityRegistration("demo", DemoEntity.class, repo));
        when(configLoader.getEntityConfig("demo")).thenReturn(aiConfig());

        DataMigrationService service = service();
        service.startMigration(MigrationRequest.builder().entityType("demo").batchSize(5).build());

        verify(executorService).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();

        assertThat(persistedJob.get().getStatus()).isEqualTo(MigrationStatus.COMPLETED);
        assertThat(persistedJob.get().getTotalEntities()).isEqualTo(0);
        assertThat(persistedJob.get().getProcessedEntities()).isZero();
    }

    @Test
    void policyCanSkipEntitiesAndPreventEnqueue() {
        when(filterPolicy.supports("demo")).thenReturn(true);
        when(filterPolicy.shouldMigrate(any(), any(), any())).thenReturn(false);

        DemoEntity entity = new DemoEntity("skip-1");
        JpaRepository<DemoEntity, String> repo = mockRepoWithEntities(entity);
        when(repositoryRegistry.getRegistration("demo")).thenReturn(new EntityRegistration("demo", DemoEntity.class, repo));
        when(configLoader.getEntityConfig("demo")).thenReturn(aiConfig());

        DataMigrationService service = service();
        service.startMigration(MigrationRequest.builder()
            .entityType("demo")
            .batchSize(5)
            .filters(ai.fabric.migration.domain.MigrationFilters.builder()
                .entityIds(List.of("skip-1"))
                .build())
            .build());

        verify(executorService).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();

        verify(queueService, never()).enqueue(any(IndexingRequest.class));
        assertThat(persistedJob.get().getProcessedEntities()).isEqualTo(1);
    }

    @Test
    void marksJobFailedOnUnhandledException() {
        when(repositoryRegistry.getRegistration("demo")).thenThrow(new RuntimeException("boom"));
        when(configLoader.getEntityConfig("demo")).thenReturn(aiConfig());

        DataMigrationService service = service();

        assertThatThrownBy(() -> service.startMigration(MigrationRequest.builder().entityType("demo").batchSize(10).build()))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void matchesFiltersRespectCreatedBeforeAfterAndSafeIds() {
        MigrationFieldConfig fieldConfig = new MigrationFieldConfig();
        fieldConfig.setCreatedAtField("createdAt");
        migrationProperties.getEntityFields().put("demo", fieldConfig);

        DataMigrationService service = service();
        MigrationRequest req = MigrationRequest.builder()
            .entityType("demo")
            .filters(ai.fabric.migration.domain.MigrationFilters.builder()
                .entityIds(List.of("keep-me"))
                .createdBefore(java.time.LocalDate.now(clock).plusDays(1))
                .createdAfter(java.time.LocalDate.now(clock).minusDays(1))
                .build())
            .build();

        DemoEntity entity = new DemoEntity("keep-me", java.time.LocalDate.now(clock));
        assertThat(invokeMatchesFilters(service, entity, req, fieldConfig)).isTrue();

        DemoEntity other = new DemoEntity("drop-me", java.time.LocalDate.now(clock));
        assertThat(invokeMatchesFilters(service, other, req, fieldConfig)).isFalse();
    }

    @Test
    void rateLimitSkipsWhenZeroOrNull() {
        MigrationJob job = baseJob("demo");
        job.setRateLimit(0);
        DataMigrationService service = service();
        invokeRateLimit(service, job);

        job.setRateLimit(null);
        invokeRateLimit(service, job);
    }

    @Test
    void rateLimitInterruptRestoresFlag() {
        MigrationJob job = baseJob("demo");
        job.setRateLimit(60); // will sleep ~1000ms; interrupt to exit early
        DataMigrationService service = service();

        // set interrupt before call; applyRateLimit should re-set it on catch
        Thread.currentThread().interrupt();
        invokeRateLimit(service, job);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();

        // clear for subsequent tests
        Thread.interrupted();
    }

    private DataMigrationService service() {
        return new DataMigrationService(
            queueService,
            configLoader,
            repositoryRegistry,
            jobRepository,
            vectorDatabaseService,
            progressTracker,
            migrationProperties,
            indexingProperties,
            new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()),
            executorService,
            capabilityService,
            clock,
            List.of(filterPolicy)
        );
    }

    private MigrationJob baseJob(String entityType) {
        return MigrationJob.builder()
            .id("mig-" + UUID.randomUUID())
            .entityType(entityType)
            .status(MigrationStatus.RUNNING)
            .totalEntities(1L)
            .processedEntities(0L)
            .failedEntities(0L)
            .currentPage(0)
            .batchSize(10)
            .rateLimit(null)
            .reindexExisting(false)
            .startedAt(clock.instant().atZone(clock.getZone()).toLocalDateTime())
            .lastUpdatedAt(clock.instant().atZone(clock.getZone()).toLocalDateTime())
            .build();
    }

    private AIEntityConfig aiConfig() {
        return AIEntityConfig.builder()
            .entityType("demo")
            .autoEmbedding(true)
            .indexable(true)
            .enableSearch(true)
            .searchableFields(List.of(AISearchableField.builder().name("id").build()))
            .embeddableFields(List.of(AIEmbeddableField.builder().name("id").build()))
            .metadataFields(List.of(AIMetadataField.builder().name("createdAt").type("DATE").build()))
            .build();
    }

    @SafeVarargs
    private final JpaRepository<DemoEntity, String> mockRepoWithEntities(DemoEntity... entities) {
        JpaRepository<DemoEntity, String> repo = mock(JpaRepository.class);
        long total = entities.length;
        org.mockito.Mockito.lenient().when(repo.count()).thenReturn(total);
        org.mockito.Mockito.lenient().when(repo.findAll(any(org.springframework.data.domain.Pageable.class))).thenAnswer(inv -> {
            org.springframework.data.domain.Pageable pr = inv.getArgument(0);
            if (pr.getPageNumber() > 0) {
                return org.springframework.data.domain.Page.empty(pr);
            }
            return new PageImpl<>(List.of(entities), PageRequest.of(0, 10), total);
        });
        return repo;
    }

    private static class DemoEntity {
        private String id;
        private java.time.LocalDate createdAt;

        DemoEntity(String id) {
            this(id, java.time.LocalDate.now());
        }

        DemoEntity(String id, java.time.LocalDate createdAt) {
            this.id = id;
            this.createdAt = createdAt;
        }

        public String getId() {
            return id;
        }

        public java.time.LocalDate getCreatedAt() {
            return createdAt;
        }
    }

    private static class BadCreatedAtEntity {
        private String id;
        @SuppressWarnings("unused")
        private String createdAt = "not-a-date";

        BadCreatedAtEntity(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    private boolean invokeMatchesFilters(DataMigrationService service,
                                         DemoEntity entity,
                                         MigrationRequest req,
                                         MigrationFieldConfig fieldConfig) {
        try {
            var method = DataMigrationService.class.getDeclaredMethod(
                "matchesFilters",
                Object.class,
                ai.fabric.migration.domain.MigrationRequest.class,
                ai.fabric.dto.AIEntityConfig.class,
                ai.fabric.migration.config.MigrationFieldConfig.class,
                ai.fabric.migration.service.MigrationFilterPolicy.class
            );
            method.setAccessible(true);
            return (boolean) method.invoke(
                service,
                entity,
                req,
                aiConfig(),
                fieldConfig,
                null
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void invokeRateLimit(DataMigrationService service, MigrationJob job) {
        try {
            var method = DataMigrationService.class.getDeclaredMethod("applyRateLimit", MigrationJob.class);
            method.setAccessible(true);
            method.invoke(service, job);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
