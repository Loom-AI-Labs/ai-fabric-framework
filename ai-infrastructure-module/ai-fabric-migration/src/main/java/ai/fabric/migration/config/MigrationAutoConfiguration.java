package ai.fabric.migration.config;

import ai.fabric.config.AIInfrastructureAutoConfiguration;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.indexing.config.AIIndexingAutoConfiguration;
import ai.fabric.indexing.api.AIEntityIndexingGateway;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.indexing.queue.IndexingQueueService;
import ai.fabric.migration.repository.MigrationJobRepository;
import ai.fabric.migration.service.DataMigrationService;
import ai.fabric.migration.service.EntityRepositoryRegistry;
import ai.fabric.migration.service.MigrationFilterPolicy;
import ai.fabric.migration.service.MigrationProgressTracker;
import ai.fabric.rag.VectorDatabaseService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.repository.support.Repositories;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

@AutoConfiguration
@AutoConfigurationPackage(basePackages = "ai.fabric.migration")
@AutoConfigureAfter({AIInfrastructureAutoConfiguration.class, AIIndexingAutoConfiguration.class})
@ConditionalOnProperty(prefix = "ai.migration", name = "enabled", havingValue = "true")
@ConditionalOnBean(IndexingQueueService.class)
@EnableConfigurationProperties(MigrationProperties.class)
@Import({EntityRepositoryRegistry.class, MigrationProgressTracker.class})
public class MigrationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(Repositories.class)
    public Repositories migrationRepositories(ApplicationContext applicationContext) {
        // Ensures migration components can resolve Spring Data repositories even when
        // RepositoriesAutoConfiguration is not imported (e.g., in slim test contexts).
        return new Repositories(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public Clock migrationClock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "migrationExecutorService")
    public ExecutorService migrationExecutorService(MigrationProperties properties) {
        int poolSize = Math.max(1, properties.getMaxConcurrentJobs());
        AtomicInteger counter = new AtomicInteger(0);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("migration-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(poolSize, factory);
    }

    @Bean
    @ConditionalOnMissingBean
    public DataMigrationService dataMigrationService(
        AIEntityConfigurationLoader configLoader,
        AIEntityDescriptorRegistry descriptorRegistry,
        AIEntityIndexingGateway indexingGateway,
        EntityRepositoryRegistry repositoryRegistry,
        MigrationJobRepository jobRepository,
        VectorDatabaseService vectorDatabaseService,
        MigrationProgressTracker progressTracker,
        MigrationProperties migrationProperties,
        ExecutorService migrationExecutorService,
        Clock migrationClock,
        List<MigrationFilterPolicy> migrationFilterPolicies
    ) {
        return new DataMigrationService(
            configLoader,
            descriptorRegistry,
            indexingGateway,
            repositoryRegistry,
            jobRepository,
            vectorDatabaseService,
            progressTracker,
            migrationProperties,
            migrationExecutorService,
            migrationClock,
            migrationFilterPolicies
        );
    }
}
