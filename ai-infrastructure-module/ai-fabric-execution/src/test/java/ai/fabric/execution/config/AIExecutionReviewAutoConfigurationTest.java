package ai.fabric.execution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ai.fabric.execution.review.ReviewDecisionGateway;
import ai.fabric.execution.review.ReviewRecoveryService;
import ai.fabric.execution.review.ReviewSecurity;
import ai.fabric.execution.review.persistence.InMemoryReviewDispatchRepository;
import ai.fabric.execution.review.persistence.InMemoryReviewTaskRepository;
import ai.fabric.execution.review.persistence.JdbcReviewDispatchRepository;
import ai.fabric.execution.review.persistence.JdbcReviewTaskRepository;
import ai.fabric.execution.review.persistence.ReviewDispatchRepository;
import ai.fabric.execution.review.persistence.ReviewTaskRepository;
import ai.fabric.execution.review.policy.ReviewPolicyRegistry;
import ai.fabric.intent.action.invocation.GovernedActionInvocationService;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AIExecutionReviewAutoConfigurationTest {

    private static final String RECEIPT_ENCRYPTION_SECRET =
        "receipt-encryption-secret-at-least-32-characters";
    private static final String RECEIPT_FINGERPRINT_SECRET =
        "receipt-fingerprint-secret-at-least-32-characters";
    private static final String REVIEW_ENCRYPTION_SECRET =
        "review-encryption-secret-at-least-32-characters";
    private static final String REVIEW_FINGERPRINT_SECRET =
        "review-fingerprint-secret-at-least-32-characters";

    private final ApplicationContextRunner contextRunner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                AIExecutionAutoConfiguration.class,
                AIExecutionJdbcReceiptAutoConfiguration.class,
                AIExecutionReceiptAutoConfiguration.class,
                AIExecutionJdbcReviewAutoConfiguration.class,
                AIExecutionReviewAutoConfiguration.class
            ))
            .withUserConfiguration(
                AIExecutionAutoConfigurationTest
                    .InfrastructureConfiguration.class
            )
            .withBean(
                GovernedActionInvocationService.class,
                () -> mock(GovernedActionInvocationService.class)
            );

    @Test
    void reviewSupportIsAbsentByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ReviewTaskRepository.class);
            assertThat(context)
                .doesNotHaveBean(ReviewDispatchRepository.class);
            assertThat(context).doesNotHaveBean(ReviewSecurity.class);
            assertThat(context)
                .doesNotHaveBean(ReviewDecisionGateway.class);
            assertThat(context)
                .doesNotHaveBean(ReviewRecoveryService.class);
        });
    }

    @Test
    void configuresExplicitInMemoryReviewLifecycle() {
        contextRunner
            .withPropertyValues(inMemoryProperties())
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context)
                    .getBean(ReviewTaskRepository.class)
                    .isInstanceOf(InMemoryReviewTaskRepository.class);
                assertThat(context)
                    .getBean(ReviewDispatchRepository.class)
                    .isInstanceOf(
                        InMemoryReviewDispatchRepository.class
                    );
                assertThat(context).hasSingleBean(ReviewSecurity.class);
                assertThat(context)
                    .hasSingleBean(ReviewPolicyRegistry.class);
                assertThat(context)
                    .hasSingleBean(ReviewDecisionGateway.class);
                assertThat(context)
                    .hasSingleBean(ReviewRecoveryService.class);
            });
    }

    @Test
    void missingReviewSecretsFailStartupVisibly() {
        contextRunner
            .withPropertyValues(
                receiptProperties()
            )
            .withPropertyValues(
                "ai.execution.reviews.enabled=true",
                "ai.execution.reviews.repository=IN_MEMORY"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage(
                        "encryptionSecret must contain at least 32 characters"
                    );
            });
    }

    @Test
    void reviewSecretsCannotReuseActionReceiptSecrets() {
        contextRunner
            .withPropertyValues(
                receiptProperties()
            )
            .withPropertyValues(
                "ai.execution.reviews.enabled=true",
                "ai.execution.reviews.repository=IN_MEMORY",
                "ai.execution.reviews.encryption-secret="
                    + RECEIPT_ENCRYPTION_SECRET,
                "ai.execution.reviews.fingerprint-secret="
                    + REVIEW_FINGERPRINT_SECRET
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage(
                        "Review secrets must be distinct from action receipt secrets"
                    );
            });
    }

    @Test
    void inMemoryReviewsFailClosedUnderProductionProfile() {
        contextRunner
            .withPropertyValues(inMemoryProperties())
            .withPropertyValues("spring.profiles.active=prod")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage(
                        "IN_MEMORY review state is non-durable and intended for tests. "
                            + "Use repository=JDBC or explicitly acknowledge the risk."
                    );
            });
    }

    @Test
    void explicitAcknowledgementAllowsInMemoryReviewsInProduction() {
        contextRunner
            .withPropertyValues(inMemoryProperties())
            .withPropertyValues(
                "spring.profiles.active=production",
                "ai.execution.reviews.allow-in-production=true"
            )
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void configuresJdbcReviewRepositoriesAndInitializesSchema() {
        contextRunner
            .withBean(DataSource.class, this::dataSource)
            .withPropertyValues(
                receiptProperties()
            )
            .withPropertyValues(
                "ai.execution.reviews.enabled=true",
                "ai.execution.reviews.repository=JDBC",
                "ai.execution.reviews.initialize-schema=true",
                "ai.execution.reviews.encryption-secret="
                    + REVIEW_ENCRYPTION_SECRET,
                "ai.execution.reviews.fingerprint-secret="
                    + REVIEW_FINGERPRINT_SECRET
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context)
                    .getBean(ReviewTaskRepository.class)
                    .isInstanceOf(JdbcReviewTaskRepository.class);
                assertThat(context)
                    .getBean(ReviewDispatchRepository.class)
                    .isInstanceOf(JdbcReviewDispatchRepository.class);
            });
    }

    @Test
    void jdbcWithoutDataSourceFailsStartupVisibly() {
        contextRunner
            .withPropertyValues(
                receiptProperties()
            )
            .withPropertyValues(
                "ai.execution.reviews.enabled=true",
                "ai.execution.reviews.repository=JDBC",
                "ai.execution.reviews.encryption-secret="
                    + REVIEW_ENCRYPTION_SECRET,
                "ai.execution.reviews.fingerprint-secret="
                    + REVIEW_FINGERPRINT_SECRET
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasMessageContaining("ReviewTaskRepository");
            });
    }

    private String[] inMemoryProperties() {
        return concat(
            receiptProperties(),
            new String[] {
                "ai.execution.reviews.enabled=true",
                "ai.execution.reviews.repository=IN_MEMORY",
                "ai.execution.reviews.encryption-secret="
                    + REVIEW_ENCRYPTION_SECRET,
                "ai.execution.reviews.fingerprint-secret="
                    + REVIEW_FINGERPRINT_SECRET
            }
        );
    }

    private String[] receiptProperties() {
        return new String[] {
            "ai.execution.receipts.enabled=true",
            "ai.execution.receipts.repository=IN_MEMORY",
            "ai.execution.receipts.allow-in-production=true",
            "ai.execution.receipts.encryption-secret="
                + RECEIPT_ENCRYPTION_SECRET,
            "ai.execution.receipts.fingerprint-secret="
                + RECEIPT_FINGERPRINT_SECRET
        };
    }

    private String[] concat(String[] first, String[] second) {
        String[] combined = new String[first.length + second.length];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(
            second,
            0,
            combined,
            first.length,
            second.length
        );
        return combined;
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:review-auto-configuration-"
                + java.util.UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1"
        );
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
