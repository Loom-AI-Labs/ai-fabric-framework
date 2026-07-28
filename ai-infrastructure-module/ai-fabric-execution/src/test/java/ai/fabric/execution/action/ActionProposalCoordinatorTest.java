package ai.fabric.execution.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.SpecialistAuthority;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.invocation.ActionInvocationFailure;
import ai.fabric.intent.action.invocation.ActionProposalCandidate;
import ai.fabric.intent.action.invocation.GovernedActionInvocationOutcome;
import ai.fabric.intent.action.invocation.GovernedActionInvocationStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ActionProposalCoordinatorTest {

    @Test
    void proposalIsIdentityBoundProtectedAndExecutedOnlyAfterConfirmation() {
        ActionProposalTestFixture fixture = new ActionProposalTestFixture();

        ActionProposalView proposal = fixture.propose();
        ActionProposalReceipt stored = fixture.repository
            .findById(proposal.receiptId())
            .orElseThrow();

        assertThat(proposal.status())
            .isEqualTo(ActionProposalReceiptStatus.PROPOSED);
        assertThat(fixture.confirmedInvocations).hasValue(0);
        assertThat(fixture.preflightSubject).hasValue("account-1");
        assertThat(stored.protectedParameters())
            .doesNotContain("1 Main Street", "SW1A 1AA");
        assertThat(stored.idempotencyKey())
            .doesNotContain("public-idempotency-key");
        assertThat(stored.toString())
            .doesNotContain(
                stored.protectedParameters(),
                stored.principalFingerprint(),
                "1 Main Street"
            );

        ActionProposalDecisionResult result = fixture.coordinator.decide(
            new ActionProposalDecisionRequest(
                proposal.receiptId(),
                ActionProposalDecision.CONFIRM
            ),
            fixture.trustedContext
        );

        assertThat(result.status())
            .isEqualTo(ActionProposalReceiptStatus.SUCCEEDED);
        assertThat(result.outcome().data())
            .containsEntry("updated", true)
            .containsEntry("addressType", "BILLING")
            .doesNotContainKeys("subscriptionId", "streetAddress");
        assertThat(fixture.confirmedInvocations).hasValue(1);

        ActionProposalDecisionResult replay = fixture.coordinator.decide(
            new ActionProposalDecisionRequest(
                proposal.receiptId(),
                ActionProposalDecision.CONFIRM
            ),
            fixture.trustedContext
        );

        assertThat(replay).isEqualTo(result);
        assertThat(fixture.confirmedInvocations).hasValue(1);
    }

    @Test
    void rejectsOversizedApplicationConfirmationBeforePersistence() {
        ActionProposalTestFixture fixture = new ActionProposalTestFixture();
        ActionResult result = ActionResult.builder()
            .success(false)
            .message("x".repeat(1001))
            .errorCode("CONFIRMATION_REQUIRED")
            .build();
        fixture.preflightOutcome.set(new GovernedActionInvocationOutcome(
            GovernedActionInvocationStatus.CONFIRMATION_REQUIRED,
            result,
            new ActionInvocationFailure(
                "CONFIRMATION_REQUIRED",
                result.getMessage(),
                false
            )
        ));

        assertThatThrownBy(fixture::propose)
            .isInstanceOf(ActionProposalValidationException.class)
            .extracting(error ->
                ((ActionProposalValidationException) error).reason()
            )
            .isEqualTo("ACTION_CONFIRMATION_MESSAGE_INVALID");
        assertThat(fixture.confirmedInvocations).hasValue(0);
    }

    @Test
    void rejectionIsTerminalAndCannotExecute() {
        ActionProposalTestFixture fixture = new ActionProposalTestFixture();
        ActionProposalView proposal = fixture.propose();

        ActionProposalDecisionResult rejected = fixture.coordinator.decide(
            new ActionProposalDecisionRequest(
                proposal.receiptId(),
                ActionProposalDecision.REJECT
            ),
            fixture.trustedContext
        );
        ActionProposalDecisionResult replay = fixture.coordinator.decide(
            new ActionProposalDecisionRequest(
                proposal.receiptId(),
                ActionProposalDecision.CONFIRM
            ),
            fixture.trustedContext
        );

        assertThat(rejected.status())
            .isEqualTo(ActionProposalReceiptStatus.REJECTED);
        assertThat(replay.status())
            .isEqualTo(ActionProposalReceiptStatus.REJECTED);
        assertThat(fixture.confirmedInvocations).hasValue(0);
    }

    @Test
    void expiredProposedAndConfirmedReceiptsCannotExecute() {
        ActionProposalTestFixture proposedFixture =
            new ActionProposalTestFixture();
        ActionProposalView proposed = proposedFixture.propose();
        proposedFixture.clock.advance(Duration.ofMinutes(11));

        ActionProposalDecisionResult expiredProposed =
            proposedFixture.coordinator.decide(
                new ActionProposalDecisionRequest(
                    proposed.receiptId(),
                    ActionProposalDecision.CONFIRM
                ),
                proposedFixture.trustedContext
            );

        ActionProposalTestFixture confirmedFixture =
            new ActionProposalTestFixture();
        ActionProposalView confirmedView = confirmedFixture.propose();
        ActionProposalReceipt receipt = confirmedFixture.repository
            .findById(confirmedView.receiptId())
            .orElseThrow();
        assertThat(confirmedFixture.repository.compareAndSet(
            receipt,
            receipt.confirmed(confirmedFixture.clock.instant())
        )).isTrue();
        confirmedFixture.clock.advance(Duration.ofMinutes(11));

        ActionProposalDecisionResult expiredConfirmed =
            confirmedFixture.coordinator.decide(
                new ActionProposalDecisionRequest(
                    confirmedView.receiptId(),
                    ActionProposalDecision.CONFIRM
                ),
                confirmedFixture.trustedContext
            );

        assertThat(expiredProposed.status())
            .isEqualTo(ActionProposalReceiptStatus.EXPIRED);
        assertThat(expiredConfirmed.status())
            .isEqualTo(ActionProposalReceiptStatus.EXPIRED);
        assertThat(proposedFixture.confirmedInvocations).hasValue(0);
        assertThat(confirmedFixture.confirmedInvocations).hasValue(0);
    }

    @Test
    void crossPrincipalSubjectAndTenantConfirmationAreIndistinguishable() {
        ActionProposalTestFixture fixture = new ActionProposalTestFixture();
        ActionProposalView proposal = fixture.propose();
        List<TrustedExecutionContext> attackers = List.of(
            fixture.trusted("principal-2", "account-1", "tenant-1"),
            fixture.trusted("principal-1", "account-2", "tenant-1"),
            fixture.trusted("principal-1", "account-1", "tenant-2")
        );

        for (TrustedExecutionContext attacker : attackers) {
            ActionProposalDecisionResult result = fixture.coordinator.decide(
                new ActionProposalDecisionRequest(
                    proposal.receiptId(),
                    ActionProposalDecision.CONFIRM
                ),
                attacker
            );
            assertThat(result.status()).isNull();
            assertThat(result.failure().reason())
                .isEqualTo("RECEIPT_NOT_AVAILABLE");
        }
        assertThat(fixture.confirmedInvocations).hasValue(0);
    }

    @Test
    void revokedAuthorityFailsBeforeExecution() {
        ActionProposalTestFixture fixture = new ActionProposalTestFixture();
        ActionProposalView proposal = fixture.propose();
        fixture.authority.set(new SpecialistAuthority(Set.of(), Set.of()));

        ActionProposalDecisionResult result = fixture.coordinator.decide(
            new ActionProposalDecisionRequest(
                proposal.receiptId(),
                ActionProposalDecision.CONFIRM
            ),
            fixture.trustedContext
        );

        assertThat(result.status())
            .isEqualTo(ActionProposalReceiptStatus.FAILED);
        assertThat(result.failure().reason())
            .isEqualTo("ACTION_AUTHORITY_INTERSECTION_FAILED");
        assertThat(fixture.confirmedInvocations).hasValue(0);
    }

    @Test
    void protectedParameterTamperingFailsBeforeExecution() {
        ActionProposalTestFixture fixture = new ActionProposalTestFixture();
        ActionProposalView proposal = fixture.propose();
        ActionProposalReceipt receipt = fixture.repository
            .findById(proposal.receiptId())
            .orElseThrow();
        ActionProposalReceipt tampered = copyWithProtectedParameters(
            receipt,
            receipt.protectedParameters() + "x"
        );
        assertThat(fixture.repository.compareAndSet(receipt, tampered)).isTrue();

        ActionProposalDecisionResult result = fixture.coordinator.decide(
            new ActionProposalDecisionRequest(
                proposal.receiptId(),
                ActionProposalDecision.CONFIRM
            ),
            fixture.trustedContext
        );

        assertThat(result.status())
            .isEqualTo(ActionProposalReceiptStatus.FAILED);
        assertThat(result.failure().reason())
            .isEqualTo("ACTION_CONFIRMATION_VALIDATION_FAILED");
        assertThat(fixture.confirmedInvocations).hasValue(0);
    }

    @Test
    void sameScopedIdempotencyKeyReplaysAndDifferentIdentityDoesNotCollide() {
        ActionProposalTestFixture fixture = new ActionProposalTestFixture();

        ActionProposalView first = fixture.propose("same-key");
        ActionProposalView replay = fixture.propose("same-key");

        assertThat(replay.receiptId()).isEqualTo(first.receiptId());

        TrustedExecutionContext other = fixture.trusted(
            "principal-2",
            "account-2",
            "tenant-2"
        );
        ActionProposalView independent = fixture.coordinator.propose(
            "invocation-2",
            fixture.definition,
            fixture.candidate(),
            other,
            fixture.resolveProfile(other),
            "same-key",
            List.of()
        );

        assertThat(independent.receiptId()).isNotEqualTo(first.receiptId());
    }

    @Test
    void conflictingUseOfScopedIdempotencyKeyIsDenied() {
        ActionProposalTestFixture fixture = new ActionProposalTestFixture();
        fixture.propose("same-key");
        ActionProposalCandidate changed = new ActionProposalCandidate(
            ActionProposalTestFixture.ACTION,
            Map.of(
                "addressType",
                "SHIPPING",
                "streetAddress",
                "2 Main Street",
                "city",
                "London",
                "state",
                "London",
                "postalCode",
                "SW1A 1AA",
                "country",
                "GB"
            ),
            fixture.candidate().actionContext()
        );

        assertThatThrownBy(() -> fixture.coordinator.propose(
            "invocation-2",
            fixture.definition,
            changed,
            fixture.trustedContext,
            fixture.effectiveProfile,
            "same-key",
            List.of()
        ))
            .isInstanceOf(ActionProposalValidationException.class)
            .extracting(error ->
                ((ActionProposalValidationException) error).reason()
            )
            .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
    }

    @Test
    void concurrentConfirmationsExecuteAtMostOnce() throws Exception {
        ActionProposalTestFixture fixture = new ActionProposalTestFixture();
        ActionProposalView proposal = fixture.propose();
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<ActionProposalDecisionResult>> tasks =
                new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                tasks.add(() -> fixture.coordinator.decide(
                    new ActionProposalDecisionRequest(
                        proposal.receiptId(),
                        ActionProposalDecision.CONFIRM
                    ),
                    fixture.trustedContext
                ));
            }
            executor.invokeAll(tasks).forEach(future -> {
                try {
                    assertThat(future.get().status()).isIn(
                        ActionProposalReceiptStatus.EXECUTING,
                        ActionProposalReceiptStatus.SUCCEEDED
                    );
                } catch (Exception ex) {
                    throw new AssertionError(ex);
                }
            });
        } finally {
            executor.shutdownNow();
        }

        assertThat(fixture.confirmedInvocations).hasValue(1);
        assertThat(fixture.repository.findById(proposal.receiptId()))
            .get()
            .extracting(ActionProposalReceipt::status)
            .isEqualTo(ActionProposalReceiptStatus.SUCCEEDED);
    }

    @Test
    void unknownOutcomeIsNotRetriedAndCanBeReconciled() {
        ActionProposalTestFixture fixture = new ActionProposalTestFixture();
        ActionResult unknownResult = ActionResult.builder()
            .success(false)
            .message("Connection lost after invocation.")
            .errorCode("WRITE_OUTCOME_UNKNOWN")
            .build();
        fixture.confirmedOutcome.set(new GovernedActionInvocationOutcome(
            GovernedActionInvocationStatus.OUTCOME_UNKNOWN,
            unknownResult,
            new ActionInvocationFailure(
                "WRITE_OUTCOME_UNKNOWN",
                unknownResult.getMessage(),
                false
            )
        ));
        ActionProposalView proposal = fixture.propose();

        ActionProposalDecisionResult unknown = fixture.coordinator.decide(
            new ActionProposalDecisionRequest(
                proposal.receiptId(),
                ActionProposalDecision.CONFIRM
            ),
            fixture.trustedContext
        );
        ActionProposalDecisionResult replay = fixture.coordinator.decide(
            new ActionProposalDecisionRequest(
                proposal.receiptId(),
                ActionProposalDecision.CONFIRM
            ),
            fixture.trustedContext
        );

        assertThat(unknown.status())
            .isEqualTo(ActionProposalReceiptStatus.OUTCOME_UNKNOWN);
        assertThat(replay.status())
            .isEqualTo(ActionProposalReceiptStatus.OUTCOME_UNKNOWN);
        assertThat(fixture.confirmedInvocations).hasValue(1);

        ActionProposalDecisionResult reconciled =
            fixture.coordinator.reconcile(
                new ActionProposalReconciliation(
                    proposal.receiptId(),
                    ActionProposalReceiptStatus.SUCCEEDED,
                    ActionResult.builder()
                        .success(true)
                        .message("Authoritatively verified.")
                        .build()
                ),
                fixture.trustedContext
            );

        assertThat(reconciled.status())
            .isEqualTo(ActionProposalReceiptStatus.SUCCEEDED);
        assertThat(reconciled.outcome().data())
            .containsEntry("updated", true)
            .doesNotContainKeys("subscriptionId", "streetAddress");
        assertThat(fixture.confirmedInvocations).hasValue(1);
    }

    @Test
    void corruptedPersistedOutcomeFailsVisiblyInsteadOfReportingSuccess() {
        ActionProposalTestFixture fixture = new ActionProposalTestFixture();
        ActionProposalView proposal = fixture.propose();
        ActionProposalDecisionResult completed = fixture.coordinator.decide(
            new ActionProposalDecisionRequest(
                proposal.receiptId(),
                ActionProposalDecision.CONFIRM
            ),
            fixture.trustedContext
        );
        assertThat(completed.succeeded()).isTrue();
        ActionProposalReceipt stored = fixture.repository
            .findById(proposal.receiptId())
            .orElseThrow();
        ActionProposalReceipt corrupted = new ActionProposalReceipt(
            stored.receiptId(),
            stored.invocationId(),
            stored.specialistId(),
            stored.effectiveProfileHash(),
            stored.principalFingerprint(),
            stored.subjectType(),
            stored.subjectFingerprint(),
            stored.tenantFingerprint(),
            stored.deploymentFingerprint(),
            stored.actionName(),
            stored.protectedParameters(),
            stored.parameterHash(),
            stored.parameterSchemaHash(),
            stored.confirmationMessage(),
            stored.idempotencyKey(),
            stored.evidenceHashes(),
            stored.status(),
            stored.createdAt(),
            stored.expiresAt(),
            stored.confirmedAt(),
            stored.executionStartedAt(),
            stored.executedAt(),
            stored.terminalAt(),
            "v1.corrupted",
            stored.failureReason(),
            fixture.clock.instant().plusSeconds(1),
            stored.version() + 1
        );
        assertThat(fixture.repository.compareAndSet(stored, corrupted)).isTrue();

        ActionProposalDecisionResult replay = fixture.coordinator.decide(
            new ActionProposalDecisionRequest(
                proposal.receiptId(),
                ActionProposalDecision.CONFIRM
            ),
            fixture.trustedContext
        );

        assertThat(replay.status())
            .isEqualTo(ActionProposalReceiptStatus.OUTCOME_UNKNOWN);
        assertThat(replay.succeeded()).isFalse();
        assertThat(replay.failure().reason())
            .isEqualTo("ACTION_OUTCOME_UNAVAILABLE");
        assertThat(replay.outcome().data())
            .containsEntry("requiresReconciliation", true);
        assertThat(fixture.confirmedInvocations).hasValue(1);
    }

    @Test
    void rejectsAProposalBuiltAgainstAStaleEffectiveProfile() {
        ActionProposalTestFixture fixture = new ActionProposalTestFixture();
        var current = fixture.effectiveProfile;
        var stale = new ai.fabric.intent.orchestration.capability
            .EffectiveCapabilityProfile(
                current.profile(),
                current.mode(),
                current.retrievalEnabled(),
                current.effectiveVectorSpaces(),
                current.visibleActions(),
                current.executableReadActions(),
                current.proposableWriteActions(),
                current.ragBudgets(),
                current.readActionResolutionPolicy(),
                "stale-profile-hash"
            );

        assertThatThrownBy(() -> fixture.coordinator.propose(
            "invocation-stale-profile",
            fixture.definition,
            fixture.candidate(),
            fixture.trustedContext,
            stale,
            "stale-profile-key",
            List.of()
        ))
            .isInstanceOf(ActionProposalValidationException.class)
            .extracting(error ->
                ((ActionProposalValidationException) error).reason()
            )
            .isEqualTo("EFFECTIVE_PROFILE_CHANGED");
        assertThat(fixture.confirmedInvocations).hasValue(0);
    }

    @Test
    void removedSpecialistVersionFailsBeforeExecution() {
        ActionProposalTestFixture fixture = new ActionProposalTestFixture();
        ActionProposalView proposal = fixture.propose();
        org.mockito.Mockito.when(fixture.specialistRegistry.find(
            ActionProposalTestFixture.SPECIALIST_ID
        )).thenReturn(java.util.Optional.empty());

        ActionProposalDecisionResult result = fixture.coordinator.decide(
            new ActionProposalDecisionRequest(
                proposal.receiptId(),
                ActionProposalDecision.CONFIRM
            ),
            fixture.trustedContext
        );

        assertThat(result.status())
            .isEqualTo(ActionProposalReceiptStatus.FAILED);
        assertThat(result.failure().reason())
            .isEqualTo("SPECIALIST_VERSION_NOT_REGISTERED");
        assertThat(fixture.confirmedInvocations).hasValue(0);
    }

    @Test
    void changedActionSchemaFailsBeforeExecution() {
        ActionProposalTestFixture fixture = new ActionProposalTestFixture();
        ActionProposalView proposal = fixture.propose();
        AIActionMetaData changedMetadata = AIActionMetaData.builder()
            .name(ActionProposalTestFixture.ACTION)
            .accessMode(ActionAccessMode.WRITE_ONLY)
            .confirmationRequired(true)
            .parameterSchemas(Map.of(
                "newField",
                AIActionParamSchema.builder()
                    .name("newField")
                    .type(AIActionParamType.STRING)
                    .build()
            ))
            .build();
        org.mockito.Mockito.when(fixture.actionRegistry.findMetadata(
            ActionProposalTestFixture.ACTION
        )).thenReturn(java.util.Optional.of(changedMetadata));

        ActionProposalDecisionResult result = fixture.coordinator.decide(
            new ActionProposalDecisionRequest(
                proposal.receiptId(),
                ActionProposalDecision.CONFIRM
            ),
            fixture.trustedContext
        );

        assertThat(result.status())
            .isEqualTo(ActionProposalReceiptStatus.FAILED);
        assertThat(result.failure().reason())
            .isEqualTo("ACTION_SCHEMA_CHANGED");
        assertThat(fixture.confirmedInvocations).hasValue(0);
    }

    @Test
    void handlerFailureIsPersistedAndProjectedWithoutRawState() {
        ActionProposalTestFixture fixture = new ActionProposalTestFixture();
        ActionResult failedResult = ActionResult.builder()
            .success(false)
            .message("Address service rejected the update.")
            .errorCode("ADDRESS_REJECTED")
            .build();
        fixture.confirmedOutcome.set(new GovernedActionInvocationOutcome(
            GovernedActionInvocationStatus.FAILED,
            failedResult,
            new ActionInvocationFailure(
                "ADDRESS_REJECTED",
                failedResult.getMessage(),
                false
            )
        ));
        ActionProposalView proposal = fixture.propose();

        ActionProposalDecisionResult result = fixture.coordinator.decide(
            new ActionProposalDecisionRequest(
                proposal.receiptId(),
                ActionProposalDecision.CONFIRM
            ),
            fixture.trustedContext
        );

        assertThat(result.status())
            .isEqualTo(ActionProposalReceiptStatus.FAILED);
        assertThat(result.failure().reason()).isEqualTo("ADDRESS_REJECTED");
        assertThat(result.outcome().data())
            .containsEntry("updated", false)
            .doesNotContainKeys("subscriptionId", "streetAddress");
        assertThat(fixture.repository.findById(proposal.receiptId()))
            .get()
            .extracting(ActionProposalReceipt::status)
            .isEqualTo(ActionProposalReceiptStatus.FAILED);
        assertThat(fixture.confirmedInvocations).hasValue(1);
    }

    @Test
    void proposalStoreFailureFailsClosedWithoutReturningAReceipt() {
        InMemoryActionProposalReceiptRepository durableStore =
            new InMemoryActionProposalReceiptRepository();
        FaultInjectingRepository failingRepository =
            new FaultInjectingRepository(durableStore);
        ActionProposalTestFixture fixture =
            new ActionProposalTestFixture(failingRepository);
        failingRepository.failIdempotencyReads();

        assertThatThrownBy(fixture::propose)
            .isInstanceOf(ActionProposalPersistenceException.class)
            .extracting(error ->
                ((ActionProposalPersistenceException) error).reason()
            )
            .isEqualTo("ACTION_RECEIPT_PERSISTENCE_FAILED");
        assertThat(fixture.confirmedInvocations).hasValue(0);
    }

    @Test
    void confirmationStoreReadFailureIsVisibleAndDoesNotExecute() {
        InMemoryActionProposalReceiptRepository durableStore =
            new InMemoryActionProposalReceiptRepository();
        FaultInjectingRepository failingRepository =
            new FaultInjectingRepository(durableStore);
        ActionProposalTestFixture fixture =
            new ActionProposalTestFixture(failingRepository);
        ActionProposalView proposal = fixture.propose();
        failingRepository.failReads();

        ActionProposalDecisionResult result = fixture.coordinator.decide(
            new ActionProposalDecisionRequest(
                proposal.receiptId(),
                ActionProposalDecision.CONFIRM
            ),
            fixture.trustedContext
        );

        assertThat(result.status()).isNull();
        assertThat(result.failure().reason())
            .isEqualTo("RECEIPT_STORE_UNAVAILABLE");
        assertThat(result.failure().retryable()).isTrue();
        assertThat(fixture.confirmedInvocations).hasValue(0);
        assertThat(durableStore.findById(proposal.receiptId()))
            .get()
            .extracting(ActionProposalReceipt::status)
            .isEqualTo(ActionProposalReceiptStatus.PROPOSED);
    }

    @Test
    void transitionRaceReloadFailureIsVisibleAndDoesNotExecute() {
        InMemoryActionProposalReceiptRepository durableStore =
            new InMemoryActionProposalReceiptRepository();
        FaultInjectingRepository failingRepository =
            new FaultInjectingRepository(durableStore);
        ActionProposalTestFixture fixture =
            new ActionProposalTestFixture(failingRepository);
        ActionProposalView proposal = fixture.propose();
        failingRepository.failNextTransitionAndReload();

        ActionProposalDecisionResult result = fixture.coordinator.decide(
            new ActionProposalDecisionRequest(
                proposal.receiptId(),
                ActionProposalDecision.CONFIRM
            ),
            fixture.trustedContext
        );

        assertThat(result.status()).isNull();
        assertThat(result.failure().reason())
            .isEqualTo("RECEIPT_STORE_UNAVAILABLE");
        assertThat(result.failure().retryable()).isTrue();
        assertThat(fixture.confirmedInvocations).hasValue(0);
        assertThat(durableStore.findById(proposal.receiptId()))
            .get()
            .extracting(ActionProposalReceipt::status)
            .isEqualTo(ActionProposalReceiptStatus.PROPOSED);
    }

    @Test
    void completionPersistenceFailureIsVisibleAndRecoveredWithoutRetry() {
        InMemoryActionProposalReceiptRepository durableStore =
            new InMemoryActionProposalReceiptRepository();
        FaultInjectingRepository failingRepository =
            new FaultInjectingRepository(durableStore);
        ActionProposalTestFixture fixture =
            new ActionProposalTestFixture(failingRepository);
        ActionProposalView proposal = fixture.propose();

        ActionProposalDecisionResult result = fixture.coordinator.decide(
            new ActionProposalDecisionRequest(
                proposal.receiptId(),
                ActionProposalDecision.CONFIRM
            ),
            fixture.trustedContext
        );

        assertThat(result.status())
            .isEqualTo(ActionProposalReceiptStatus.OUTCOME_UNKNOWN);
        assertThat(result.failure().reason())
            .isEqualTo("ACTION_OUTCOME_PERSISTENCE_FAILED");
        assertThat(durableStore.findById(proposal.receiptId()))
            .get()
            .extracting(ActionProposalReceipt::status)
            .isEqualTo(ActionProposalReceiptStatus.EXECUTING);
        assertThat(fixture.confirmedInvocations).hasValue(1);

        fixture.clock.advance(Duration.ofMinutes(3));
        ActionProposalRecoveryService.RecoverySummary recovery =
            new ActionProposalRecoveryService(
                failingRepository,
                fixture.security,
                ActionProposalMetrics.noop(),
                fixture.clock,
                Duration.ofMinutes(2),
                10
            ).recover();

        assertThat(recovery.unknownExecutions()).isEqualTo(1);
        assertThat(durableStore.findById(proposal.receiptId()))
            .get()
            .extracting(ActionProposalReceipt::status)
            .isEqualTo(ActionProposalReceiptStatus.OUTCOME_UNKNOWN);
        assertThat(fixture.confirmedInvocations).hasValue(1);
    }

    private ActionProposalReceipt copyWithProtectedParameters(
        ActionProposalReceipt receipt,
        String protectedParameters
    ) {
        return new ActionProposalReceipt(
            receipt.receiptId(),
            receipt.invocationId(),
            receipt.specialistId(),
            receipt.effectiveProfileHash(),
            receipt.principalFingerprint(),
            receipt.subjectType(),
            receipt.subjectFingerprint(),
            receipt.tenantFingerprint(),
            receipt.deploymentFingerprint(),
            receipt.actionName(),
            protectedParameters,
            receipt.parameterHash(),
            receipt.parameterSchemaHash(),
            receipt.confirmationMessage(),
            receipt.idempotencyKey(),
            receipt.evidenceHashes(),
            receipt.status(),
            receipt.createdAt(),
            receipt.expiresAt(),
            receipt.confirmedAt(),
            receipt.executionStartedAt(),
            receipt.executedAt(),
            receipt.terminalAt(),
            receipt.protectedOutcome(),
            receipt.failureReason(),
            fixtureInstant(receipt),
            receipt.version() + 1
        );
    }

    private java.time.Instant fixtureInstant(ActionProposalReceipt receipt) {
        return receipt.updatedAt().plusMillis(1);
    }

    private static final class FaultInjectingRepository
        implements ActionProposalReceiptRepository {

        private final ActionProposalReceiptRepository delegate;
        private final AtomicBoolean completionFailure =
            new AtomicBoolean(true);
        private final AtomicBoolean readFailure = new AtomicBoolean();
        private final AtomicBoolean idempotencyReadFailure =
            new AtomicBoolean();
        private final AtomicBoolean transitionRaceFailure =
            new AtomicBoolean();

        private FaultInjectingRepository(
            ActionProposalReceiptRepository delegate
        ) {
            this.delegate = delegate;
        }

        private void failReads() {
            readFailure.set(true);
        }

        private void failIdempotencyReads() {
            idempotencyReadFailure.set(true);
        }

        private void failNextTransitionAndReload() {
            transitionRaceFailure.set(true);
        }

        @Override
        public ActionProposalReceipt create(ActionProposalReceipt receipt) {
            return delegate.create(receipt);
        }

        @Override
        public java.util.Optional<ActionProposalReceipt> findById(
            String receiptId
        ) {
            if (readFailure.get()) {
                throw new IllegalStateException(
                    "simulated receipt read failure"
                );
            }
            return delegate.findById(receiptId);
        }

        @Override
        public java.util.Optional<ActionProposalReceipt>
            findByIdempotencyKey(String idempotencyKey) {
            if (idempotencyReadFailure.get()) {
                throw new IllegalStateException(
                    "simulated idempotency read failure"
                );
            }
            return delegate.findByIdempotencyKey(idempotencyKey);
        }

        @Override
        public boolean compareAndSet(
            ActionProposalReceipt expected,
            ActionProposalReceipt updated
        ) {
            if (transitionRaceFailure.compareAndSet(true, false)) {
                readFailure.set(true);
                return false;
            }
            if (expected.status()
                    == ActionProposalReceiptStatus.EXECUTING
                && updated.status().terminal()
                && completionFailure.compareAndSet(true, false)) {
                throw new IllegalStateException(
                    "simulated completion persistence failure"
                );
            }
            return delegate.compareAndSet(expected, updated);
        }

        @Override
        public List<ActionProposalReceipt> findExpiredConfirmable(
            java.time.Instant now,
            int limit
        ) {
            return delegate.findExpiredConfirmable(now, limit);
        }

        @Override
        public List<ActionProposalReceipt> findUpdatedBefore(
            ActionProposalReceiptStatus status,
            java.time.Instant cutoff,
            int limit
        ) {
            return delegate.findUpdatedBefore(status, cutoff, limit);
        }

        @Override
        public List<ActionProposalReceipt> findRetainableTerminalBefore(
            java.time.Instant cutoff,
            int limit
        ) {
            return delegate.findRetainableTerminalBefore(cutoff, limit);
        }

        @Override
        public boolean delete(ActionProposalReceipt expected) {
            return delegate.delete(expected);
        }
    }
}
