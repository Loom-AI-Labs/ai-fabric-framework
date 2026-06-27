package ai.fabric.governance.config;

import ai.fabric.core.AICoreService;
import ai.fabric.compliance.AIComplianceService;
import ai.fabric.compliance.policy.ComplianceCheckProvider;
import ai.fabric.deletion.UserDataDeletionService;
import ai.fabric.deletion.policy.UserDataDeletionProvider;
import ai.fabric.deletion.port.BehaviorDeletionPort;
import ai.fabric.filter.AIContentFilterService;
import ai.fabric.governance.catalog.IndexCatalog;
import ai.fabric.governance.catalog.disabled.DisabledIndexCatalog;
import ai.fabric.governance.catalog.jpa.IndexCatalogRepository;
import ai.fabric.governance.catalog.jpa.JpaIndexCatalog;
import ai.fabric.governance.catalog.vector.VectorIndexCatalog;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import ai.fabric.intent.orchestration.pipeline.steps.ComplianceCheckStep;
import ai.fabric.privacy.AIDataPrivacyService;
import ai.fabric.governance.vector.GovernanceVectorDatabaseServiceDecorator;
import ai.fabric.privacy.pii.PIIDetectionService;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.retention.RetentionCleanupScheduler;
import ai.fabric.retention.policy.RetentionPolicyProvider;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
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
@AutoConfigurationPackage(basePackages = "ai.fabric.governance")
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
    public IndexCatalog indexCatalog(
        AIGovernanceProperties properties,
        ObjectProvider<VectorDatabaseService> vectorDatabaseServiceProvider,
        ObjectMapper governanceObjectMapper,
        ObjectProvider<IndexCatalogRepository> repositoryProvider
    ) {
        AIGovernanceProperties.CatalogProperties.Mode mode = properties.getCatalog() != null
            ? properties.getCatalog().getMode()
            : AIGovernanceProperties.CatalogProperties.Mode.AUTO;

        VectorDatabaseService vectorDatabaseService = vectorDatabaseServiceProvider.getIfAvailable();
        IndexCatalogRepository repository = repositoryProvider.getIfAvailable();
        boolean vectorCapable = vectorDatabaseService != null
            && vectorDatabaseService.supportsVectorScan()
            && vectorDatabaseService.supportsScanMetadataFiltering();
        boolean sqlCapable = repository != null;

        AIGovernanceProperties.CatalogProperties.Mode resolved = switch (mode) {
            case VECTOR -> AIGovernanceProperties.CatalogProperties.Mode.VECTOR;
            case SQL -> AIGovernanceProperties.CatalogProperties.Mode.SQL;
            case DISABLED -> AIGovernanceProperties.CatalogProperties.Mode.DISABLED;
            case AUTO -> vectorCapable
                ? AIGovernanceProperties.CatalogProperties.Mode.VECTOR
                : (sqlCapable ? AIGovernanceProperties.CatalogProperties.Mode.SQL : AIGovernanceProperties.CatalogProperties.Mode.DISABLED);
        };

        if (resolved == AIGovernanceProperties.CatalogProperties.Mode.VECTOR) {
            if (vectorDatabaseService == null) {
                throw new IllegalStateException("ai.governance.catalog.mode=VECTOR requires a VectorDatabaseService bean");
            }
            if (!vectorCapable) {
                throw new IllegalStateException(
                    "ai.governance.catalog.mode=VECTOR requires VectorDatabaseService to support vector scan and scan metadata filtering");
            }
            return new VectorIndexCatalog(vectorDatabaseService);
        }

        if (resolved == AIGovernanceProperties.CatalogProperties.Mode.SQL) {
            if (repository == null) {
                throw new IllegalStateException("ai.governance.catalog.mode=SQL requires IndexCatalogRepository (JPA) to be available");
            }
            return new JpaIndexCatalog(repository, governanceObjectMapper);
        }

        return new DisabledIndexCatalog();
    }

    @Bean
    public static BeanPostProcessor governanceVectorDatabaseServiceDecorator(
        ObjectProvider<AIGovernanceProperties> propertiesProvider,
        ObjectProvider<IndexCatalog> catalogProvider
    ) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof VectorDatabaseService vectorDatabaseService)) {
                    return bean;
                }
                AIGovernanceProperties properties = propertiesProvider.getIfAvailable();
                if (properties == null || !properties.isEnabled()) {
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
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ai.governance.deletion", name = "enabled", havingValue = "true")
    public UserDataDeletionService userDataDeletionService(
        ObjectProvider<VectorDatabaseService> vectorDatabaseServiceProvider,
        ObjectProvider<IndexCatalog> indexCatalogProvider,
        ObjectProvider<Clock> clockProvider,
        ObjectProvider<UserDataDeletionProvider> userDataDeletionProvider,
        ObjectProvider<BehaviorDeletionPort> behaviorDeletionPortProvider
    ) {
        Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
        return new UserDataDeletionService(
            vectorDatabaseServiceProvider.getIfAvailable(),
            indexCatalogProvider.getIfAvailable(),
            clock,
            userDataDeletionProvider.getIfAvailable(),
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
