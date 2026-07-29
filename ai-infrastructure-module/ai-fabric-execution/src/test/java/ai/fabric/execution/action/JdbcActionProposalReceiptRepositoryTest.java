package ai.fabric.execution.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcActionProposalReceiptRepositoryTest {

    @Test
    void receiptSurvivesRepositoryRestartAndTransitionsWithCas() {
        JdbcDataSource dataSource = dataSource();
        JdbcActionProposalReceiptRepository first =
            new JdbcActionProposalReceiptRepository(
                dataSource,
                new ObjectMapper(),
                true
            );
        ActionProposalReceipt proposed = ActionProposalTestFixture.receipt(
            ActionProposalReceiptStatus.PROPOSED,
            ActionProposalTestFixture.NOW
        );
        first.create(proposed);

        JdbcActionProposalReceiptRepository restarted =
            new JdbcActionProposalReceiptRepository(
                dataSource,
                new ObjectMapper(),
                false
            );
        ActionProposalReceipt restored = restarted
            .findById(proposed.receiptId())
            .orElseThrow();

        assertThat(restored).isEqualTo(proposed);
        ActionProposalReceipt confirmed = restored.confirmed(
            ActionProposalTestFixture.NOW.plusSeconds(1)
        );
        assertThat(restarted.compareAndSet(restored, confirmed)).isTrue();
        assertThat(restarted.compareAndSet(restored, confirmed)).isFalse();
        assertThat(restarted.findByIdempotencyKey(
            proposed.idempotencyKey()
        )).contains(confirmed);
    }

    @Test
    void enforcesReceiptAndIdempotencyUniqueness() {
        JdbcActionProposalReceiptRepository repository =
            new JdbcActionProposalReceiptRepository(
                dataSource(),
                new ObjectMapper(),
                true
            );
        ActionProposalReceipt receipt = ActionProposalTestFixture.receipt(
            ActionProposalReceiptStatus.PROPOSED,
            ActionProposalTestFixture.NOW
        );
        repository.create(receipt);

        assertThatThrownBy(() -> repository.create(receipt))
            .isInstanceOf(
                ActionProposalReceiptRepository.DuplicateReceiptException.class
            );
    }

    @Test
    void queriesExpiredConfirmedAndStaleExecutingReceipts() {
        JdbcActionProposalReceiptRepository repository =
            new JdbcActionProposalReceiptRepository(
                dataSource(),
                new ObjectMapper(),
                true
            );
        Instant now = ActionProposalTestFixture.NOW;
        ActionProposalReceipt proposed = expiredReceipt(
            "expired-proposed",
            ActionProposalReceiptStatus.PROPOSED,
            now
        );
        ActionProposalReceipt confirmed = expiredReceipt(
            "expired-confirmed",
            ActionProposalReceiptStatus.CONFIRMED,
            now
        );
        ActionProposalReceipt executing = ActionProposalTestFixture.receipt(
            ActionProposalReceiptStatus.EXECUTING,
            now.minusSeconds(300)
        );
        repository.create(proposed);
        repository.create(confirmed);
        repository.create(executing);

        assertThat(repository.findExpiredConfirmable(now, 10))
            .extracting(ActionProposalReceipt::receiptId)
            .containsExactlyInAnyOrder(
                proposed.receiptId(),
                confirmed.receiptId()
            );
        assertThat(repository.findUpdatedBefore(
            ActionProposalReceiptStatus.EXECUTING,
            now.minusSeconds(120),
            10
        ))
            .extracting(ActionProposalReceipt::receiptId)
            .containsExactly(executing.receiptId());
    }

    @Test
    void retentionQueryAndDeletePreserveUnknownAndRecentReceipts() {
        JdbcActionProposalReceiptRepository repository =
            new JdbcActionProposalReceiptRepository(
                dataSource(),
                new ObjectMapper(),
                true
            );
        Instant now = ActionProposalTestFixture.NOW;
        ActionProposalReceipt oldSucceeded =
            ActionProposalTestFixture.receipt(
                ActionProposalReceiptStatus.SUCCEEDED,
                now.minusSeconds(90 * 24 * 60 * 60L)
            );
        ActionProposalReceipt oldUnknown =
            ActionProposalTestFixture.receipt(
                ActionProposalReceiptStatus.OUTCOME_UNKNOWN,
                now.minusSeconds(90 * 24 * 60 * 60L)
            );
        ActionProposalReceipt recentFailed =
            ActionProposalTestFixture.receipt(
                ActionProposalReceiptStatus.FAILED,
                now.minusSeconds(5 * 24 * 60 * 60L)
            );
        repository.create(oldSucceeded);
        repository.create(oldUnknown);
        repository.create(recentFailed);

        assertThat(repository.findRetainableTerminalBefore(
            now.minusSeconds(30 * 24 * 60 * 60L),
            10
        ))
            .extracting(ActionProposalReceipt::receiptId)
            .containsExactly(oldSucceeded.receiptId());
        assertThat(repository.delete(oldSucceeded)).isTrue();
        assertThat(repository.delete(oldSucceeded)).isFalse();
        assertThat(repository.findById(oldSucceeded.receiptId())).isEmpty();
        assertThat(repository.findById(oldUnknown.receiptId()))
            .contains(oldUnknown);
        assertThat(repository.findById(recentFailed.receiptId()))
            .contains(recentFailed);
    }

    @Test
    void migratesLegacyReceiptSchemaAndMarksUnpinnedContent() {
        JdbcDataSource dataSource = dataSource();
        JdbcActionProposalReceiptRepository initial =
            new JdbcActionProposalReceiptRepository(
                dataSource,
                new ObjectMapper(),
                true
            );
        ActionProposalReceipt receipt = ActionProposalTestFixture.receipt(
            ActionProposalReceiptStatus.PROPOSED,
            ActionProposalTestFixture.NOW
        );
        initial.create(receipt);
        new JdbcTemplate(dataSource).execute(
            "ALTER TABLE ai_action_proposal_receipt"
                + " DROP COLUMN specialist_content_hash"
        );

        JdbcActionProposalReceiptRepository migrated =
            new JdbcActionProposalReceiptRepository(
                dataSource,
                new ObjectMapper(),
                true
            );

        assertThat(migrated.findById(receipt.receiptId()))
            .get()
            .extracting(ActionProposalReceipt::specialistContentHash)
            .isEqualTo("legacy-unpinned");
    }

    private JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:action-receipts-"
                + java.util.UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1"
        );
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private ActionProposalReceipt expiredReceipt(
        String id,
        ActionProposalReceiptStatus status,
        Instant now
    ) {
        Instant created = now.minusSeconds(600);
        return new ActionProposalReceipt(
            id,
            "invocation-" + id,
            ActionProposalTestFixture.SPECIALIST_ID,
            ActionProposalTestFixture.SPECIALIST_CONTENT_HASH,
            "profile-hash",
            "principal-fingerprint",
            "account",
            "subject-fingerprint",
            "tenant-fingerprint",
            "deployment-fingerprint",
            ActionProposalTestFixture.ACTION,
            "v1.protected-parameters",
            "parameter-hash",
            "schema-hash",
            "Update the billing address?",
            "idempotency-" + id,
            java.util.List.of(),
            status,
            created,
            now.minusSeconds(1),
            status == ActionProposalReceiptStatus.CONFIRMED
                ? created.plusSeconds(10)
                : null,
            null,
            null,
            null,
            null,
            null,
            now.minusSeconds(10),
            0
        );
    }
}
