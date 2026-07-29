package ai.fabric.execution.config;

import ai.fabric.execution.action.ActionProposalCoordinator;
import ai.fabric.execution.action.ActionProposalReceiptRepository;
import ai.fabric.execution.action.ActionProposalSecurity;
import ai.fabric.execution.review.ReviewDecisionGateway;
import ai.fabric.execution.review.ReviewRecoveryService;
import ai.fabric.execution.review.ReviewSecurity;
import ai.fabric.execution.review.auth.ReviewerAuthorizer;
import ai.fabric.execution.review.auth.ReviewerAuthorizerRegistry;
import ai.fabric.execution.review.continuation.ReviewCorrectionHandler;
import ai.fabric.execution.review.continuation.ReviewCorrectionHandlerRegistry;
import ai.fabric.execution.review.continuation.ReviewInformationHandler;
import ai.fabric.execution.review.continuation.ReviewInformationHandlerRegistry;
import ai.fabric.execution.review.dispatch.ReviewTaskDispatcher;
import ai.fabric.execution.review.dispatch.ReviewTaskDispatcherRegistry;
import ai.fabric.execution.review.persistence.InMemoryReviewDispatchRepository;
import ai.fabric.execution.review.persistence.InMemoryReviewTaskRepository;
import ai.fabric.execution.review.persistence.ReviewDispatchRepository;
import ai.fabric.execution.review.persistence.ReviewTaskRepository;
import ai.fabric.execution.review.policy.DefaultReviewPolicyRegistry;
import ai.fabric.execution.review.policy.ReviewPolicyDefinition;
import ai.fabric.execution.review.policy.ReviewPolicyRegistry;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration(after = {
    AIExecutionAutoConfiguration.class,
    AIExecutionReceiptAutoConfiguration.class,
    AIExecutionJdbcReviewAutoConfiguration.class
})
@ConditionalOnProperty(
    prefix = "ai.execution.reviews",
    name = "enabled",
    havingValue = "true"
)
@ConditionalOnBean(ActionProposalCoordinator.class)
public class AIExecutionReviewAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
        prefix = "ai.execution.reviews",
        name = "repository",
        havingValue = "IN_MEMORY"
    )
    @ConditionalOnMissingBean(ReviewTaskRepository.class)
    public ReviewTaskRepository inMemoryReviewTaskRepository(
        AIExecutionProperties properties,
        Environment environment
    ) {
        assertInMemoryAllowed(properties, environment);
        return new InMemoryReviewTaskRepository();
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "ai.execution.reviews",
        name = "repository",
        havingValue = "IN_MEMORY"
    )
    @ConditionalOnMissingBean(ReviewDispatchRepository.class)
    public ReviewDispatchRepository inMemoryReviewDispatchRepository(
        AIExecutionProperties properties,
        Environment environment
    ) {
        assertInMemoryAllowed(properties, environment);
        return new InMemoryReviewDispatchRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReviewerAuthorizerRegistry reviewerAuthorizerRegistry(
        List<ReviewerAuthorizer> authorizers
    ) {
        return new ReviewerAuthorizerRegistry(authorizers);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReviewTaskDispatcherRegistry reviewTaskDispatcherRegistry(
        List<ReviewTaskDispatcher> dispatchers
    ) {
        return new ReviewTaskDispatcherRegistry(dispatchers);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReviewCorrectionHandlerRegistry reviewCorrectionHandlerRegistry(
        List<ReviewCorrectionHandler> handlers
    ) {
        return new ReviewCorrectionHandlerRegistry(handlers);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReviewInformationHandlerRegistry
        reviewInformationHandlerRegistry(
            List<ReviewInformationHandler> handlers
        ) {
        return new ReviewInformationHandlerRegistry(handlers);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReviewPolicyRegistry reviewPolicyRegistry(
        List<ReviewPolicyDefinition> policies,
        SpecialistJsonSchemaRegistry schemaRegistry,
        CanonicalJsonSupport canonicalJson,
        ReviewerAuthorizerRegistry authorizers,
        ReviewTaskDispatcherRegistry dispatchers,
        ReviewCorrectionHandlerRegistry correctionHandlers,
        ReviewInformationHandlerRegistry informationHandlers
    ) {
        return new DefaultReviewPolicyRegistry(
            policies,
            schemaRegistry,
            canonicalJson,
            authorizers,
            dispatchers,
            correctionHandlers,
            informationHandlers
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ReviewSecurity reviewSecurity(
        ObjectMapper objectMapper,
        AIExecutionProperties properties
    ) {
        assertIndependentSecrets(properties);
        return new ReviewSecurity(
            objectMapper,
            properties.getReviews().getEncryptionSecret(),
            properties.getReviews().getFingerprintSecret()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ReviewDecisionGateway reviewDecisionGateway(
        ReviewTaskRepository taskRepository,
        ReviewDispatchRepository dispatchRepository,
        ReviewPolicyRegistry policyRegistry,
        ReviewerAuthorizerRegistry authorizers,
        ReviewTaskDispatcherRegistry dispatchers,
        ReviewCorrectionHandlerRegistry correctionHandlers,
        ReviewInformationHandlerRegistry informationHandlers,
        SpecialistJsonSchemaRegistry schemaRegistry,
        SpecialistJsonSchemaValidator schemaValidator,
        ActionProposalReceiptRepository receiptRepository,
        ActionProposalSecurity actionSecurity,
        ActionProposalCoordinator actionCoordinator,
        ReviewSecurity reviewSecurity,
        ObjectMapper objectMapper,
        Clock clock,
        AIExecutionProperties properties
    ) {
        return new ReviewDecisionGateway(
            taskRepository,
            dispatchRepository,
            policyRegistry,
            authorizers,
            dispatchers,
            correctionHandlers,
            informationHandlers,
            schemaRegistry,
            schemaValidator,
            receiptRepository,
            actionSecurity,
            actionCoordinator,
            reviewSecurity,
            objectMapper,
            clock,
            properties.getReviews().getDecisionLeaseDuration(),
            properties.getReviews().getMaxDispatchAttempts(),
            properties.getReviews().getMaxDecisionAttempts()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ReviewRecoveryService reviewRecoveryService(
        ReviewTaskRepository repository,
        ReviewDispatchRepository dispatchRepository,
        ReviewDecisionGateway gateway,
        Clock clock,
        AIExecutionProperties properties
    ) {
        return new ReviewRecoveryService(
            repository,
            dispatchRepository,
            gateway,
            clock,
            properties.getReviews().getRecoveryBatchSize(),
            properties.getReviews().isCleanupEnabled(),
            properties.getReviews().getRetention()
        );
    }

    private void assertInMemoryAllowed(
        AIExecutionProperties properties,
        Environment environment
    ) {
        boolean production = Arrays.stream(environment.getActiveProfiles())
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(value ->
                value.equals("prod") || value.equals("production")
            );
        if (production
            && !properties.getReviews().isAllowInProduction()) {
            throw new IllegalStateException(
                "IN_MEMORY review state is non-durable and intended for tests. "
                    + "Use repository=JDBC or explicitly acknowledge the risk."
            );
        }
    }

    private void assertIndependentSecrets(
        AIExecutionProperties properties
    ) {
        String reviewEncryption = normalized(
            properties.getReviews().getEncryptionSecret()
        );
        String reviewFingerprint = normalized(
            properties.getReviews().getFingerprintSecret()
        );
        String receiptEncryption = normalized(
            properties.getReceipts().getEncryptionSecret()
        );
        String receiptFingerprint = normalized(
            properties.getReceipts().getFingerprintSecret()
        );
        if (reviewEncryption != null
            && (reviewEncryption.equals(receiptEncryption)
                || reviewEncryption.equals(receiptFingerprint))
            || reviewFingerprint != null
                && (reviewFingerprint.equals(receiptEncryption)
                    || reviewFingerprint.equals(receiptFingerprint))) {
            throw new IllegalStateException(
                "Review secrets must be distinct from action receipt secrets"
            );
        }
    }

    private String normalized(String value) {
        return value == null ? null : value.trim();
    }
}
