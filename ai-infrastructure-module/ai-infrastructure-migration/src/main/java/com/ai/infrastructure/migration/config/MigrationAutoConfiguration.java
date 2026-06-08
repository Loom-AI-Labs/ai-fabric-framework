package com.ai.infrastructure.migration.config;

import com.ai.infrastructure.config.AIInfrastructureAutoConfiguration;
import com.ai.infrastructure.config.AIEntityConfigurationLoader;
import com.ai.infrastructure.config.AIIndexingProperties;
import com.ai.infrastructure.indexing.config.AIIndexingAutoConfiguration;
import com.ai.infrastructure.indexing.queue.IndexingQueueService;
import com.ai.infrastructure.migration.repository.MigrationJobRepository;
import com.ai.infrastructure.migration.service.DataMigrationService;
import com.ai.infrastructure.migration.service.EntityRepositoryRegistry;
import com.ai.infrastructure.migration.service.MigrationFilterPolicy;
import com.ai.infrastructure.migration.service.MigrationProgressTracker;
import com.ai.infrastructure.rag.VectorDatabaseService;
import com.ai.infrastructure.service.AICapabilityService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@AutoConfigurationPackage(basePackages = "com.ai.infrastructure.migration")
@AutoConfigureAfter({AIInfrastructureAutoConfiguration.class, AIIndexingAutoConfiguration.class})
@ConditionalOnProperty(prefix = "ai.migration", name = "enabled", havingValue = "true")
@ConditionalOnBean(IndexingQueueService.class)
@EnableConfigurationProperties({MigrationProperties.class, AIIndexingProperties.class})
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
        IndexingQueueService queueService,
        AIEntityConfigurationLoader configLoader,
        EntityRepositoryRegistry repositoryRegistry,
        MigrationJobRepository jobRepository,
        VectorDatabaseService vectorDatabaseService,
        MigrationProgressTracker progressTracker,
        MigrationProperties migrationProperties,
        AIIndexingProperties indexingProperties,
        ObjectMapper objectMapper,
        ExecutorService migrationExecutorService,
        AICapabilityService capabilityService,
        Clock migrationClock,
        List<MigrationFilterPolicy> migrationFilterPolicies
    ) {
        return new DataMigrationService(
            queueService,
            configLoader,
            repositoryRegistry,
            jobRepository,
            vectorDatabaseService,
            progressTracker,
            migrationProperties,
            indexingProperties,
            objectMapper,
            migrationExecutorService,
            capabilityService,
            migrationClock,
            migrationFilterPolicies
        );
    }
}
