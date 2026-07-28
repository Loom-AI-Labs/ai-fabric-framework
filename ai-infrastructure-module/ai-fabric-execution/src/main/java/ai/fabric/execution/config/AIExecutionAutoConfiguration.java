package ai.fabric.execution.config;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.evidence.AIEvidenceReferenceMapper;
import ai.fabric.execution.action.ActionProposalCoordinator;
import ai.fabric.execution.gateway.AIExecutionGateway;
import ai.fabric.execution.gateway.AIExecutionConversationRecorder;
import ai.fabric.execution.gateway.DefaultAIExecutionGateway;
import ai.fabric.execution.gateway.DefaultStructuredSpecialistOutputFinalizer;
import ai.fabric.execution.gateway.DefaultSpecialistAuthorityResolver;
import ai.fabric.execution.gateway.ExecutionCapabilityInventory;
import ai.fabric.execution.gateway.OrchestrationEvidenceProjector;
import ai.fabric.execution.gateway.SpecialistAuthorityResolver;
import ai.fabric.execution.gateway.SpecialistCapabilityResolver;
import ai.fabric.execution.gateway.SpecialistGroundingProjector;
import ai.fabric.execution.gateway.SpecialistOutputFinalizer;
import ai.fabric.execution.specialist.DefaultSpecialistRegistry;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilitiesResolver;
import ai.fabric.intent.orchestration.pipeline.Pipeline;
import ai.fabric.intent.orchestration.pipeline.steps.OrchestrationPolicyResolutionStep;
import ai.fabric.llm.structured.StructuredJsonCallExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@AutoConfiguration
@EnableConfigurationProperties(AIExecutionProperties.class)
@ConditionalOnProperty(
    prefix = "ai.execution",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class AIExecutionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SpecialistRegistry specialistRegistry(
        List<SpecialistDefinition<?, ?>> definitions,
        AIActionRegistry actionRegistry,
        OrchestrationProperties orchestrationProperties,
        ExecutionCapabilityInventory capabilityInventory
    ) {
        Set<String> knownModes = new LinkedHashSet<>();
        if (orchestrationProperties.getModes() != null) {
            knownModes.addAll(orchestrationProperties.getModes().keySet());
        }
        if (orchestrationProperties.getDefaultMode() != null) {
            knownModes.add(orchestrationProperties.getDefaultMode());
        }
        return new DefaultSpecialistRegistry(
            definitions,
            actionRegistry,
            knownModes,
            capabilityInventory.registeredVectorSpaces()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ExecutionCapabilityInventory executionCapabilityInventory(
        ObjectProvider<AIEntityDescriptorRegistry> descriptorRegistryProvider,
        AIExecutionProperties properties
    ) {
        return new ExecutionCapabilityInventory() {
            @Override
            public Set<String> registeredVectorSpaces() {
                Set<String> vectorSpaces = new LinkedHashSet<>(
                    properties.getCapabilities().getRegisteredVectorSpaces()
                );
                AIEntityDescriptorRegistry registry =
                    descriptorRegistryProvider.getIfAvailable();
                if (registry != null) {
                    registry.descriptors().stream()
                        .filter(descriptor -> descriptor.indexingEnabled())
                        .map(descriptor ->
                            descriptor.entityType().toLowerCase(Locale.ROOT)
                        )
                        .forEach(vectorSpaces::add);
                }
                return Set.copyOf(vectorSpaces);
            }

            @Override
            public Set<String> deploymentAllowedActions() {
                return properties.getCapabilities().getAllowedActions();
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistAuthorityResolver specialistAuthorityResolver() {
        return new DefaultSpecialistAuthorityResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistCapabilityResolver specialistCapabilityResolver(
        EffectiveCapabilitiesResolver capabilitiesResolver,
        AIActionRegistry actionRegistry,
        ExecutionCapabilityInventory capabilityInventory,
        SpecialistAuthorityResolver authorityResolver
    ) {
        return new SpecialistCapabilityResolver(
            capabilitiesResolver,
            actionRegistry,
            capabilityInventory,
            authorityResolver
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public AIEvidenceReferenceMapper aiExecutionEvidenceReferenceMapper() {
        return new AIEvidenceReferenceMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public OrchestrationEvidenceProjector orchestrationEvidenceProjector(
        AIEvidenceReferenceMapper mapper
    ) {
        return new OrchestrationEvidenceProjector(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistGroundingProjector specialistGroundingProjector() {
        return new SpecialistGroundingProjector();
    }

    @Bean
    @ConditionalOnBean({
        AICoreService.class,
        StructuredJsonCallExecutor.class,
        ObjectMapper.class
    })
    @ConditionalOnMissingBean
    public SpecialistOutputFinalizer specialistOutputFinalizer(
        AICoreService aiCoreService,
        StructuredJsonCallExecutor structuredJsonCallExecutor,
        ObjectMapper objectMapper,
        SpecialistGroundingProjector groundingProjector
    ) {
        return new DefaultStructuredSpecialistOutputFinalizer(
            aiCoreService,
            structuredJsonCallExecutor,
            objectMapper,
            groundingProjector
        );
    }

    @Bean(name = "aiFabricExecutionTaskExecutor", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "aiFabricExecutionTaskExecutor")
    public ThreadPoolTaskExecutor aiFabricExecutionTaskExecutor(
        AIExecutionProperties properties
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ai-fabric-execution-");
        executor.setCorePoolSize(properties.getAsync().getCorePoolSize());
        executor.setMaxPoolSize(properties.getAsync().getMaxPoolSize());
        executor.setQueueCapacity(properties.getAsync().getQueueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean
    @ConditionalOnBean({
        Pipeline.class,
        AIActionRegistry.class,
        OrchestrationPolicyResolutionStep.class,
        SpecialistOutputFinalizer.class
    })
    @ConditionalOnMissingBean(AIExecutionGateway.class)
    public AIExecutionGateway aiExecutionGateway(
        SpecialistRegistry specialistRegistry,
        Pipeline pipeline,
        OrchestrationPolicyResolutionStep policyResolutionStep,
        EffectiveCapabilitiesResolver capabilitiesResolver,
        AIActionRegistry actionRegistry,
        ExecutionCapabilityInventory capabilityInventory,
        SpecialistAuthorityResolver authorityResolver,
        OrchestrationEvidenceProjector evidenceProjector,
        SpecialistOutputFinalizer outputFinalizer,
        ObjectProvider<AIExecutionConversationRecorder> conversationRecorder,
        ObjectProvider<ActionProposalCoordinator> actionProposalCoordinator,
        @Qualifier("aiFabricExecutionTaskExecutor") AsyncTaskExecutor taskExecutor,
        Clock clock,
        AIExecutionProperties properties
    ) {
        return new DefaultAIExecutionGateway(
            specialistRegistry,
            pipeline,
            policyResolutionStep,
            capabilitiesResolver,
            actionRegistry,
            capabilityInventory,
            authorityResolver,
            evidenceProjector,
            outputFinalizer,
            conversationRecorder.getIfAvailable(),
            actionProposalCoordinator::getIfAvailable,
            taskExecutor,
            clock,
            properties.getAsync().getResultTtl()
        );
    }
}
