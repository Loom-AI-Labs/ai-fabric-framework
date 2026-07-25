package ai.fabric.indexing.transaction;

import ai.fabric.config.AIIndexingProperties;
import ai.fabric.entity.IndexingEntityState;
import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.DefaultAIEntityIndexingGateway;
import ai.fabric.indexing.IndexingStatus;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.indexing.model.AIIndexDocument;
import ai.fabric.indexing.projection.AIEntityProjectionService;
import ai.fabric.indexing.queue.IndexingQueueService;
import ai.fabric.indexing.worker.IndexingExecutionException;
import ai.fabric.indexing.worker.IndexingOperationExecutor;
import ai.fabric.indexing.worker.IndexingWorkProcessor;
import ai.fabric.repository.IndexingEntityStateRepository;
import ai.fabric.repository.IndexingQueueRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IndexingTransactionIntegrationTest.TestConfiguration.class)
class IndexingTransactionIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private IndexingQueueRepository queueRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private IndexingEntityStateRepository stateRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private DefaultAIEntityIndexingGateway gateway;

    @org.springframework.beans.factory.annotation.Autowired
    private IndexingQueueService queueService;

    @org.springframework.beans.factory.annotation.Autowired
    private IndexingOperationExecutor operationExecutor;

    @org.springframework.beans.factory.annotation.Autowired
    private IndexingWorkProcessor workProcessor;

    @org.springframework.beans.factory.annotation.Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private final Map<String, String> vectors = new ConcurrentHashMap<>();

    @BeforeEach
    void clearState() {
        queueRepository.deleteAll();
        stateRepository.deleteAll();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            entityManager.createQuery("DELETE FROM IndexingTransactionSource")
                .executeUpdate()
        );
        reset(operationExecutor);
        vectors.clear();
        doAnswer(invocation -> executeAgainstVectorState(
            invocation.getArgument(0)
        )).when(operationExecutor).execute(any(AIIndexDocument.class), anyLong());
    }

    @Test
    void syncWorkExecutesOnlyAfterTheSourceTransactionCommits() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            persist(new TestSource("p-1", "Laptop"));
            gateway.submit(document("p-1"), IndexingStrategy.SYNC);

            assertThat(queueRepository.count()).isEqualTo(1);
            verifyNoInteractions(operationExecutor);
        });

        assertThat(sourceExists("p-1")).isTrue();
        IndexingQueueEntry work = queueRepository.findAll().getFirst();
        assertThat(work.getStatus()).isEqualTo(IndexingStatus.COMPLETED);
        assertThat(vectors).containsEntry("product:p-1", "title: Product");
        verify(operationExecutor).execute(any(AIIndexDocument.class), anyLong());
    }

    @Test
    void syncRetryWorkerCannotStealCommitOwnedWork() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            gateway.submit(document("commit-owned"), IndexingStrategy.SYNC);

            IndexingQueueEntry work = queueRepository.findAll().getFirst();
            assertThat(work.getStatus()).isEqualTo(
                IndexingStatus.COMMIT_PENDING
            );
            assertThat(queueService.lease(IndexingStrategy.SYNC, 10)).isEmpty();
            verifyNoInteractions(operationExecutor);
        });

        IndexingQueueEntry completed = queueRepository.findAll().getFirst();
        assertThat(completed.getStatus()).isEqualTo(IndexingStatus.COMPLETED);
        assertThat(vectors)
            .containsEntry("product:commit-owned", "title: Product");
    }

    @Test
    void newerSynchronousWorkWaitsForAnOlderRetryableEntry() {
        IndexingQueueEntry older = queueService.enqueueForSynchronousDispatch(
            upsertDocument("sync-ordered", "Older", 1L)
        );
        IndexingQueueEntry newer = queueService.enqueueForSynchronousDispatch(
            upsertDocument("sync-ordered", "Newer", 2L)
        );

        assertThat(queueService.claimSynchronous(older.getId())).isPresent();
        queueService.markFailure(older.getId(), "VECTOR_PROVIDER_UNAVAILABLE");

        assertThat(queueService.claimSynchronous(newer.getId())).isEmpty();

        queueService.markCompleted(older.getId(), null);
        assertThat(queueService.claimSynchronous(newer.getId())).isPresent();
    }

    @Test
    void rollbackRemovesSourceQueueAndOrderingStateWithoutProviderWork() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            persist(new TestSource("p-2", "Tablet"));
            gateway.submit(document("p-2"), IndexingStrategy.SYNC);
            status.setRollbackOnly();
        });

        assertThat(sourceExists("p-2")).isFalse();
        assertThat(queueRepository.count()).isZero();
        assertThat(stateRepository.count()).isZero();
        verifyNoInteractions(operationExecutor);
    }

    @Test
    void updateRollbackKeepsThePreviousSourceAndVector() {
        inTransaction(() -> persist(new TestSource("p-update", "Original")));
        vectors.put("product:p-update", "title: Original");

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            TestSource source = entityManager.find(TestSource.class, "p-update");
            source.rename("Changed");
            gateway.submit(
                upsertDocument("p-update", "Changed", 2L),
                IndexingStrategy.SYNC
            );
            status.setRollbackOnly();
        });

        assertThat(sourceName("p-update")).isEqualTo("Original");
        assertThat(vectors).containsEntry("product:p-update", "title: Original");
        assertThat(queueRepository.count()).isZero();
        verifyNoInteractions(operationExecutor);
    }

    @Test
    void deleteRollbackKeepsTheSourceAndVector() {
        inTransaction(() -> persist(new TestSource("p-delete", "Keep me")));
        vectors.put("product:p-delete", "title: Keep me");

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            entityManager.remove(entityManager.find(TestSource.class, "p-delete"));
            entityManager.flush();
            gateway.submit(deleteDocument("p-delete"), IndexingStrategy.SYNC);
            status.setRollbackOnly();
        });

        assertThat(sourceName("p-delete")).isEqualTo("Keep me");
        assertThat(vectors).containsEntry("product:p-delete", "title: Keep me");
        assertThat(queueRepository.count()).isZero();
        verifyNoInteractions(operationExecutor);
    }

    @Test
    void enqueueFailureRollsBackTheSourceTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        String oversizedId = "x".repeat(600);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            persist(new TestSource("p-3", "Phone"));
            gateway.submit(document(oversizedId), IndexingStrategy.ASYNC);
        })).isInstanceOf(RuntimeException.class);

        assertThat(sourceExists("p-3")).isFalse();
        assertThat(queueRepository.count()).isZero();
        assertThat(stateRepository.count()).isZero();
        verifyNoInteractions(operationExecutor);
    }

    @Test
    void nonTransactionalSubmissionCommitsItsOwnDurableQueueRow() {
        gateway.submit(document("p-4"), IndexingStrategy.ASYNC);

        IndexingQueueEntry work = queueRepository.findAll().getFirst();
        assertThat(work.getStatus()).isEqualTo(IndexingStatus.PENDING);
        assertThat(stateRepository.count()).isZero();
        verifyNoInteractions(operationExecutor);
    }

    @Test
    void concurrentFirstSubmissionsDoNotCompeteForOrderingState() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                queueService.enqueue(
                    upsertDocument("concurrent", "Version one", 1L),
                    IndexingStrategy.ASYNC
                );
                return null;
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                queueService.enqueue(
                    upsertDocument("concurrent", "Version two", 2L),
                    IndexingStrategy.ASYNC
                );
                return null;
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(queueRepository.count()).isEqualTo(2);
        assertThat(stateRepository.count()).isZero();

        processNextAsyncWork();
        processNextAsyncWork();

        assertThat(stateRepository.count()).isEqualTo(1);
        assertThat(vectors)
            .containsEntry("product:concurrent", "title: Version two");
        assertThat(queueRepository.findAll())
            .extracting(IndexingQueueEntry::getStatus)
            .allMatch(status ->
                status == IndexingStatus.COMPLETED
                    || status == IndexingStatus.SUPERSEDED
            );
    }

    @Test
    void syncProviderFailureKeepsSourceAndRetryableWork() {
        doThrow(new IndexingExecutionException("VECTOR_PROVIDER_UNAVAILABLE"))
            .when(operationExecutor)
            .execute(any(AIIndexDocument.class), anyLong());
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            persist(new TestSource("p-5", "Monitor"));
            gateway.submit(document("p-5"), IndexingStrategy.SYNC);
        });

        assertThat(sourceExists("p-5")).isTrue();
        IndexingQueueEntry work = queueRepository.findAll().getFirst();
        assertThat(work.getStatus()).isEqualTo(IndexingStatus.PENDING);
        assertThat(work.getRetryCount()).isEqualTo(1);
        assertThat(work.getErrorCode()).isEqualTo("VECTOR_PROVIDER_UNAVAILABLE");
    }

    @Test
    void transientProviderFailureEventuallyCompletesTheSameDurableWork() {
        doThrow(new IndexingExecutionException("VECTOR_PROVIDER_UNAVAILABLE"))
            .doAnswer(invocation -> executeAgainstVectorState(
                invocation.getArgument(0)
            ))
            .when(operationExecutor)
            .execute(any(AIIndexDocument.class), anyLong());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            persist(new TestSource("p-retry", "Recovered"));
            gateway.submit(
                upsertDocument("p-retry", "Recovered", 1L),
                IndexingStrategy.SYNC
            );
        });
        IndexingQueueEntry retryable = queueRepository.findAll().getFirst();
        assertThat(retryable.getStatus()).isEqualTo(IndexingStatus.PENDING);

        IndexingWorkProcessor.WorkResult result = workProcessor.process(retryable);
        queueService.markCompleted(retryable.getId(), result.resultPayload());

        IndexingQueueEntry completed = queueRepository.findById(
            retryable.getId()
        ).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(IndexingStatus.COMPLETED);
        assertThat(completed.getRetryCount()).isEqualTo(1);
        assertThat(completed.getErrorCode()).isNull();
        assertThat(vectors).containsEntry("product:p-retry", "title: Recovered");
        verify(operationExecutor, org.mockito.Mockito.times(2))
            .execute(any(AIIndexDocument.class), anyLong());
    }

    @Test
    void analysisFailureDoesNotRetryOrInvalidateTheCompletedUpsert() {
        IndexingQueueEntry upsert = queueService.enqueue(
            upsertDocument("p-analysis", "Indexed", 1L),
            IndexingStrategy.ASYNC
        );
        IndexingQueueEntry analysis = queueService.enqueue(
            analysisDocument("p-analysis", "Indexed", 1L),
            IndexingStrategy.ASYNC,
            upsert.getId()
        );

        IndexingWorkProcessor.WorkResult upsertResult = workProcessor.process(upsert);
        queueService.markCompleted(upsert.getId(), upsertResult.resultPayload());
        doAnswer(invocation -> {
            AIIndexDocument document = invocation.getArgument(0);
            if (document.workType() == AIIndexWorkType.ANALYZE) {
                throw new IndexingExecutionException("ANALYSIS_PROVIDER_FAILED");
            }
            return executeAgainstVectorState(document);
        }).when(operationExecutor).execute(any(AIIndexDocument.class), anyLong());

        assertThatThrownBy(() -> workProcessor.process(analysis))
            .isInstanceOf(IndexingExecutionException.class);
        queueService.markFailure(analysis.getId(), "ANALYSIS_PROVIDER_FAILED");

        IndexingQueueEntry persistedUpsert = queueRepository.findById(
            upsert.getId()
        ).orElseThrow();
        IndexingQueueEntry persistedAnalysis = queueRepository.findById(
            analysis.getId()
        ).orElseThrow();
        assertThat(persistedUpsert.getStatus()).isEqualTo(IndexingStatus.COMPLETED);
        assertThat(persistedUpsert.getRetryCount()).isZero();
        assertThat(persistedAnalysis.getStatus()).isEqualTo(IndexingStatus.PENDING);
        assertThat(persistedAnalysis.getRetryCount()).isEqualTo(1);
        assertThat(vectors).containsEntry("product:p-analysis", "title: Indexed");
        verify(operationExecutor, org.mockito.Mockito.times(1)).execute(
            org.mockito.ArgumentMatchers.argThat(
                document -> document.workType() == AIIndexWorkType.UPSERT
            ),
            anyLong()
        );
    }

    @Test
    void leaseSerializesWorkPerEntityWhileAllowingOtherEntitiesToProgress() {
        IndexingQueueEntry first = queueService.enqueue(
            upsertDocument("ordered", "Version one", 1L),
            IndexingStrategy.ASYNC
        );
        IndexingQueueEntry second = queueService.enqueue(
            upsertDocument("ordered", "Version two", 2L),
            IndexingStrategy.ASYNC
        );
        IndexingQueueEntry independent = queueService.enqueue(
            upsertDocument("independent", "Other entity", 1L),
            IndexingStrategy.ASYNC
        );

        var firstLease = queueService.lease(IndexingStrategy.ASYNC, 10);

        assertThat(firstLease)
            .extracting(IndexingQueueEntry::getId)
            .containsExactlyInAnyOrder(first.getId(), independent.getId())
            .doesNotContain(second.getId());

        queueService.markCompleted(first.getId(), null);
        queueService.markCompleted(independent.getId(), null);
        var secondLease = queueService.lease(IndexingStrategy.ASYNC, 10);

        assertThat(secondLease)
            .extracting(IndexingQueueEntry::getId)
            .containsExactly(second.getId());
    }

    @Test
    void permanentFailureMovesDurableWorkToDeadLetter() {
        doThrow(new IndexingExecutionException("VECTOR_PROVIDER_UNAVAILABLE"))
            .when(operationExecutor)
            .execute(any(AIIndexDocument.class), anyLong());
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status ->
            gateway.submit(document("p-6"), IndexingStrategy.SYNC)
        );
        IndexingQueueEntry work = queueRepository.findAll().getFirst();

        queueService.markFailure(work.getId(), "VECTOR_PROVIDER_UNAVAILABLE");

        IndexingQueueEntry deadLetter = queueRepository.findById(work.getId()).orElseThrow();
        assertThat(deadLetter.getStatus()).isEqualTo(IndexingStatus.DEAD_LETTER);
        assertThat(deadLetter.getRetryCount()).isEqualTo(2);
        assertThat(deadLetter.getDeadLetterReason())
            .isEqualTo("VECTOR_PROVIDER_UNAVAILABLE");
    }

    @Test
    void expiredLeaseHonorsStuckThresholdAndEventuallyDeadLetters() {
        IndexingQueueEntry submitted = queueService.enqueue(
            upsertDocument("stuck", "Stuck worker", 1L),
            IndexingStrategy.ASYNC
        );
        assertThat(queueService.claimSynchronous(submitted.getId())).isPresent();

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        inTransaction(() -> {
            IndexingQueueEntry managed = entityManager.find(
                IndexingQueueEntry.class,
                submitted.getId()
            );
            managed.setStartedAt(now.minusMinutes(1));
            managed.setVisibilityTimeoutUntil(now.minusSeconds(1));
        });
        assertThat(queueService.resetStuckEntries()).isZero();
        assertThat(queueRepository.findById(submitted.getId()).orElseThrow().getStatus())
            .isEqualTo(IndexingStatus.PROCESSING);

        makeLeaseStuck(submitted.getId(), now.minusMinutes(11));
        assertThat(queueService.resetStuckEntries()).isOne();
        IndexingQueueEntry retry = queueRepository.findById(
            submitted.getId()
        ).orElseThrow();
        assertThat(retry.getStatus()).isEqualTo(IndexingStatus.PENDING);
        assertThat(retry.getRetryCount()).isEqualTo(1);
        assertThat(retry.getErrorCode()).isEqualTo("WORKER_VISIBILITY_TIMEOUT");

        assertThat(queueService.claimSynchronous(submitted.getId())).isPresent();
        makeLeaseStuck(submitted.getId(), now.minusMinutes(12));
        assertThat(queueService.resetStuckEntries()).isOne();
        IndexingQueueEntry deadLetter = queueRepository.findById(
            submitted.getId()
        ).orElseThrow();
        assertThat(deadLetter.getStatus()).isEqualTo(IndexingStatus.DEAD_LETTER);
        assertThat(deadLetter.getRetryCount()).isEqualTo(2);
        assertThat(deadLetter.getDeadLetterReason())
            .isEqualTo("WORKER_VISIBILITY_TIMEOUT");
    }

    private AIIndexDocument document(String entityId) {
        return upsertDocument(entityId, "Product", 1L);
    }

    private AIIndexDocument upsertDocument(
        String entityId,
        String title,
        Long sourceVersion
    ) {
        return new AIIndexDocument(
            AIIndexDocument.CURRENT_SCHEMA_VERSION,
            "a".repeat(64),
            "product",
            entityId,
            AIIndexWorkType.UPSERT,
            AIProcessOperation.UPDATE,
            "title: " + title,
            "title: " + title,
            Map.of("workspaceId", "workspace-a"),
            Map.of(),
            Map.of(),
            sourceVersion,
            "transaction-test",
            Instant.parse("2026-07-24T12:00:00Z")
        );
    }

    private AIIndexDocument deleteDocument(String entityId) {
        return new AIIndexDocument(
            AIIndexDocument.CURRENT_SCHEMA_VERSION,
            "a".repeat(64),
            "product",
            entityId,
            AIIndexWorkType.DELETE,
            AIProcessOperation.DELETE,
            null,
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            null,
            "transaction-test",
            Instant.parse("2026-07-24T12:00:00Z")
        );
    }

    private AIIndexDocument analysisDocument(
        String entityId,
        String title,
        Long sourceVersion
    ) {
        return new AIIndexDocument(
            AIIndexDocument.CURRENT_SCHEMA_VERSION,
            "a".repeat(64),
            "product",
            entityId,
            AIIndexWorkType.ANALYZE,
            AIProcessOperation.UPDATE,
            "title: " + title,
            "title: " + title,
            Map.of("workspaceId", "workspace-a"),
            Map.of(),
            Map.of(),
            sourceVersion,
            "transaction-test",
            Instant.parse("2026-07-24T12:00:00Z")
        );
    }

    private String executeAgainstVectorState(AIIndexDocument document) {
        String key = document.entityType() + ":" + document.entityId();
        if (document.workType() == AIIndexWorkType.UPSERT) {
            vectors.put(key, document.semanticSearchText());
        } else if (document.workType() == AIIndexWorkType.DELETE) {
            vectors.remove(key);
        }
        return document.workType() == AIIndexWorkType.ANALYZE
            ? "{\"analysis\":\"ok\"}"
            : null;
    }

    private void persist(TestSource source) {
        entityManager.persist(source);
        entityManager.flush();
    }

    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            action.run()
        );
    }

    private void makeLeaseStuck(long workId, LocalDateTime startedAt) {
        inTransaction(() -> {
            IndexingQueueEntry managed = entityManager.find(
                IndexingQueueEntry.class,
                workId
            );
            managed.setStartedAt(startedAt);
            managed.setVisibilityTimeoutUntil(
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)
            );
        });
    }

    private void processNextAsyncWork() {
        IndexingQueueEntry entry = queueService.lease(
            IndexingStrategy.ASYNC,
            1
        ).getFirst();
        IndexingWorkProcessor.WorkResult result = workProcessor.process(entry);
        if (result.status()
            == ai.fabric.indexing.api.IndexingDispatchStatus.SKIPPED_STALE) {
            queueService.markSuperseded(entry.getId());
        } else {
            queueService.markCompleted(entry.getId(), result.resultPayload());
        }
    }

    private boolean sourceExists(String id) {
        Boolean exists = new TransactionTemplate(transactionManager).execute(status ->
            entityManager.find(TestSource.class, id) != null
        );
        return Boolean.TRUE.equals(exists);
    }

    private String sourceName(String id) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            TestSource source = entityManager.find(TestSource.class, id);
            return source == null ? null : source.name();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(
        basePackageClasses = IndexingQueueRepository.class
    )
    static class TestConfiguration {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                "jdbc:h2:mem:indexing-transaction;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
            );
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(
            DataSource dataSource
        ) {
            LocalContainerEntityManagerFactoryBean factory =
                new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(
                IndexingQueueEntry.class.getPackageName(),
                TestSource.class.getPackageName()
            );
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "create-drop",
                "hibernate.show_sql", "false"
            ));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(
            EntityManagerFactory entityManagerFactory
        ) {
            return new JpaTransactionManager(entityManagerFactory);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        AIIndexingProperties indexingProperties() {
            AIIndexingProperties properties = new AIIndexingProperties();
            properties.getQueue().setMaxRetries(2);
            return properties;
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        IndexingQueueService indexingQueueService(
            IndexingQueueRepository queueRepository,
            AIIndexingProperties properties,
            ObjectMapper objectMapper,
            Clock clock
        ) {
            return new IndexingQueueService(
                queueRepository,
                properties,
                objectMapper,
                clock
            );
        }

        @Bean
        IndexingOperationExecutor indexingOperationExecutor() {
            return mock(IndexingOperationExecutor.class);
        }

        @Bean
        IndexingWorkProcessor indexingWorkProcessor(
            IndexingQueueService queueService,
            IndexingEntityStateRepository stateRepository,
            IndexingOperationExecutor operationExecutor,
            Clock clock
        ) {
            return new IndexingWorkProcessor(
                queueService,
                stateRepository,
                operationExecutor,
                clock
            );
        }

        @Bean
        AIEntityProjectionService projectionService() {
            return mock(AIEntityProjectionService.class);
        }

        @Bean
        AIEntityDescriptorRegistry descriptorRegistry() {
            return mock(AIEntityDescriptorRegistry.class);
        }

        @Bean
        DefaultAIEntityIndexingGateway gateway(
            AIEntityProjectionService projectionService,
            AIEntityDescriptorRegistry descriptorRegistry,
            IndexingQueueService queueService,
            IndexingWorkProcessor workProcessor
        ) {
            return new DefaultAIEntityIndexingGateway(
                projectionService,
                descriptorRegistry,
                queueService,
                workProcessor
            );
        }
    }

    @Entity(name = "IndexingTransactionSource")
    @Table(name = "indexing_transaction_source")
    public static class TestSource {
        @Id
        @Column(name = "id", length = 128)
        private String id;

        @Column(name = "name", nullable = false)
        private String name;

        protected TestSource() {
        }

        TestSource(String id, String name) {
            this.id = id;
            this.name = name;
        }

        void rename(String name) {
            this.name = name;
        }

        String name() {
            return name;
        }
    }
}
