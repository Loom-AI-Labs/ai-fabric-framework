package ai.fabric.execution.config;

import ai.fabric.execution.action.ActionOutcomeProjector;
import ai.fabric.execution.action.ActionOutcomeProjectorRegistry;
import ai.fabric.execution.action.ActionProposalCoordinator;
import ai.fabric.execution.action.ActionProposalMetrics;
import ai.fabric.execution.action.ActionProposalReceiptRepository;
import ai.fabric.execution.action.ActionProposalRecoveryService;
import ai.fabric.execution.action.ActionProposalSecurity;
import ai.fabric.execution.action.ActionProposalValidator;
import ai.fabric.execution.action.InMemoryActionProposalReceiptRepository;
import ai.fabric.execution.gateway.SpecialistCapabilityResolver;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.invocation.GovernedActionInvocationService;
import ai.fabric.intent.orchestration.pipeline.steps.OrchestrationPolicyResolutionStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration(after = {
    AIExecutionAutoConfiguration.class,
    AIExecutionJdbcReceiptAutoConfiguration.class
})
@ConditionalOnProperty(
    prefix = "ai.execution.receipts",
    name = "enabled",
    havingValue = "true"
)
public class AIExecutionReceiptAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
        prefix = "ai.execution.receipts",
        name = "repository",
        havingValue = "IN_MEMORY"
    )
    @ConditionalOnMissingBean(ActionProposalReceiptRepository.class)
    public ActionProposalReceiptRepository inMemoryActionProposalReceiptRepository(
        AIExecutionProperties properties,
        Environment environment
    ) {
        boolean productionProfile = Arrays.stream(
                environment.getActiveProfiles()
            )
            .filter(profile -> profile != null && !profile.isBlank())
            .map(profile -> profile.toLowerCase(Locale.ROOT))
            .anyMatch(profile ->
                profile.equals("prod") || profile.equals("production")
            );
        if (productionProfile
            && !properties.getReceipts().isAllowInProduction()) {
            throw new IllegalStateException(
                "IN_MEMORY action receipts are non-durable and intended for tests. "
                    + "Use repository=JDBC or set ai.execution.receipts.allow-in-production=true "
                    + "to explicitly acknowledge the risk."
            );
        }
        return new InMemoryActionProposalReceiptRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public ActionProposalSecurity actionProposalSecurity(
        ObjectMapper objectMapper,
        AIExecutionProperties properties
    ) {
        return new ActionProposalSecurity(
            objectMapper,
            properties.getReceipts().getEncryptionSecret(),
            properties.getReceipts().getFingerprintSecret()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ActionProposalValidator actionProposalValidator() {
        return new ActionProposalValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public ActionOutcomeProjectorRegistry actionOutcomeProjectorRegistry(
        List<ActionOutcomeProjector> projectors
    ) {
        return new ActionOutcomeProjectorRegistry(projectors);
    }

    @Bean
    @ConditionalOnMissingBean
    public ActionProposalCoordinator actionProposalCoordinator(
        ActionProposalReceiptRepository repository,
        ActionProposalSecurity security,
        ActionProposalValidator validator,
        ActionOutcomeProjectorRegistry projectors,
        SpecialistRegistry specialistRegistry,
        AIActionRegistry actionRegistry,
        OrchestrationPolicyResolutionStep policyResolutionStep,
        SpecialistCapabilityResolver capabilityResolver,
        GovernedActionInvocationService invocationService,
        ObjectProvider<ActionProposalMetrics> metrics,
        Clock clock,
        AIExecutionProperties properties
    ) {
        return new ActionProposalCoordinator(
            repository,
            security,
            validator,
            projectors,
            specialistRegistry,
            actionRegistry,
            policyResolutionStep,
            capabilityResolver,
            invocationService,
            metrics.getIfAvailable(ActionProposalMetrics::noop),
            clock,
            properties.getReceipts().getTtl()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ActionProposalRecoveryService actionProposalRecoveryService(
        ActionProposalReceiptRepository repository,
        ActionProposalSecurity security,
        ObjectProvider<ActionProposalMetrics> metrics,
        Clock clock,
        AIExecutionProperties properties
    ) {
        return new ActionProposalRecoveryService(
            repository,
            security,
            metrics.getIfAvailable(ActionProposalMetrics::noop),
            clock,
            properties.getReceipts().getStaleExecutingAfter(),
            properties.getReceipts().getRecoveryBatchSize(),
            properties.getReceipts().isCleanupEnabled(),
            properties.getReceipts().getRetention()
        );
    }
}
