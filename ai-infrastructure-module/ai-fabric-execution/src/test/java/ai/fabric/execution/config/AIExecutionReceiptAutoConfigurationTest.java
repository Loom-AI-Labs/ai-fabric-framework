package ai.fabric.execution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ai.fabric.execution.action.ActionProposalCoordinator;
import ai.fabric.execution.action.ActionProposalReceiptRepository;
import ai.fabric.execution.action.ActionProposalRecoveryService;
import ai.fabric.execution.action.ActionProposalSecurity;
import ai.fabric.execution.action.InMemoryActionProposalReceiptRepository;
import ai.fabric.execution.action.JdbcActionProposalReceiptRepository;
import ai.fabric.intent.action.invocation.GovernedActionInvocationService;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AIExecutionReceiptAutoConfigurationTest {

    private static final String ENCRYPTION_SECRET =
        "test-receipt-encryption-secret-at-least-32-characters";
    private static final String FINGERPRINT_SECRET =
        "test-receipt-fingerprint-secret-at-least-32-characters";

    private final ApplicationContextRunner contextRunner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                AIExecutionAutoConfiguration.class,
                AIExecutionJdbcReceiptAutoConfiguration.class,
                AIExecutionReceiptAutoConfiguration.class
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
    void receiptSupportIsAbsentByDefault() {
        contextRunner.run(context -> {
            assertThat(context)
                .doesNotHaveBean(ActionProposalReceiptRepository.class);
            assertThat(context).doesNotHaveBean(ActionProposalSecurity.class);
            assertThat(context)
                .doesNotHaveBean(ActionProposalCoordinator.class);
            assertThat(context)
                .doesNotHaveBean(ActionProposalRecoveryService.class);
        });
    }

    @Test
    void configuresInMemoryReceiptLifecycleWhenExplicitlyEnabled() {
        contextRunner
            .withPropertyValues(
                "ai.execution.receipts.enabled=true",
                "ai.execution.receipts.repository=IN_MEMORY",
                "ai.execution.receipts.encryption-secret="
                    + ENCRYPTION_SECRET,
                "ai.execution.receipts.fingerprint-secret="
                    + FINGERPRINT_SECRET
            )
            .run(context -> {
                assertThat(context)
                    .hasSingleBean(ActionProposalReceiptRepository.class);
                assertThat(context)
                    .getBean(ActionProposalReceiptRepository.class)
                    .isInstanceOf(
                        InMemoryActionProposalReceiptRepository.class
                    );
                assertThat(context).hasSingleBean(ActionProposalSecurity.class);
                assertThat(context)
                    .hasSingleBean(ActionProposalCoordinator.class);
                assertThat(context)
                    .hasSingleBean(ActionProposalRecoveryService.class);
            });
    }

    @Test
    void missingSecretsFailStartupInsteadOfDisablingReceipts() {
        contextRunner
            .withPropertyValues(
                "ai.execution.receipts.enabled=true",
                "ai.execution.receipts.repository=IN_MEMORY"
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
    void inMemoryReceiptsFailClosedUnderProductionProfile() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "ai.execution.receipts.enabled=true",
                "ai.execution.receipts.repository=IN_MEMORY",
                "ai.execution.receipts.encryption-secret="
                    + ENCRYPTION_SECRET,
                "ai.execution.receipts.fingerprint-secret="
                    + FINGERPRINT_SECRET
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage(
                        "IN_MEMORY action receipts are non-durable and intended for tests. "
                            + "Use repository=JDBC or set ai.execution.receipts.allow-in-production=true "
                            + "to explicitly acknowledge the risk."
                    );
            });
    }

    @Test
    void explicitProductionAcknowledgementAllowsInMemoryReceipts() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=production",
                "ai.execution.receipts.enabled=true",
                "ai.execution.receipts.repository=IN_MEMORY",
                "ai.execution.receipts.allow-in-production=true",
                "ai.execution.receipts.encryption-secret="
                    + ENCRYPTION_SECRET,
                "ai.execution.receipts.fingerprint-secret="
                    + FINGERPRINT_SECRET
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context)
                    .getBean(ActionProposalReceiptRepository.class)
                    .isInstanceOf(
                        InMemoryActionProposalReceiptRepository.class
                    );
            });
    }

    @Test
    void configuresJdbcRepositoryAndInitializesSchema() {
        contextRunner
            .withBean(DataSource.class, this::dataSource)
            .withPropertyValues(
                "ai.execution.receipts.enabled=true",
                "ai.execution.receipts.repository=JDBC",
                "ai.execution.receipts.encryption-secret="
                    + ENCRYPTION_SECRET,
                "ai.execution.receipts.fingerprint-secret="
                    + FINGERPRINT_SECRET
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context)
                    .getBean(ActionProposalReceiptRepository.class)
                    .isInstanceOf(
                        JdbcActionProposalReceiptRepository.class
                    );
            });
    }

    @Test
    void jdbcWithoutDataSourceFailsStartupVisibly() {
        contextRunner
            .withPropertyValues(
                "ai.execution.receipts.enabled=true",
                "ai.execution.receipts.repository=JDBC",
                "ai.execution.receipts.encryption-secret="
                    + ENCRYPTION_SECRET,
                "ai.execution.receipts.fingerprint-secret="
                    + FINGERPRINT_SECRET
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasMessageContaining(
                        "ActionProposalReceiptRepository"
                    );
            });
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:receipt-auto-configuration;"
                + "DB_CLOSE_DELAY=-1"
        );
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
