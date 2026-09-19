package ai.fabric.execution.config;

import ai.fabric.execution.chain.DefaultSpecialistChainRegistry;
import ai.fabric.execution.chain.MicrometerSpecialistChainMetrics;
import ai.fabric.execution.chain.SpecialistChainDefinition;
import ai.fabric.execution.chain.SpecialistChainRegistration;
import ai.fabric.execution.chain.SpecialistChainRegistrationBundle;
import ai.fabric.execution.chain.SpecialistChainDefinitionSource;
import ai.fabric.execution.chain.SpecialistChainGateway;
import ai.fabric.execution.chain.SpecialistChainMetrics;
import ai.fabric.execution.chain.SpecialistChainRegistry;
import ai.fabric.execution.chain.state.InMemorySpecialistChainExecutionRepository;
import ai.fabric.execution.chain.state.SpecialistChainExecutionRepository;
import ai.fabric.execution.chain.state.SpecialistChainPayloadCodec;
import ai.fabric.execution.chain.state.SpecialistChainSecurity;
import ai.fabric.execution.delegation.SpecialistDelegationGateway;
import ai.fabric.execution.gateway.AIExecutionConversationRecorder;
import ai.fabric.execution.gateway.DefaultSpecialistChainGateway;
import ai.fabric.execution.gateway.SharedInteractiveTurnCoordinator;
import ai.fabric.execution.handoff.SpecialistHandoffGateway;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.SpecialistCompilationDiagnostic;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaValidator;
import ai.fabric.execution.specialist.manifest.SpecialistManifestException;
import ai.fabric.execution.specialist.manifest.SpecialistResourceBundle;
import ai.fabric.execution.chain.manifest.DefaultSpecialistChainManifestCompiler;
import ai.fabric.execution.chain.manifest.DefaultSpecialistChainAuthoringCatalogProvider;
import ai.fabric.execution.chain.manifest.DefaultSpecialistChainManifestValidator;
import ai.fabric.execution.chain.manifest.LoadedSpecialistChainManifest;
import ai.fabric.execution.chain.manifest.SpecialistChainCompilationContext;
import ai.fabric.execution.chain.manifest.SpecialistChainManifestCompiler;
import ai.fabric.execution.chain.manifest.SpecialistChainAuthoringCatalogProvider;
import ai.fabric.execution.chain.manifest.SpecialistChainManifestValidator;
import ai.fabric.execution.chain.manifest.SpecialistChainManifestRuntimeStatus;
import ai.fabric.execution.chain.manifest.SpecialistChainManifestMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Opt-in runtime assembly for bounded multi-specialist chains. */
@AutoConfiguration(
    after = {
        AIExecutionAutoConfiguration.class,
        AIExecutionChatSessionAutoConfiguration.class
    }
)
@EnableConfigurationProperties(AIExecutionProperties.class)
@EnableScheduling
@ConditionalOnProperty(
    prefix = "ai.execution.specialist-chains",
    name = "enabled",
    havingValue = "true"
)
public class AIExecutionSpecialistChainAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SpecialistChainManifestCompiler specialistChainManifestCompiler(
        SpecialistChainManifestMetrics metrics
    ) {
        return new DefaultSpecialistChainManifestCompiler(metrics);
    }

    @Bean
    @ConditionalOnBean(SpecialistClientFactory.class)
    @ConditionalOnMissingBean
    public SpecialistChainCompilationContext
        specialistChainCompilationContext(
            SpecialistRegistry specialistRegistry,
            SpecialistClientFactory clientFactory,
            SpecialistJsonSchemaRegistry schemaRegistry,
            SpecialistJsonSchemaValidator schemaValidator,
            CanonicalJsonSupport canonicalJson,
            ObjectMapper objectMapper,
            AIExecutionProperties properties
        ) {
        return new SpecialistChainCompilationContext(
            specialistRegistry,
            clientFactory,
            schemaRegistry,
            schemaValidator,
            canonicalJson,
            objectMapper,
            properties.getSpecialistChains()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistChainManifestValidator specialistChainManifestValidator(
        SpecialistChainManifestCompiler compiler,
        SpecialistChainCompilationContext context
    ) {
        return new DefaultSpecialistChainManifestValidator(compiler, context);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialistChainAuthoringCatalogProvider
        specialistChainAuthoringCatalogProvider(
            SpecialistRegistry specialistRegistry,
            AIExecutionProperties properties
        ) {
        return new DefaultSpecialistChainAuthoringCatalogProvider(
            specialistRegistry,
            properties.getSpecialistChains()
        );
    }

    @Bean
    @ConditionalOnBean(SpecialistClientFactory.class)
    @ConditionalOnMissingBean(SpecialistChainRegistrationBundle.class)
    public SpecialistChainRegistrationBundle specialistChainRegistrations(
        List<SpecialistChainDefinition<?>> definitions,
        SpecialistResourceBundle resources,
        SpecialistChainManifestCompiler compiler,
        SpecialistChainCompilationContext context,
        AIExecutionProperties properties,
        SpecialistChainManifestMetrics metrics
    ) {
        List<SpecialistChainRegistration> registrations = new ArrayList<>();
        definitions.stream()
            .map(SpecialistChainRegistration::javaDefinition)
            .forEach(registrations::add);
        List<SpecialistCompilationDiagnostic> diagnostics = new ArrayList<>();
        int compiled = 0;
        for (LoadedSpecialistChainManifest manifest
            : resources.chainManifests()) {
            try {
                registrations.add(compiler.compile(manifest, context));
                compiled++;
                metrics.recordCompilation("compiled", "none");
            } catch (SpecialistManifestException ex) {
                metrics.recordCompilation("rejected", ex.reason());
                if (properties.getManifests().isFailFast()) {
                    throw ex;
                }
                diagnostics.add(new SpecialistCompilationDiagnostic(
                    ex.reason(),
                    ex.getMessage(),
                    ex.source() == null ? manifest.source() : ex.source()
                ));
            }
        }
        int javaCount = definitions == null ? 0 : definitions.size();
        metrics.recordRegistryCounts(
            javaCount,
            compiled,
            resources.chainManifests().size() - compiled
        );
        return new SpecialistChainRegistrationBundle(
            registrations,
            diagnostics,
            resources.chainManifests().size(),
            compiled
        );
    }

    @Bean
    @ConditionalOnBean(SpecialistClientFactory.class)
    @ConditionalOnMissingBean(SpecialistChainRegistry.class)
    public SpecialistChainRegistry specialistChainRegistry(
        SpecialistChainRegistrationBundle registrations,
        SpecialistRegistry specialistRegistry,
        SpecialistClientFactory clientFactory,
        CanonicalJsonSupport canonicalJson,
        AIExecutionProperties properties
    ) {
        AIExecutionProperties.SpecialistChains chain =
            properties.getSpecialistChains();
        return new DefaultSpecialistChainRegistry(
            registrations,
            specialistRegistry,
            clientFactory,
            canonicalJson,
            chain.getMaxDuration(),
            chain.getMaxManagerDecisions(),
            chain.getMaxWorkerInvocations(),
            chain.getMaxParallelWorkers(),
            chain.getMaxInvocationsPerTarget(),
            chain.getMaxProjectedResultCharacters()
        );
    }

    @Bean
    @ConditionalOnBean(SpecialistChainRegistry.class)
    @ConditionalOnMissingBean(SpecialistChainManifestRuntimeStatus.class)
    public SpecialistChainManifestRuntimeStatus
        specialistChainManifestRuntimeStatus(
            SpecialistChainRegistrationBundle registrations,
            SpecialistChainRegistry registry,
            CanonicalJsonSupport canonicalJson,
            AIExecutionProperties properties
        ) {
        List<SpecialistChainRegistration> manifestRegistrations =
            registrations.registrations().stream()
                .filter(item -> item.source()
                    == SpecialistChainDefinitionSource.MANIFEST)
                .toList();
        int javaCount = (int) registrations.registrations().stream()
            .filter(item -> item.source()
                == SpecialistChainDefinitionSource.JAVA)
            .count();
        String auditHash = manifestRegistrations.isEmpty()
            ? ""
            : canonicalJson.hashValue(manifestRegistrations.stream()
                .map(item -> item.identity().resourceHash().orElseThrow())
                .sorted()
                .toList());
        String semanticsHash = manifestRegistrations.isEmpty()
            ? ""
            : canonicalJson.hashValue(manifestRegistrations.stream()
                .map(item -> item.identity()
                    .declarativeSemanticsHash().orElseThrow())
                .sorted()
                .toList());
        return new SpecialistChainManifestRuntimeStatus(
            properties.getManifests().isEnabled(),
            true,
            registrations.diagnostics().isEmpty(),
            javaCount,
            registrations.discoveredManifestCount(),
            registrations.discoveredManifestCount()
                - registrations.compiledManifestCount(),
            registrations.compiledManifestCount(),
            registry.list().size(),
            auditHash,
            semanticsHash,
            registry.registryContentHash(),
            registrations.diagnostics()
        );
    }

    @Bean
    @ConditionalOnMissingBean(SpecialistChainMetrics.class)
    public SpecialistChainMetrics specialistChainMetrics(
        ObjectProvider<MeterRegistry> meterRegistryProvider,
        ObjectProvider<SpecialistChainExecutionRepository>
            repositoryProvider
    ) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        SpecialistChainExecutionRepository repository =
            repositoryProvider.getIfAvailable();
        return registry == null || repository == null
            ? SpecialistChainMetrics.noop()
            : new MicrometerSpecialistChainMetrics(registry, repository);
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "ai.execution.specialist-chains",
        name = "durable-enabled",
        havingValue = "false",
        matchIfMissing = true
    )
    @ConditionalOnProperty(
        prefix = "ai.execution.specialist-chains",
        name = "allow-ephemeral",
        havingValue = "true"
    )
    @ConditionalOnMissingBean(SpecialistChainExecutionRepository.class)
    public SpecialistChainExecutionRepository
        inMemorySpecialistChainExecutionRepository() {
        return new InMemorySpecialistChainExecutionRepository();
    }

    @Bean
    @ConditionalOnMissingBean(SpecialistChainSecurity.class)
    public SpecialistChainSecurity specialistChainSecurity(
        ObjectMapper objectMapper,
        AIExecutionProperties properties
    ) {
        AIExecutionProperties.SpecialistChains chain =
            properties.getSpecialistChains();
        String encryptionSecret = chain.getEncryptionSecret();
        String fingerprintSecret = chain.getFingerprintSecret();
        if (!chain.isDurableEnabled()) {
            encryptionSecret = randomSecret();
            fingerprintSecret = randomSecret();
        } else {
            validateSecrets(encryptionSecret, fingerprintSecret);
        }
        return new SpecialistChainSecurity(
            objectMapper,
            encryptionSecret,
            fingerprintSecret
        );
    }

    @Bean
    @ConditionalOnBean({
        SpecialistChainRegistry.class,
        SpecialistChainSecurity.class
    })
    @ConditionalOnMissingBean(SpecialistChainPayloadCodec.class)
    public SpecialistChainPayloadCodec specialistChainPayloadCodec(
        ObjectMapper objectMapper,
        SpecialistChainRegistry chainRegistry,
        SpecialistChainSecurity security
    ) {
        return new SpecialistChainPayloadCodec(
            objectMapper,
            chainRegistry,
            security
        );
    }

    @Bean
    @ConditionalOnBean({
        SpecialistChainRegistry.class,
        SpecialistClientFactory.class,
        SpecialistDelegationGateway.class,
        SpecialistHandoffGateway.class,
        SpecialistChainPayloadCodec.class
    })
    @ConditionalOnMissingBean(SpecialistChainGateway.class)
    public SpecialistChainGateway specialistChainGateway(
        SpecialistChainRegistry chainRegistry,
        SpecialistClientFactory clientFactory,
        SpecialistDelegationGateway delegationGateway,
        SpecialistHandoffGateway handoffGateway,
        ObjectProvider<AIExecutionConversationRecorder> recorderProvider,
        ObjectProvider<SharedInteractiveTurnCoordinator>
            turnCoordinatorProvider,
        ObjectProvider<SpecialistChainExecutionRepository>
            repositoryProvider,
        SpecialistChainPayloadCodec codec,
        SpecialistChainSecurity security,
        @Qualifier("aiFabricExecutionTaskExecutor")
        AsyncTaskExecutor taskExecutor,
        CanonicalJsonSupport canonicalJson,
        Clock clock,
        SpecialistChainMetrics metrics,
        AIExecutionProperties properties
    ) {
        AIExecutionProperties.SpecialistChains chain =
            properties.getSpecialistChains();
        if (!chain.isDurableEnabled() && !chain.isAllowEphemeral()) {
            throw new IllegalStateException(
                "ai.execution.specialist-chains.enabled=true requires "
                    + "durable-enabled=true or an explicit "
                    + "allow-ephemeral=true acknowledgement"
            );
        }
        SpecialistChainExecutionRepository repository =
            repositoryProvider.getIfAvailable();
        if (repository == null) {
            throw new IllegalStateException(
                chain.isDurableEnabled()
                    ? "Durable specialist chains require a DataSource, "
                        + "Spring JDBC, and a "
                        + "SpecialistChainExecutionRepository"
                    : "Ephemeral specialist chains require "
                        + "allow-ephemeral=true or an application-provided "
                        + "SpecialistChainExecutionRepository"
            );
        }
        return new DefaultSpecialistChainGateway(
            chainRegistry,
            clientFactory,
            delegationGateway,
            handoffGateway,
            recorderProvider.getIfAvailable(),
            turnCoordinatorProvider.getIfAvailable(),
            repository,
            codec,
            security,
            taskExecutor,
            canonicalJson,
            clock,
            metrics,
            chain,
            chain.isDurableEnabled()
        );
    }

    private static void validateSecrets(
        String encryptionSecret,
        String fingerprintSecret
    ) {
        if (encryptionSecret == null
            || encryptionSecret.length() < 32
            || fingerprintSecret == null
            || fingerprintSecret.length() < 32
            || encryptionSecret.equals(fingerprintSecret)) {
            throw new IllegalStateException(
                "Durable specialist chains require two distinct secrets "
                    + "of at least 32 characters"
            );
        }
    }

    private static String randomSecret() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return HexFormat.of().formatHex(value);
    }
}
