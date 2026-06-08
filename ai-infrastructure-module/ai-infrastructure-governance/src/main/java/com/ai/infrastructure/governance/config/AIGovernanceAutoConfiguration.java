package com.ai.infrastructure.governance.config;

import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.compliance.AIComplianceService;
import com.ai.infrastructure.compliance.policy.ComplianceCheckProvider;
import com.ai.infrastructure.deletion.UserDataDeletionService;
import com.ai.infrastructure.deletion.policy.UserDataDeletionProvider;
import com.ai.infrastructure.deletion.port.BehaviorDeletionPort;
import com.ai.infrastructure.filter.AIContentFilterService;
import com.ai.infrastructure.governance.catalog.IndexCatalog;
import com.ai.infrastructure.governance.catalog.noop.NoopIndexCatalog;
import com.ai.infrastructure.governance.catalog.jpa.IndexCatalogRepository;
import com.ai.infrastructure.governance.catalog.jpa.JpaIndexCatalog;
import com.ai.infrastructure.governance.catalog.vector.VectorIndexCatalog;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineStep;
import com.ai.infrastructure.intent.orchestration.pipeline.steps.ComplianceCheckStep;
import com.ai.infrastructure.privacy.AIDataPrivacyService;
import com.ai.infrastructure.governance.vector.GovernanceVectorDatabaseServiceDecorator;
import com.ai.infrastructure.privacy.pii.PIIDetectionService;
import com.ai.infrastructure.rag.VectorDatabaseService;
import com.ai.infrastructure.retention.RetentionCleanupScheduler;
import com.ai.infrastructure.retention.policy.RetentionPolicyProvider;
import com.ai.infrastructure.prompt.PromptRenderer;
import com.ai.infrastructure.prompt.PromptTemplateResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.time.Clock;

@AutoConfiguration
@AutoConfigurationPackage(basePackages = "com.ai.infrastructure.governance")
@EnableConfigurationProperties(AIGovernanceProperties.class)
@EnableScheduling
@ConditionalOnProperty(prefix = "ai.governance", name = "enabled", havingValue = "true")
public class AIGovernanceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper governanceObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean(IndexCatalog.class)
    @ConditionalOnBean(VectorDatabaseService.class)
    public IndexCatalog indexCatalog(
        AIGovernanceProperties properties,
        VectorDatabaseService vectorDatabaseService,
        ObjectMapper governanceObjectMapper,
        org.springframework.beans.factory.ObjectProvider<IndexCatalogRepository> repositoryProvider
    ) {
        AIGovernanceProperties.CatalogProperties.Mode mode = properties.getCatalog() != null
            ? properties.getCatalog().getMode()
            : AIGovernanceProperties.CatalogProperties.Mode.AUTO;

        boolean vectorCapable = vectorDatabaseService.supportsVectorScan() && vectorDatabaseService.supportsMetadataFiltering();
        boolean sqlCapable = repositoryProvider.getIfAvailable() != null;

        AIGovernanceProperties.CatalogProperties.Mode resolved = switch (mode) {
            case VECTOR -> AIGovernanceProperties.CatalogProperties.Mode.VECTOR;
            case SQL -> AIGovernanceProperties.CatalogProperties.Mode.SQL;
            case DISABLED -> AIGovernanceProperties.CatalogProperties.Mode.DISABLED;
            case AUTO -> vectorCapable
                ? AIGovernanceProperties.CatalogProperties.Mode.VECTOR
                : (sqlCapable ? AIGovernanceProperties.CatalogProperties.Mode.SQL : AIGovernanceProperties.CatalogProperties.Mode.DISABLED);
        };

        if (resolved == AIGovernanceProperties.CatalogProperties.Mode.VECTOR) {
            return new VectorIndexCatalog(vectorDatabaseService);
        }

        if (resolved == AIGovernanceProperties.CatalogProperties.Mode.SQL) {
            IndexCatalogRepository repository = repositoryProvider.getIfAvailable();
            if (repository == null) {
                throw new IllegalStateException("ai.governance.catalog.mode=SQL requires IndexCatalogRepository (JPA) to be available");
            }
            return new JpaIndexCatalog(repository, governanceObjectMapper);
        }

        return new NoopIndexCatalog();
    }

    @Bean
    public BeanPostProcessor governanceVectorDatabaseServiceDecorator(
        AIGovernanceProperties properties,
        org.springframework.beans.factory.ObjectProvider<IndexCatalog> catalogProvider
    ) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!properties.isEnabled()) {
                    return bean;
                }
                if (!(bean instanceof VectorDatabaseService vectorDatabaseService)) {
                    return bean;
                }
                if (bean instanceof GovernanceVectorDatabaseServiceDecorator) {
                    return bean;
                }
                return new GovernanceVectorDatabaseServiceDecorator(vectorDatabaseService, catalogProvider);
            }
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.governance.deletion", name = "enabled", havingValue = "true")
    @ConditionalOnBean({VectorDatabaseService.class, IndexCatalog.class, UserDataDeletionProvider.class})
    public UserDataDeletionService userDataDeletionService(
        VectorDatabaseService vectorDatabaseService,
        IndexCatalog indexCatalog,
        org.springframework.beans.factory.ObjectProvider<Clock> clockProvider,
        UserDataDeletionProvider userDataDeletionProvider,
        org.springframework.beans.factory.ObjectProvider<BehaviorDeletionPort> behaviorDeletionPortProvider
    ) {
        Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
        return new UserDataDeletionService(
            vectorDatabaseService,
            indexCatalog,
            clock,
            userDataDeletionProvider,
            behaviorDeletionPortProvider.getIfAvailable()
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.governance.privacy", name = "enabled", havingValue = "true")
    @ConditionalOnBean(AICoreService.class)
    public AIDataPrivacyService aiDataPrivacyService(AICoreService aiCoreService,
                                                     PromptTemplateResolver promptTemplateResolver,
                                                     PromptRenderer promptRenderer) {
        return new AIDataPrivacyService(aiCoreService, promptTemplateResolver, promptRenderer);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.governance.compliance", name = "enabled", havingValue = "true")
    @ConditionalOnBean(ComplianceCheckProvider.class)
    public AIComplianceService aiComplianceService(
        org.springframework.beans.factory.ObjectProvider<Clock> clockProvider,
        ComplianceCheckProvider complianceCheckProvider
    ) {
        Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
        return new AIComplianceService(clock, complianceCheckProvider);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.governance.compliance", name = "enabled", havingValue = "true")
    @ConditionalOnBean(AIComplianceService.class)
    public PipelineStep complianceCheckStep(AIComplianceService complianceService) {
        return new ComplianceCheckStep(complianceService);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.governance.content-filter", name = "enabled", havingValue = "true")
    @ConditionalOnBean(AICoreService.class)
    public AIContentFilterService aiContentFilterService(AICoreService aiCoreService,
                                                         PromptTemplateResolver promptTemplateResolver,
                                                         PromptRenderer promptRenderer) {
        return new AIContentFilterService(aiCoreService, promptTemplateResolver, promptRenderer);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.governance.retention", name = "enabled", havingValue = "true")
    @ConditionalOnBean({IndexCatalog.class, VectorDatabaseService.class})
    public RetentionCleanupScheduler retentionCleanupScheduler(
        AIGovernanceProperties properties,
        IndexCatalog indexCatalog,
        VectorDatabaseService vectorDatabaseService,
        org.springframework.beans.factory.ObjectProvider<RetentionPolicyProvider> retentionPolicyProvider,
        org.springframework.beans.factory.ObjectProvider<Clock> clockProvider
    ) {
        Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
        return new RetentionCleanupScheduler(properties, indexCatalog, vectorDatabaseService, retentionPolicyProvider, clock);
    }

    @Bean
    public SmartInitializingSingleton governancePiiConfigurationValidator(
        AIGovernanceProperties properties,
        ObjectProvider<PIIDetectionService> piiDetectionServiceProvider,
        ObjectProvider<PipelineStep> pipelineStepProvider
    ) {
        return new GovernancePiiConfigurationValidator(properties, piiDetectionServiceProvider, pipelineStepProvider);
    }

    @Slf4j
    @RequiredArgsConstructor
    static class GovernancePiiConfigurationValidator implements SmartInitializingSingleton {

        private static final String PII_STEP_NAME = "PIIDetection";

        private final AIGovernanceProperties properties;
        private final ObjectProvider<PIIDetectionService> piiDetectionServiceProvider;
        private final ObjectProvider<PipelineStep> pipelineStepProvider;

        @Override
        public void afterSingletonsInstantiated() {
            if (properties == null || !properties.isEnabled() || properties.getPii() == null || !properties.getPii().isEnabled()) {
                return;
            }

            PIIDetectionService service = piiDetectionServiceProvider.getIfAvailable();
            if (service == null) {
                String message = "ai.governance.pii.enabled=true requires a PIIDetectionService. " +
                    "Enable ai-infrastructure-pii via ai.pii-detection.enabled=true or provide a custom PIIDetectionService bean.";
                if (properties.getPii().isRequireDetectionService()) {
                    throw new IllegalStateException(message);
                }
                log.warn(message);
                return;
            }

            if (properties.getPii().isRequirePipelineStep()) {
                boolean foundStep = pipelineStepProvider.orderedStream()
                    .anyMatch(step -> step != null && PII_STEP_NAME.equals(step.getStepName()));
                if (!foundStep) {
                    throw new IllegalStateException(
                        "ai.governance.pii.require-pipeline-step=true requires a PipelineStep with stepName=PIIDetection. " +
                            "Enable ai-infrastructure-pii and set ai.pii-detection.enabled=true (creates PIIDetectionStep) " +
                            "or provide your own PipelineStep implementation.");
                }
            }
        }
    }
}
