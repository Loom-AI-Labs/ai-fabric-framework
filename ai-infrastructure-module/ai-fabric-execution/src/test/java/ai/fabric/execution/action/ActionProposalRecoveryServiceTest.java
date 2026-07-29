package ai.fabric.execution.action;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActionProposalRecoveryServiceTest {

    @Test
    void expiresConfirmableReceiptsAndMarksStaleExecutionUnknown() {
        ActionProposalTestFixture fixture = new ActionProposalTestFixture();
        Instant now = fixture.clock.instant();
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
            now.minus(Duration.ofMinutes(5))
        );
        fixture.repository.create(proposed);
        fixture.repository.create(confirmed);
        fixture.repository.create(executing);
        ActionProposalRecoveryService recovery =
            new ActionProposalRecoveryService(
                fixture.repository,
                fixture.security,
                ActionProposalMetrics.noop(),
                fixture.clock,
                Duration.ofMinutes(2),
                20
            );

        ActionProposalRecoveryService.RecoverySummary summary =
            recovery.recover();

        assertThat(summary.expiredProposals()).isEqualTo(2);
        assertThat(summary.unknownExecutions()).isEqualTo(1);
        assertThat(summary.deletedAfterRetention()).isZero();
        assertThat(status(fixture, proposed))
            .isEqualTo(ActionProposalReceiptStatus.EXPIRED);
        assertThat(status(fixture, confirmed))
            .isEqualTo(ActionProposalReceiptStatus.EXPIRED);
        ActionProposalReceipt unknown = fixture.repository
            .findById(executing.receiptId())
            .orElseThrow();
        assertThat(unknown.status())
            .isEqualTo(ActionProposalReceiptStatus.OUTCOME_UNKNOWN);
        assertThat(fixture.security.unprotect(
            unknown.protectedOutcome(),
            unknown.receiptId() + ":outcome"
        ))
            .containsEntry(
                "message",
                "The action may have completed, but its authoritative outcome is not yet known."
            );
    }

    @Test
    void cleanupDeletesOnlyOldReconciledTerminalReceipts() {
        ActionProposalTestFixture fixture = new ActionProposalTestFixture();
        Instant now = fixture.clock.instant();
        ActionProposalReceipt oldSucceeded =
            ActionProposalTestFixture.receipt(
                ActionProposalReceiptStatus.SUCCEEDED,
                now.minus(Duration.ofDays(45))
            );
        ActionProposalReceipt recentFailed =
            ActionProposalTestFixture.receipt(
                ActionProposalReceiptStatus.FAILED,
                now.minus(Duration.ofDays(5))
            );
        ActionProposalReceipt unknown =
            ActionProposalTestFixture.receipt(
                ActionProposalReceiptStatus.OUTCOME_UNKNOWN,
                now.minus(Duration.ofDays(45))
            );
        fixture.repository.create(oldSucceeded);
        fixture.repository.create(recentFailed);
        fixture.repository.create(unknown);
        ActionProposalRecoveryService recovery =
            new ActionProposalRecoveryService(
                fixture.repository,
                fixture.security,
                ActionProposalMetrics.noop(),
                fixture.clock,
                Duration.ofMinutes(2),
                20,
                true,
                Duration.ofDays(30)
            );

        ActionProposalRecoveryService.RecoverySummary summary =
            recovery.recover();

        assertThat(summary.deletedAfterRetention()).isEqualTo(1);
        assertThat(fixture.repository.findById(oldSucceeded.receiptId()))
            .isEmpty();
        assertThat(fixture.repository.findById(recentFailed.receiptId()))
            .contains(recentFailed);
        assertThat(fixture.repository.findById(unknown.receiptId()))
            .contains(unknown);
    }

    private ActionProposalReceiptStatus status(
        ActionProposalTestFixture fixture,
        ActionProposalReceipt receipt
    ) {
        return fixture.repository.findById(receipt.receiptId())
            .orElseThrow()
            .status();
    }

    private ActionProposalReceipt expiredReceipt(
        String id,
        ActionProposalReceiptStatus status,
        Instant now
    ) {
        Instant created = now.minus(Duration.ofMinutes(20));
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
            List.of(),
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
