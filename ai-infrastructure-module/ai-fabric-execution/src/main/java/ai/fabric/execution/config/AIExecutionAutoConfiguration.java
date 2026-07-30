package ai.fabric.execution.config;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.evidence.AIEvidenceReferenceMapper;
import ai.fabric.execution.action.ActionProposalCoordinator;
import ai.fabric.execution.delegation.DefaultSpecialistDelegationGateway;
import ai.fabric.execution.delegation.DefaultSpecialistHandoffGateway;
import ai.fabric.execution.delegation.SpecialistDelegationGateway;
import ai.fabric.execution.gateway.AIExecutionConversationRecorder;
import ai.fabric.execution.gateway.AIExecutionConversationSnapshotRegistry;
import ai.fabric.execution.gateway.AIExecutionGateway;
import ai.fabric.execution.gateway.DefaultAIExecutionCoordinator;
import ai.fabric.execution.gateway.DefaultAIExecutionGateway;
import ai.fabric.execution.gateway.DefaultSpecialistAuthorityResolver;
import ai.fabric.execution.gateway.DefaultStructuredSpecialistOutputFinalizer;
import ai.fabric.execution.gateway.DurableAIExecutionGateway;
import ai.fabric.execution.gateway.EphemeralAIExecutionConversationSnapshotRegistry;
import ai.fabric.execution.gateway.ExecutionCapabilityInventory;
import ai.fabric.execution.gateway.OrchestrationEvidenceProjector;
import ai.fabric.execution.gateway.SpecialistAuthorityResolver;
import ai.fabric.execution.gateway.SpecialistCapabilityResolver;
import ai.fabric.execution.gateway.SpecialistGroundingProjector;
import ai.fabric.execution.gateway.SpecialistOutputFinalizer;
import ai.fabric.execution.handoff.SpecialistHandoffGateway;
import ai.fabric.execution.manager.ConversationManagerDefinition;
import ai.fabric.execution.manager.ConversationManagerRegistry;
import ai.fabric.execution.manager.DefaultConversationManagerRegistry;
import ai.fabric.execution.specialist.DefaultSpecialistRegistry;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistDefinitionValidator;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.client.DefaultSpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.DefaultSpecialistManifestCompiler;
import ai.fabric.execution.specialist.manifest.DefaultSpecialistManifestLoader;
import ai.fabric.execution.specialist.manifest.DefaultSpecialistAuthoringCatalogProvider;
import ai.fabric.execution.specialist.manifest.MicrometerSpecialistManifestMetrics;
import ai.fabric.execution.specialist.manifest.SpecialistAuthoringCatalogProvider;
import ai.fabric.execution.specialist.manifest.SpecialistDirectOutputProjector;
import ai.fabric.execution.specialist.manifest.SpecialistDirectOutputProjectorRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistFinalOutputValidator;
import ai.fabric.execution.specialist.manifest.SpecialistFinalOutputValidatorRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistGroundingValidator;
import ai.fabric.execution.specialist.manifest.SpecialistGroundingValidatorRegistry;
import ai.fabric.execution.input.SpecialistInputContinuation;
import ai.fabric.execution.plan.AIExecutionCoordinator;
import ai.fabric.execution.plan.DefaultExecutionPlanRegistry;
import ai.fabric.execution.plan.ExecutionPlanDefinition;
import ai.fabric.execution.plan.ExecutionPlanRegistry;
import ai.fabric.execution.plan.PlanComponentRegistry;
import ai.fabric.execution.plan.PlanResultAggregator;
import ai.fabric.execution.plan.PlanStepInputMapper;
import ai.fabric.execution.specialist.manifest.SpecialistInputContinuationRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaValidator;
import ai.fabric.execution.specialist.manifest.SpecialistManifestCompiler;
import ai.fabric.execution.specialist.manifest.SpecialistManifestLoader;
import ai.fabric.execution.specialist.manifest.SpecialistManifestMetrics;
import ai.fabric.execution.specialist.manifest.SpecialistManifestRuntimeStatus;
import ai.fabric.execution.specialist.manifest.SpecialistOutputNormalizer;
import ai.fabric.execution.specialist.manifest.SpecialistOutputNormalizerRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistPromptProfileRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistRegistryBootstrap;
import ai.fabric.execution.specialist.manifest.SpecialistResourceBundle;
import ai.fabric.execution.state.DurableExecutionPayloadCodec;
import ai.fabric.execution.state.DurableExecutionRepository;
import ai.fabric.execution.state.DurableExecutionSecurity;
import ai.fabric.execution.state.DurableExecutionSubmissionPolicy;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilitiesResolver;
import ai.fabric.intent.orchestration.pipeline.Pipeline;
import ai.fabric.intent.orchestration.pipeline.steps.OrchestrationPolicyResolutionStep;
import ai.fabric.llm.structured.StructuredJsonCallExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import io.micrometer.core.instrument.MeterRegistry;
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
    public SpecialistDefinitionValidator specialistDefinitionValidator(
        AIActionRegistry actionRegistry,
        OrchestrationProperties orchestrationProperties,
        ExecutionCapabilityInventory capabilityInventory
    ) {
        return new SpecialistDefinitionValidator(
            actionRegistry,
            knownModes(orchestrationProperties),
            capabilityInventory.registeredVectorSpaces()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public CanonicalJsonSupport specialistCanonicalJsonSupport(
        ObjectMapper objectMapper
    ) {
        return new CanonicalJsonSupport(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistJsonSchemaValidator specialistJsonSchemaValidator() {
        return new SpecialistJsonSchemaValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistManifestLoader specialistManifestLoader(
        ObjectMapper objectMapper
    ) {
        return new DefaultSpecialistManifestLoader(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistManifestCompiler specialistManifestCompiler() {
        return new DefaultSpecialistManifestCompiler();
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistGroundingValidatorRegistry
        specialistGroundingValidatorRegistry(
            List<SpecialistGroundingValidator> validators
        ) {
        return new SpecialistGroundingValidatorRegistry(validators);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistFinalOutputValidatorRegistry
        specialistFinalOutputValidatorRegistry(
            List<SpecialistFinalOutputValidator> validators
        ) {
        return new SpecialistFinalOutputValidatorRegistry(validators);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistDirectOutputProjectorRegistry
        specialistDirectOutputProjectorRegistry(
            List<SpecialistDirectOutputProjector> projectors
        ) {
        return new SpecialistDirectOutputProjectorRegistry(projectors);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistOutputNormalizerRegistry
        specialistOutputNormalizerRegistry(
            List<SpecialistOutputNormalizer> normalizers
        ) {
        return new SpecialistOutputNormalizerRegistry(normalizers);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistInputContinuationRegistry
        specialistInputContinuationRegistry(
            List<SpecialistInputContinuation<?>> continuations
        ) {
        return new SpecialistInputContinuationRegistry(continuations);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistManifestMetrics specialistManifestMetrics(
        ObjectProvider<MeterRegistry> meterRegistryProvider
    ) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        return meterRegistry == null
            ? SpecialistManifestMetrics.noop()
            : new MicrometerSpecialistManifestMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistResourceBundle specialistResourceBundle(
        SpecialistManifestLoader loader,
        AIExecutionProperties properties
    ) {
        return loader.load(properties.getManifests());
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistRegistryBootstrap specialistRegistryBootstrap(
        List<SpecialistDefinition<?, ?>> definitions,
        OrchestrationProperties orchestrationProperties,
        SpecialistResourceBundle resources,
        SpecialistManifestCompiler compiler,
        SpecialistDefinitionValidator definitionValidator,
        SpecialistGroundingValidatorRegistry groundingValidators,
        SpecialistFinalOutputValidatorRegistry finalOutputValidators,
        SpecialistDirectOutputProjectorRegistry directOutputProjectors,
        SpecialistOutputNormalizerRegistry outputNormalizers,
        SpecialistInputContinuationRegistry inputContinuations,
        SpecialistJsonSchemaValidator schemaValidator,
        CanonicalJsonSupport canonicalJson,
        ObjectMapper objectMapper,
        AIExecutionProperties properties,
        SpecialistManifestMetrics metrics
    ) {
        return new SpecialistRegistryBootstrap(
            definitions,
            resources,
            compiler,
            definitionValidator,
            groundingValidators,
            finalOutputValidators,
            directOutputProjectors,
            outputNormalizers,
            inputContinuations,
            schemaValidator,
            canonicalJson,
            objectMapper,
            iterativeModes(orchestrationProperties),
            properties.getManifests(),
            metrics
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistRegistry specialistRegistry(
        SpecialistRegistryBootstrap bootstrap
    ) {
        return bootstrap.registry();
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistManifestRuntimeStatus specialistManifestRuntimeStatus(
        SpecialistRegistryBootstrap bootstrap
    ) {
        return bootstrap.status();
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistJsonSchemaRegistry specialistJsonSchemaRegistry(
        SpecialistRegistryBootstrap bootstrap
    ) {
        return bootstrap.schemaRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistPromptProfileRegistry specialistPromptProfileRegistry(
        SpecialistRegistryBootstrap bootstrap
    ) {
        return bootstrap.promptProfileRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistAuthoringCatalogProvider
        specialistAuthoringCatalogProvider(
            OrchestrationProperties orchestrationProperties,
            ExecutionCapabilityInventory capabilityInventory,
            AIActionRegistry actionRegistry,
            SpecialistJsonSchemaRegistry schemaRegistry,
            SpecialistPromptProfileRegistry promptProfileRegistry,
            SpecialistGroundingValidatorRegistry groundingValidators,
            SpecialistFinalOutputValidatorRegistry finalOutputValidators,
            SpecialistDirectOutputProjectorRegistry directOutputProjectors,
            SpecialistOutputNormalizerRegistry outputNormalizers,
            SpecialistInputContinuationRegistry inputContinuations
        ) {
        return new DefaultSpecialistAuthoringCatalogProvider(
            knownModes(orchestrationProperties),
            capabilityInventory,
            actionRegistry,
            schemaRegistry,
            promptProfileRegistry,
            groundingValidators,
            finalOutputValidators,
            directOutputProjectors,
            outputNormalizers,
            inputContinuations
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
    public AIExecutionConversationSnapshotRegistry
        aiExecutionConversationSnapshotRegistry(Clock clock) {
        return new EphemeralAIExecutionConversationSnapshotRegistry(
            clock,
            Duration.ofMinutes(2)
        );
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
        AIExecutionProperties properties,
        SpecialistManifestMetrics specialistMetrics,
        SpecialistJsonSchemaRegistry schemaRegistry,
        SpecialistJsonSchemaValidator schemaValidator,
        CanonicalJsonSupport canonicalJson,
        ObjectMapper objectMapper,
        ObjectProvider<DurableExecutionRepository> durableRepository,
        ObjectProvider<DurableExecutionSecurity> durableSecurity,
        AIExecutionConversationSnapshotRegistry conversationSnapshotRegistry
    ) {
        DefaultAIExecutionGateway gateway = new DefaultAIExecutionGateway(
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
            properties.getAsync().getResultTtl(),
            specialistMetrics,
            schemaRegistry,
            schemaValidator,
            canonicalJson,
            properties.getInputWaits(),
            conversationSnapshotRegistry
        );
        if (properties.getAsync().getRepository()
            == AIExecutionProperties.AsyncRepository.IN_MEMORY) {
            return gateway;
        }

        DurableExecutionRepository repository =
            durableRepository.getIfAvailable();
        DurableExecutionSecurity security = durableSecurity.getIfAvailable();
        if (repository == null || security == null) {
            throw new IllegalStateException(
                "ai.execution.async.repository=JDBC requires a DataSource, "
                    + "the JDBC execution state adapter, and two distinct "
                    + "ai.execution.async secrets of at least 32 characters"
            );
        }
        return new DurableAIExecutionGateway(
            gateway,
            gateway,
            specialistRegistry,
            repository,
            new DurableExecutionPayloadCodec(
                objectMapper,
                specialistRegistry,
                security
            ),
            security,
            new DurableExecutionSubmissionPolicy(),
            taskExecutor,
            clock,
            properties.getAsync().getLeaseDuration(),
            properties.getAsync().getRetention(),
            properties.getAsync().getRecoveryBatchSize(),
            properties.getAsync().getMaxAttempts(),
            properties.getAsync().isCleanupEnabled()
        );
    }

    @Bean
    @ConditionalOnBean(AIExecutionGateway.class)
    @ConditionalOnMissingBean
    public SpecialistClientFactory specialistClientFactory(
        SpecialistRegistry specialistRegistry,
        AIExecutionGateway executionGateway,
        ObjectMapper objectMapper
    ) {
        return new DefaultSpecialistClientFactory(
            specialistRegistry,
            executionGateway,
            objectMapper
        );
    }

    @Bean
    @ConditionalOnBean(SpecialistClientFactory.class)
    @ConditionalOnMissingBean
    public SpecialistDelegationGateway specialistDelegationGateway(
        SpecialistRegistry specialistRegistry,
        SpecialistClientFactory specialistClientFactory,
        CanonicalJsonSupport canonicalJson,
        Clock clock,
        AIExecutionProperties properties
    ) {
        return new DefaultSpecialistDelegationGateway(
            specialistRegistry,
            specialistClientFactory,
            canonicalJson,
            clock,
            properties.getAsync().getResultTtl()
        );
    }

    @Bean
    @ConditionalOnBean(SpecialistClientFactory.class)
    @ConditionalOnMissingBean
    public SpecialistHandoffGateway specialistHandoffGateway(
        SpecialistRegistry specialistRegistry,
        SpecialistClientFactory specialistClientFactory,
        CanonicalJsonSupport canonicalJson,
        Clock clock,
        AIExecutionProperties properties
    ) {
        return new DefaultSpecialistHandoffGateway(
            specialistRegistry,
            specialistClientFactory,
            canonicalJson,
            clock,
            properties.getAsync().getResultTtl()
        );
    }

    @Bean
    @ConditionalOnBean(SpecialistClientFactory.class)
    @ConditionalOnProperty(
        prefix = "ai.execution.conversation-managers",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
    )
    @ConditionalOnMissingBean
    public ConversationManagerRegistry conversationManagerRegistry(
        List<ConversationManagerDefinition<?>> definitions,
        SpecialistRegistry specialistRegistry,
        SpecialistClientFactory specialistClientFactory,
        CanonicalJsonSupport canonicalJson,
        AIExecutionProperties properties
    ) {
        return new DefaultConversationManagerRegistry(
            definitions,
            specialistRegistry,
            specialistClientFactory,
            canonicalJson,
            properties.getConversationManagers().getMaxDuration()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public PlanComponentRegistry planComponentRegistry(
        List<PlanStepInputMapper<?, ?>> inputMappers,
        List<PlanResultAggregator<?, ?>> resultAggregators
    ) {
        return new PlanComponentRegistry(inputMappers, resultAggregators);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExecutionPlanRegistry executionPlanRegistry(
        List<ExecutionPlanDefinition<?, ?>> definitions,
        SpecialistRegistry specialistRegistry,
        SpecialistClientFactory specialistClientFactory,
        PlanComponentRegistry componentRegistry,
        AIExecutionProperties properties
    ) {
        return new DefaultExecutionPlanRegistry(
            definitions,
            specialistRegistry,
            specialistClientFactory,
            componentRegistry,
            properties.getPlans().getMaxSteps(),
            properties.getPlans().getMaxDuration(),
            properties.getPlans().isParallelEnabled(),
            properties.getPlans().getMaxParallelBranches()
        );
    }

    @Bean
    @ConditionalOnBean(AIExecutionGateway.class)
    @ConditionalOnProperty(
        prefix = "ai.execution.plans",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
    )
    @ConditionalOnMissingBean
    public AIExecutionCoordinator aiExecutionCoordinator(
        ExecutionPlanRegistry planRegistry,
        PlanComponentRegistry componentRegistry,
        AIExecutionGateway executionGateway,
        SpecialistClientFactory specialistClientFactory,
        @Qualifier("aiFabricExecutionTaskExecutor")
        AsyncTaskExecutor taskExecutor,
        CanonicalJsonSupport canonicalJson,
        Clock clock,
        AIExecutionProperties properties
    ) {
        return new DefaultAIExecutionCoordinator(
            planRegistry,
            componentRegistry,
            executionGateway,
            specialistClientFactory,
            taskExecutor,
            canonicalJson,
            clock,
            properties.getPlans()
        );
    }

    private Set<String> knownModes(
        OrchestrationProperties orchestrationProperties
    ) {
        Set<String> knownModes = new LinkedHashSet<>();
        if (orchestrationProperties.getModes() != null) {
            orchestrationProperties.getModes().keySet().stream()
                .filter(java.util.Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .forEach(knownModes::add);
        }
        if (orchestrationProperties.getDefaultMode() != null
            && !orchestrationProperties.getDefaultMode().isBlank()) {
            knownModes.add(
                orchestrationProperties.getDefaultMode()
                    .trim()
                    .toLowerCase(Locale.ROOT)
            );
        }
        return Set.copyOf(knownModes);
    }

    private Set<String> iterativeModes(
        OrchestrationProperties orchestrationProperties
    ) {
        if (orchestrationProperties.getModes() == null) {
            return Set.of();
        }
        Set<String> modes = new LinkedHashSet<>();
        orchestrationProperties.getModes().forEach((name, overrides) -> {
            if (name == null
                || overrides == null
                || overrides.getReadActionResolution() == null) {
                return;
            }
            var read = overrides.getReadActionResolution();
            if (Boolean.TRUE.equals(read.getEnabled())
                && read.getPlanningMode()
                    == OrchestrationProperties
                        .ReadActionResolutionPlanningMode.ITERATIVE) {
                modes.add(name.trim().toLowerCase(Locale.ROOT));
            }
        });
        return Set.copyOf(modes);
    }
}
