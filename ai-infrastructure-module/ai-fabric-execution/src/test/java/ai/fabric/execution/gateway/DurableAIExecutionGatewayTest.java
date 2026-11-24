package ai.fabric.execution.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.action.ActionProposalView;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.input.NeedsUserInput;
import ai.fabric.execution.specialist.ExecutionStrategy;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistDefinitionSource;
import ai.fabric.execution.specialist.SpecialistExecutionProfile;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistIdentity;
import ai.fabric.execution.specialist.SpecialistInputAdapter;
import ai.fabric.execution.specialist.SpecialistInstructions;
import ai.fabric.execution.specialist.SpecialistLimits;
import ai.fabric.execution.specialist.SpecialistOutputAdapter;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.SpecialistWritePolicy;
import ai.fabric.execution.state.DurableExecutionPayloadCodec;
import ai.fabric.execution.state.DurableExecutionRecord;
import ai.fabric.execution.state.DurableExecutionRepository;
import ai.fabric.execution.state.DurableExecutionSecurity;
import ai.fabric.execution.state.DurableExecutionSubmissionPolicy;
import ai.fabric.execution.state.DurableWorkflowCheckpointStore;
import ai.fabric.execution.state.JdbcDurableExecutionRepository;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.workflow.*;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.core.task.support.TaskExecutorAdapter;

class DurableAIExecutionGatewayTest {

    private static final SpecialistId SPECIALIST_ID =
        SpecialistId.of("account-resolver", "1");
    private static final Instant NOW =
        Instant.parse("2026-07-29T12:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(300);
    private static final String ENCRYPTION_SECRET =
        "durable-encryption-secret-for-tests-0001";
    private static final String FINGERPRINT_SECRET =
        "durable-fingerprint-secret-for-tests-0002";

    @Test void goalSegmentsSurviveDatabaseRestartAndExceedCrashAttemptBudgetWithoutResettingUsage() {
        var source = dataSource(); var repository = new JdbcDurableExecutionRepository(source, true);
        var security = security(); var registry = goalRegistry(); var runner = runner();
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var actualWrites = new java.util.ArrayList<String>();
        var hostCalls = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            String id = invocation.getArgument(0);
            var record = repository.findById(id).orElseThrow();
            var store = new DurableWorkflowCheckpointStore(repository, security, objectMapper(), clock, record);
            var coreRequest = new BoundedWorkflowRunner.Request(id, record.requestFingerprint(), record.accessFingerprint(),
                record.specialistContentHash(), record.deadline(), false);
            var result = new BoundedWorkflowRunner(store, clock).run(coreRequest, goalPolicy(), (checkpoint, allowance) -> {
                allowance.modelCall();
                return checkpoint.steps().size() < 18
                    ? new BoundedWorkflowRunner.Decision(BoundedWorkflowRunner.DecisionType.ACTION, "save", Map.of("value", checkpoint.steps().size()), null, null)
                    : new BoundedWorkflowRunner.Decision(BoundedWorkflowRunner.DecisionType.COMPLETE, null, null, "Saved", null);
            }, new BoundedWorkflowRunner.Actions() {
                public void verifyAuthority(BoundedWorkflowRunner.Request request) {}
                public boolean isWrite(String name) { return true; }
                public String fingerprint(Map<String, Object> parameters) { return security.canonicalHash(parameters); }
                public BoundedWorkflowRunner.Outcome invoke(WorkflowCheckpoint.Step step, WorkflowAllowance allowance) {
                    actualWrites.add(step.operationId());
                    return new BoundedWorkflowRunner.Outcome(WorkflowCheckpoint.Effect.EXECUTED, Map.of("receipt", step.operationId()), null, false);
                }
                public BoundedWorkflowRunner.Outcome reconcile(WorkflowCheckpoint.Step step) { throw new AssertionError("No dispatch should be replayed"); }
            });
            var data = new LinkedHashMap<String, Object>();
            data.put("state", result.state().name()); data.put("usage", result.usage());
            if (result.goal().nextEligibleAt() != null) data.put("nextEligibleAt", result.goal().nextEligibleAt().toString());
            return new AIExecutionResult<>(id, SPECIALIST_ID, result.state() == WorkflowCheckpoint.State.COMPLETE
                ? AIExecutionStatus.SUCCEEDED : AIExecutionStatus.YIELDED, result.state() == WorkflowCheckpoint.State.COMPLETE
                    ? new ResolverOutput("Saved all selected operations") : null, List.of(), Map.of("workflow", data), null, NOW, NOW);
        }).when(runner).executeAssigned(anyString(), any());
        var first = gateway(runner, registry, repository, security, rejectingExecutor());
        first.setDurableGoalExecutionHost(passthroughHost(hostCalls));
        var request = request(new ResolverInput("Prepare all selected operations"), "goal", context("owner", "account-42"));
        var accepted = first.submitGoal(new GoalExecutionRequest(request, "owned-work-authority"));
        assertThat(accepted.status()).isEqualTo(ExecutionHandleStatus.QUEUED);
        for (int i = 0; i < 12; i++) {
            var restarted = gateway(runner, registry, new JdbcDurableExecutionRepository(source, false), security, directExecutor());
            restarted.setDurableGoalExecutionHost(passthroughHost(hostCalls));
            restarted.recover();
            var snapshot = restarted.find(accepted.invocationId(), request.trustedExecutionContext()).orElseThrow();
            if (snapshot.handle().status() == ExecutionHandleStatus.SUCCEEDED) break;
            assertThat(snapshot.handle().status()).as("segment %s reason=%s result=%s checkpoint=%s", i, snapshot.handle().failureReason(),
                snapshot.result(), restarted.findWorkflowCheckpoint(accepted.invocationId(), request.trustedExecutionContext()))
                .isEqualTo(ExecutionHandleStatus.YIELDED);
        }
        var finalGateway = gateway(runner, registry, repository, security, directExecutor());
        assertThat(finalGateway.find(accepted.invocationId(), request.trustedExecutionContext()).orElseThrow().handle().status())
            .isEqualTo(ExecutionHandleStatus.SUCCEEDED);
        var journal = finalGateway.findWorkflowCheckpoint(accepted.invocationId(), request.trustedExecutionContext()).orElseThrow();
        assertThat(journal.steps()).hasSize(18);
        assertThat(journal.usage().writes()).isEqualTo(18);
        assertThat(journal.usage().modelCalls()).isGreaterThan(16);
        assertThat(journal.goal().segmentNumber()).isGreaterThan(3);
        assertThat(actualWrites).doesNotHaveDuplicates().hasSize(18);
        assertThat(hostCalls.get()).isGreaterThan(3);
        assertThat(finalGateway.findWorkflowCheckpoint(accepted.invocationId(), context("foreign", "account-42"))).isEmpty();
    }

    @Test void goalPauseResumeCancelAreAuthorizedAndNotificationIsRetriedAfterRestartWithoutInference() {
        var source = dataSource(); var repository = new JdbcDurableExecutionRepository(source, true);
        var runner = runner(); var registry = goalRegistry(); var security = security();
        var first = gateway(runner, registry, repository, security, rejectingExecutor());
        first.setDurableGoalExecutionHost(new DurableGoalExecutionHost() {
            public AIExecutionRequest<?> prepareSubmission(GoalExecutionRequest request) { return request.request(); }
            public AIExecutionResult<?> executeSegment(GoalExecutionClaim claim, AIExecutionRequest<?> request,
                java.util.function.Function<AIExecutionRequest<?>, AIExecutionResult<?>> execute) { return execute.apply(request); }
            public void executionUpdated(ExecutionSnapshot snapshot, String reference) { throw new IllegalStateException("lost callback"); }
        });
        var owner = context("owner", "account-42");
        var accepted = first.submitGoal(new GoalExecutionRequest(request(new ResolverInput("Goal"), "pause-goal", owner), "authority"));
        assertThat(first.controlGoal(accepted.invocationId(), GoalExecutionControl.PAUSE, context("foreign", "account-42"))).isFalse();
        assertThat(first.controlGoal(accepted.invocationId(), GoalExecutionControl.PAUSE, owner)).isTrue();
        assertThat(repository.findById(accepted.invocationId()).orElseThrow().goalNotificationPending()).isTrue();
        var notifications = new java.util.ArrayList<ExecutionHandleStatus>();
        var restarted = gateway(runner, registry, repository, security, directExecutor());
        restarted.setDurableGoalExecutionHost(new DurableGoalExecutionHost() {
            public AIExecutionRequest<?> prepareSubmission(GoalExecutionRequest request) { return request.request(); }
            public AIExecutionResult<?> executeSegment(GoalExecutionClaim claim, AIExecutionRequest<?> request,
                java.util.function.Function<AIExecutionRequest<?>, AIExecutionResult<?>> execute) {
                assertThat(claim.resumeRequested()).isTrue(); return execute.apply(request);
            }
            public void executionUpdated(ExecutionSnapshot snapshot, String reference) {
                assertThat(reference).isEqualTo("authority"); notifications.add(snapshot.handle().status());
            }
        });
        restarted.recover();
        assertThat(notifications).containsExactly(ExecutionHandleStatus.PAUSED);
        verify(runner, never()).executeAssigned(anyString(), any());
        assertThat(restarted.controlGoal(accepted.invocationId(), GoalExecutionControl.RESUME, owner)).isTrue();
        assertThat(restarted.find(accepted.invocationId(), owner).orElseThrow().handle().status()).isEqualTo(ExecutionHandleStatus.SUCCEEDED);
        assertThat(notifications).containsExactly(ExecutionHandleStatus.PAUSED, ExecutionHandleStatus.QUEUED, ExecutionHandleStatus.SUCCEEDED);
        assertThat(restarted.controlGoal(accepted.invocationId(), GoalExecutionControl.CANCEL, owner)).isFalse();
    }

    @Test void unconfiguredGoalHostAndRestoredScopeExpansionFailClosed() {
        var registry = goalRegistry(); var runner = runner(); var repository = new JdbcDurableExecutionRepository(dataSource(), true);
        var gateway = gateway(runner, registry, repository, security(), directExecutor());
        var owner = context("owner", "account-42");
        var request = request(new ResolverInput("Goal"), "denied-goal", owner);
        assertThat(gateway.submitGoal(new GoalExecutionRequest(request, "authority")).failureReason()).isEqualTo("DURABLE_GOAL_HOST_UNAVAILABLE");
        gateway.setDurableGoalExecutionHost(new DurableGoalExecutionHost() {
            public AIExecutionRequest<?> prepareSubmission(GoalExecutionRequest request) { return request.request(); }
            public AIExecutionResult<?> executeSegment(GoalExecutionClaim claim, AIExecutionRequest<?> persisted,
                java.util.function.Function<AIExecutionRequest<?>, AIExecutionResult<?>> execute) {
                return execute.apply(new AIExecutionRequest<>(persisted.specialistId(), persisted.input(),
                    context("foreign", "account-42"), null, persisted.deadline(), persisted.idempotencyKey()));
            }
        });
        var accepted = gateway.submitGoal(new GoalExecutionRequest(request, "authority"));
        var denied = gateway.find(accepted.invocationId(), owner).orElseThrow();
        assertThat(denied.handle().status()).isEqualTo(ExecutionHandleStatus.FAILED);
        assertThat(denied.result().status()).isEqualTo(AIExecutionStatus.DENIED);
        verify(runner, never()).executeAssigned(anyString(), any());
    }

    @Test void retiredGoalDefinitionPausesAndRemainsReadableWithoutInferenceOrLosingItsCheckpoint() {
        var registry = goalRegistry(); var runner = runner(); var security = security();
        var repository = new JdbcDurableExecutionRepository(dataSource(), true);
        var owner = context("owner", "account-42");
        var first = gateway(runner, registry, repository, security, rejectingExecutor());
        first.setDurableGoalExecutionHost(passthroughHost(new java.util.concurrent.atomic.AtomicInteger()));
        var accepted = first.submitGoal(new GoalExecutionRequest(request(new ResolverInput("Goal"), "retired-goal", owner), "authority"));
        when(registry.requireRegistered(SPECIALIST_ID)).thenThrow(new IllegalArgumentException("Definition retired"));
        var restarted = gateway(runner, registry, repository, security, directExecutor());
        restarted.setDurableGoalExecutionHost(passthroughHost(new java.util.concurrent.atomic.AtomicInteger()));
        restarted.recover();
        var snapshot = restarted.find(accepted.invocationId(), owner).orElseThrow();
        assertThat(snapshot.handle().status()).isEqualTo(ExecutionHandleStatus.PAUSED);
        assertThat(snapshot.handle().failureReason()).isEqualTo("SPECIALIST_DEFINITION_UNAVAILABLE");
        assertThat(snapshot.result().status()).isEqualTo(AIExecutionStatus.PAUSED);
        assertThat(repository.findById(accepted.invocationId()).orElseThrow().goalAuthorityReference()).isEqualTo("authority");
        verify(runner, never()).executeAssigned(anyString(), any());
    }

    private WorkflowPolicy goalPolicy() {
        return new WorkflowPolicy(true, 4, 3, 4, 2, Duration.ofSeconds(120),
            new WorkflowGoalPolicy(true, Duration.ofDays(1), Duration.ZERO, 8, 4, List.of()));
    }
    private SpecialistRegistry goalRegistry() {
        var base = definition();
        var profile = new SpecialistExecutionProfile("resolver", base.executionProfile().requestedCapabilities(),
            ExecutionStrategy.BOUNDED_ITERATIVE, SpecialistWritePolicy.DISABLED, goalPolicy());
        var definition = new SpecialistDefinition<>(base.identity(), base.instructions(), profile, base.limits(), base.inputAdapter(), base.outputAdapter());
        var registry = mock(SpecialistRegistry.class);
        when(registry.requireRegistered(SPECIALIST_ID)).thenReturn(RegisteredSpecialist.javaDefinition(definition));
        return registry;
    }
    private DurableGoalExecutionHost passthroughHost(java.util.concurrent.atomic.AtomicInteger calls) {
        return new DurableGoalExecutionHost() {
            public AIExecutionRequest<?> prepareSubmission(GoalExecutionRequest request) { return request.request(); }
            public AIExecutionResult<?> executeSegment(GoalExecutionClaim claim, AIExecutionRequest<?> request,
                java.util.function.Function<AIExecutionRequest<?>, AIExecutionResult<?>> execute) {
                calls.incrementAndGet(); return execute.apply(request);
            }
        };
    }

    @Test
    void queuedExecutionSurvivesRestartAndReplaysWithoutExecutingTwice() {
        JdbcDataSource dataSource = dataSource();
        SpecialistRegistry registry = registry();
        DurableExecutionSecurity security = security();
        DefaultAIExecutionGateway offlineRunner = runner();
        DurableAIExecutionGateway first = gateway(
            offlineRunner,
            registry,
            new JdbcDurableExecutionRepository(dataSource, true),
            security,
            rejectingExecutor()
        );
        AIExecutionRequest<ResolverInput> request = request(
            new ResolverInput("Inspect this account"),
            "event-42",
            context("account-service", "account-42").withCallerIdentity(new ai.fabric.execution.context.ExecutionCallerIdentity(
                "SERVICE", "PRIVATE_ASSERTION", "BACKEND", "session-private", "customer-private", "issuer-private",
                java.util.List.of("runtime"), NOW.plusSeconds(300)))
        );

        ExecutionHandle accepted = first.submit(request);

        assertThat(accepted.durability())
            .isEqualTo(ExecutionDurability.DURABLE);
        assertThat(accepted.status()).isEqualTo(ExecutionHandleStatus.QUEUED);
        DurableExecutionRecordAssertions.assertProtected(
            new JdbcDurableExecutionRepository(dataSource, false),
            accepted.invocationId(),
            "Inspect this account",
            "account-service",
            "account-42"
        );
        verify(offlineRunner, never())
            .executeAssigned(anyString(), any());

        DefaultAIExecutionGateway restartedRunner = runner();
        DurableExecutionRepository restartedRepository =
            new JdbcDurableExecutionRepository(dataSource, false);
        DurableAIExecutionGateway restarted = gateway(
            restartedRunner,
            registry,
            restartedRepository,
            security,
            directExecutor()
        );

        DurableAIExecutionGateway.RecoverySummary recovery =
            restarted.recover();
        ExecutionSnapshot completed = restarted.find(
            accepted.invocationId(),
            request.trustedExecutionContext()
        ).orElseThrow();

        assertThat(recovery.dispatched()).isEqualTo(1);
        assertThat(completed.handle().status())
            .isEqualTo(ExecutionHandleStatus.SUCCEEDED);
        assertThat(completed.result().output())
            .isEqualTo(new ResolverOutput("Account inspected"));
        assertThat(completed.result().diagnostics())
            .containsEntry("nullableProviderField", null);
        var restoredRequest = org.mockito.ArgumentCaptor.forClass(AIExecutionRequest.class);
        verify(restartedRunner).executeAssigned(anyString(), restoredRequest.capture());
        assertThat(restoredRequest.getValue().trustedExecutionContext().callerIdentity())
            .isEqualTo(request.trustedExecutionContext().callerIdentity());

        ExecutionHandle replay = restarted.submit(request);
        ExecutionHandle conflict = restarted.submit(request(
            new ResolverInput("Inspect a changed account payload"),
            "event-42",
            request.trustedExecutionContext()
        ));

        assertThat(replay.invocationId())
            .isEqualTo(accepted.invocationId());
        assertThat(replay.status())
            .isEqualTo(ExecutionHandleStatus.SUCCEEDED);
        assertThat(conflict.status())
            .isEqualTo(ExecutionHandleStatus.REJECTED);
        assertThat(conflict.failureReason())
            .isEqualTo("IDEMPOTENCY_CONFLICT");
        verify(restartedRunner)
            .executeAssigned(anyString(), any());
    }

    @Test
    void findIsBoundToTrustedExecutionIdentityAndSubject() {
        SpecialistRegistry registry = registry();
        DurableExecutionSecurity security = security();
        DurableAIExecutionGateway gateway = gateway(
            runner(),
            registry,
            new JdbcDurableExecutionRepository(dataSource(), true),
            security,
            directExecutor()
        );
        TrustedExecutionContext owner =
            context("account-service", "account-42");
        ExecutionHandle handle = gateway.submit(request(
            new ResolverInput("Inspect this account"),
            "event-owner",
            owner
        ));

        assertThat(gateway.find(handle.invocationId(), owner)).isPresent();
        assertThat(gateway.find(
            handle.invocationId(),
            context("other-service", "account-42")
        )).isEmpty();
        assertThat(gateway.find(
            handle.invocationId(),
            context("account-service", "account-99")
        )).isEmpty();
    }

    @Test
    void cancelledQueuedExecutionIsNotRunByRecovery() {
        JdbcDataSource dataSource = dataSource();
        SpecialistRegistry registry = registry();
        DurableExecutionSecurity security = security();
        TrustedExecutionContext context =
            context("account-service", "account-42");
        DurableAIExecutionGateway first = gateway(
            runner(),
            registry,
            new JdbcDurableExecutionRepository(dataSource, true),
            security,
            rejectingExecutor()
        );
        ExecutionHandle handle = first.submit(request(
            new ResolverInput("Inspect this account"),
            "event-cancel",
            context
        ));

        assertThat(first.cancel(handle.invocationId(), context)).isTrue();

        DefaultAIExecutionGateway restartedRunner = runner();
        DurableAIExecutionGateway restarted = gateway(
            restartedRunner,
            registry,
            new JdbcDurableExecutionRepository(dataSource, false),
            security,
            directExecutor()
        );
        assertThat(restarted.recover().dispatched()).isZero();
        assertThat(restarted.find(handle.invocationId(), context))
            .get()
            .extracting(snapshot -> snapshot.handle().status())
            .isEqualTo(ExecutionHandleStatus.CANCELLED);
        verify(restartedRunner, never())
            .executeAssigned(anyString(), any());
    }

    @Test
    void routesInteractiveSubmissionsToTheEphemeralDelegate() {
        SpecialistRegistry registry = registry();
        DefaultAIExecutionGateway runner = runner();
        DurableAIExecutionGateway gateway = gateway(
            runner,
            registry,
            new JdbcDurableExecutionRepository(dataSource(), true),
            security(),
            directExecutor()
        );
        TrustedExecutionContext interactive = new TrustedExecutionContext(
            new ExecutionPrincipal(
                "user-42",
                ExecutionPrincipalType.END_USER
            ),
            new ExecutionSubjectRef("account", "account-42"),
            ExecutionSource.INTERACTIVE,
            "tenant-1",
            "test",
            Set.of(),
            "correlation-user",
            NOW
        );
        AIExecutionRequest<ResolverInput> request =
            new AIExecutionRequest<>(
                SPECIALIST_ID,
                new ResolverInput("Inspect this account"),
                interactive,
                new ConversationBinding("user-42", "conversation-1"),
                DEADLINE,
                "interactive-1"
            );
        ExecutionHandle ephemeral = new ExecutionHandle(
            "ephemeral-interactive-1",
            ExecutionDurability.EPHEMERAL,
            ExecutionHandleStatus.QUEUED,
            DEADLINE,
            DEADLINE.plusSeconds(30),
            null
        );
        when(runner.submit(request)).thenReturn(ephemeral);

        ExecutionHandle result = gateway.submit(request);

        assertThat(result).isSameAs(ephemeral);
        verify(runner).submit(request);
    }

    @Test
    void stillRejectsConversationBoundMachineSubmissions() {
        DurableAIExecutionGateway gateway = gateway(
            runner(),
            registry(),
            new JdbcDurableExecutionRepository(dataSource(), true),
            security(),
            directExecutor()
        );
        AIExecutionRequest<ResolverInput> request =
            new AIExecutionRequest<>(
                SPECIALIST_ID,
                new ResolverInput("Inspect this account"),
                context("account-service", "account-42"),
                new ConversationBinding(
                    "account-service",
                    "conversation-1"
                ),
                DEADLINE,
                "application-conversation-1"
            );

        ExecutionHandle result = gateway.submit(request);

        assertThat(result.status())
            .isEqualTo(ExecutionHandleStatus.REJECTED);
        assertThat(result.failureReason())
            .isEqualTo("DURABLE_CONVERSATION_UNSUPPORTED");
    }

    @Test
    void definitionChangeFailsClosedBeforeSpecialistExecution() {
        JdbcDataSource dataSource = dataSource();
        SpecialistRegistry registry = registry();
        DurableExecutionSecurity security = security();
        DefaultAIExecutionGateway offlineRunner = runner();
        DurableAIExecutionGateway first = gateway(
            offlineRunner,
            registry,
            new JdbcDurableExecutionRepository(dataSource, true),
            security,
            rejectingExecutor()
        );
        AIExecutionRequest<ResolverInput> request = request(
            new ResolverInput("Inspect this account"),
            "event-definition-change",
            context("account-service", "account-42")
        );
        ExecutionHandle accepted = first.submit(request);
        RegisteredSpecialist changed = new RegisteredSpecialist(
            definition(),
            SpecialistDefinitionSource.JAVA,
            ai.fabric.execution.specialist.manifest.CanonicalJsonSupport
                .sha256("changed-specialist-definition"),
            "java:" + SPECIALIST_ID,
            Map.of()
        );
        when(registry.requireRegistered(SPECIALIST_ID))
            .thenReturn(changed);

        DefaultAIExecutionGateway restartedRunner = runner();
        DurableAIExecutionGateway restarted = gateway(
            restartedRunner,
            registry,
            new JdbcDurableExecutionRepository(dataSource, false),
            security,
            directExecutor()
        );
        restarted.recover();

        ExecutionSnapshot snapshot = restarted.find(
            accepted.invocationId(),
            request.trustedExecutionContext()
        ).orElseThrow();
        assertThat(snapshot.handle().status())
            .isEqualTo(ExecutionHandleStatus.FAILED);
        assertThat(snapshot.handle().failureReason())
            .isEqualTo("SPECIALIST_DEFINITION_CHANGED");
        assertThat(snapshot.result().failure().reason())
            .isEqualTo("SPECIALIST_DEFINITION_CHANGED");
        verify(restartedRunner, never())
            .executeAssigned(anyString(), any());
    }

    @Test
    void unsupportedDurableContinuationOutcomesFailVisibly() {
        for (AIExecutionStatus status : List.of(
            AIExecutionStatus.WAITING_FOR_INPUT,
            AIExecutionStatus.CONFIRMATION_REQUIRED
        )) {
            DefaultAIExecutionGateway runner = runner(
                interactiveResult(status)
            );
            TrustedExecutionContext context = context(
                "account-service-" + status.name().toLowerCase(),
                "account-42"
            );
            DurableAIExecutionGateway gateway = gateway(
                runner,
                registry(),
                new JdbcDurableExecutionRepository(dataSource(), true),
                security(),
                directExecutor()
            );

            ExecutionHandle handle = gateway.submit(request(
                new ResolverInput("Inspect this account"),
                "event-" + status.name().toLowerCase(),
                context
            ));
            ExecutionSnapshot snapshot = gateway.find(
                handle.invocationId(),
                context
            ).orElseThrow();

            assertThat(snapshot.handle().status())
                .isEqualTo(ExecutionHandleStatus.FAILED);
            assertThat(snapshot.result().failure().reason())
                .isEqualTo("DURABLE_CONTINUATION_UNSUPPORTED");
            verify(runner).executeAssigned(anyString(), any());
        }
    }

    @Test
    void expiredDeadlineAndExhaustedLeaseBecomeTerminal() {
        SpecialistRegistry registry = registry();
        TrustedExecutionContext expiredContext =
            context("deadline-service", "account-deadline");
        DefaultAIExecutionGateway expiredRunner = runner();
        DurableAIExecutionGateway expiredGateway = gateway(
            expiredRunner,
            registry,
            new JdbcDurableExecutionRepository(dataSource(), true),
            security(),
            directExecutor(),
            Clock.fixed(DEADLINE.plusSeconds(1), ZoneOffset.UTC),
            3
        );
        ExecutionHandle expired = expiredGateway.submit(request(
            new ResolverInput("Inspect expired work"),
            "event-expired",
            expiredContext
        ));

        DurableAIExecutionGateway.RecoverySummary deadlineRecovery =
            expiredGateway.recover();
        ExecutionSnapshot expiredSnapshot = expiredGateway.find(
            expired.invocationId(),
            expiredContext
        ).orElseThrow();
        assertThat(deadlineRecovery.deadlineFailures()).isEqualTo(1);
        assertThat(expiredSnapshot.handle().status())
            .isEqualTo(ExecutionHandleStatus.EXPIRED);
        assertThat(expiredSnapshot.handle().failureReason())
            .isEqualTo("DEADLINE_EXCEEDED");
        verify(expiredRunner, never())
            .executeAssigned(anyString(), any());

        JdbcDataSource attemptDataSource = dataSource();
        DurableExecutionRepository attemptRepository =
            new JdbcDurableExecutionRepository(attemptDataSource, true);
        TrustedExecutionContext attemptContext =
            context("attempt-service", "account-attempt");
        DurableAIExecutionGateway attemptGateway = gateway(
            runner(),
            registry,
            attemptRepository,
            security(),
            rejectingExecutor(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            1
        );
        ExecutionHandle attempt = attemptGateway.submit(request(
            new ResolverInput("Inspect abandoned work"),
            "event-attempt",
            attemptContext
        ));
        DurableExecutionRecord queued = attemptRepository
            .findById(attempt.invocationId())
            .orElseThrow();
        DurableExecutionRecord abandoned = queued.claimed(
            "stopped-worker",
            NOW.minusSeconds(60),
            NOW.minusSeconds(30)
        );
        assertThat(attemptRepository.compareAndSet(queued, abandoned))
            .isTrue();

        DurableAIExecutionGateway.RecoverySummary attemptRecovery =
            attemptGateway.recover();
        ExecutionSnapshot attemptSnapshot = attemptGateway.find(
            attempt.invocationId(),
            attemptContext
        ).orElseThrow();
        assertThat(attemptRecovery.attemptFailures()).isEqualTo(1);
        assertThat(attemptSnapshot.handle().status())
            .isEqualTo(ExecutionHandleStatus.FAILED);
        assertThat(attemptSnapshot.handle().failureReason())
            .isEqualTo("RECOVERY_ATTEMPTS_EXHAUSTED");
    }

    @Test
    void sameIdempotencyKeyIsIndependentAcrossTrustedAccessBindings() {
        DefaultAIExecutionGateway runner = runner();
        DurableAIExecutionGateway gateway = gateway(
            runner,
            registry(),
            new JdbcDurableExecutionRepository(dataSource(), true),
            security(),
            directExecutor()
        );
        ExecutionHandle first = gateway.submit(request(
            new ResolverInput("Inspect first account"),
            "shared-event",
            context("account-service", "account-1")
        ));
        ExecutionHandle second = gateway.submit(request(
            new ResolverInput("Inspect second account"),
            "shared-event",
            context("account-service", "account-2")
        ));

        assertThat(first.invocationId()).isNotEqualTo(second.invocationId());
        assertThat(first.status()).isEqualTo(ExecutionHandleStatus.QUEUED);
        assertThat(second.status()).isEqualTo(ExecutionHandleStatus.QUEUED);
        verify(runner, times(2)).executeAssigned(anyString(), any());
    }

    @Test
    void terminalProviderFailureIsPersistedAndNeverRecovered() {
        DefaultAIExecutionGateway runner = runner(failedResult());
        TrustedExecutionContext context =
            context("provider-service", "account-provider");
        DurableAIExecutionGateway gateway = gateway(
            runner,
            registry(),
            new JdbcDurableExecutionRepository(dataSource(), true),
            security(),
            directExecutor()
        );

        ExecutionHandle handle = gateway.submit(request(
            new ResolverInput("Inspect with provider"),
            "event-provider-failure",
            context
        ));
        ExecutionSnapshot failed = gateway.find(
            handle.invocationId(),
            context
        ).orElseThrow();

        assertThat(failed.handle().status())
            .isEqualTo(ExecutionHandleStatus.FAILED);
        assertThat(failed.result().failure().reason())
            .isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(gateway.recover().dispatched()).isZero();
        verify(runner).executeAssigned(anyString(), any());
    }

    private DurableAIExecutionGateway gateway(
        DefaultAIExecutionGateway runner,
        SpecialistRegistry registry,
        DurableExecutionRepository repository,
        DurableExecutionSecurity security,
        AsyncTaskExecutor executor
    ) {
        return gateway(
            runner,
            registry,
            repository,
            security,
            executor,
            Clock.fixed(NOW, ZoneOffset.UTC),
            3
        );
    }

    private DurableAIExecutionGateway gateway(
        DefaultAIExecutionGateway runner,
        SpecialistRegistry registry,
        DurableExecutionRepository repository,
        DurableExecutionSecurity security,
        AsyncTaskExecutor executor,
        Clock clock,
        int maxAttempts
    ) {
        return new DurableAIExecutionGateway(
            runner,
            runner,
            registry,
            repository,
            new DurableExecutionPayloadCodec(
                objectMapper(),
                registry,
                security
            ),
            security,
            new DurableExecutionSubmissionPolicy(),
            executor,
            clock,
            Duration.ofSeconds(30),
            Duration.ofDays(30),
            50,
            maxAttempts,
            false
        );
    }

    private DefaultAIExecutionGateway runner() {
        DefaultAIExecutionGateway runner =
            mock(DefaultAIExecutionGateway.class);
        when(runner.resolveDeadline(any(), any())).thenReturn(DEADLINE);
        when(runner.executeAssigned(anyString(), any())).thenAnswer(answer -> {
            String invocationId = answer.getArgument(0);
            LinkedHashMap<String, Object> diagnostics =
                new LinkedHashMap<>();
            diagnostics.put("nullableProviderField", null);
            return new AIExecutionResult<>(
                invocationId,
                SPECIALIST_ID,
                AIExecutionStatus.SUCCEEDED,
                new ResolverOutput("Account inspected"),
                List.of(),
                diagnostics,
                null,
                NOW,
                NOW.plusSeconds(1)
            );
        });
        return runner;
    }

    private DefaultAIExecutionGateway runner(
        AIExecutionResult<?> result
    ) {
        DefaultAIExecutionGateway runner =
            mock(DefaultAIExecutionGateway.class);
        when(runner.resolveDeadline(any(), any())).thenReturn(DEADLINE);
        when(runner.executeAssigned(anyString(), any()))
            .thenAnswer(ignored -> result);
        return runner;
    }

    private AIExecutionResult<?> interactiveResult(
        AIExecutionStatus status
    ) {
        return new AIExecutionResult<>(
            "upstream-invocation",
            SPECIALIST_ID,
            status,
            null,
            List.of(),
            Map.of(),
            null,
            NOW,
            NOW.plusSeconds(1),
            status == AIExecutionStatus.CONFIRMATION_REQUIRED
                ? mock(ActionProposalView.class)
                : null,
            status == AIExecutionStatus.WAITING_FOR_INPUT
                ? mock(NeedsUserInput.class)
                : null
        );
    }

    private AIExecutionResult<?> failedResult() {
        return new AIExecutionResult<>(
            "upstream-invocation",
            SPECIALIST_ID,
            AIExecutionStatus.FAILED,
            null,
            List.of(),
            Map.of(),
            new AIExecutionFailure(
                "PROVIDER_UNAVAILABLE",
                "The provider is unavailable.",
                true
            ),
            NOW,
            NOW.plusSeconds(1)
        );
    }

    private SpecialistRegistry registry() {
        SpecialistRegistry registry = mock(SpecialistRegistry.class);
        RegisteredSpecialist registered =
            RegisteredSpecialist.javaDefinition(definition());
        when(registry.requireRegistered(SPECIALIST_ID))
            .thenReturn(registered);
        return registry;
    }

    private SpecialistDefinition<ResolverInput, ResolverOutput> definition() {
        return new SpecialistDefinition<>(
            new SpecialistIdentity(
                SPECIALIST_ID,
                "Account Resolver",
                "Explains current account state"
            ),
            new SpecialistInstructions(
                "Inspect the current account.",
                "Use trusted application evidence only."
            ),
            new SpecialistExecutionProfile(
                "resolver",
                RequestedCapabilityProfile.retrievalOnly(
                    Set.of("account-policy")
                ),
                ExecutionStrategy.SINGLE_PASS,
                SpecialistWritePolicy.DISABLED
            ),
            SpecialistLimits.defaults(),
            new SpecialistInputAdapter<>() {
                @Override
                public Class<ResolverInput> inputType() {
                    return ResolverInput.class;
                }

                @Override
                public void validate(ResolverInput input) {}

                @Override
                public String renderModelInput(ResolverInput input) {
                    return input.question();
                }
            },
            new SpecialistOutputAdapter<>() {
                @Override
                public Class<ResolverOutput> outputType() {
                    return ResolverOutput.class;
                }

                @Override
                public ResolverOutput project(
                    OrchestrationResult result,
                    List<AIEvidenceReference> evidence
                ) {
                    return new ResolverOutput(result.getMessage());
                }

                @Override
                public void validate(ResolverOutput output) {}
            }
        );
    }

    private AIExecutionRequest<ResolverInput> request(
        ResolverInput input,
        String idempotencyKey,
        TrustedExecutionContext context
    ) {
        return new AIExecutionRequest<>(
            SPECIALIST_ID,
            input,
            context,
            null,
            DEADLINE,
            idempotencyKey
        );
    }

    private TrustedExecutionContext context(
        String serviceId,
        String accountId
    ) {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                serviceId,
                ExecutionPrincipalType.SERVICE
            ),
            new ExecutionSubjectRef("account", accountId),
            ExecutionSource.EVENT,
            "tenant-1",
            "test",
            Set.of("specialist:account-resolver@1"),
            "correlation-" + serviceId + "-" + accountId,
            NOW
        );
    }

    private DurableExecutionSecurity security() {
        return new DurableExecutionSecurity(
            objectMapper(),
            ENCRYPTION_SECRET,
            FINGERPRINT_SECRET
        );
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private AsyncTaskExecutor directExecutor() {
        return new TaskExecutorAdapter(Runnable::run);
    }

    private AsyncTaskExecutor rejectingExecutor() {
        return new TaskExecutorAdapter(command -> {
            throw new TaskRejectedException("executor unavailable");
        });
    }

    private JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:durable-gateway-"
                + java.util.UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1"
        );
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private record ResolverInput(String question) {}

    private record ResolverOutput(String summary) {}

    private static final class DurableExecutionRecordAssertions {

        private static void assertProtected(
            DurableExecutionRepository repository,
            String invocationId,
            String... forbiddenValues
        ) {
            String payload = repository.findById(invocationId)
                .orElseThrow()
                .protectedRequest();
            assertThat(payload).startsWith("v1.");
            for (String forbiddenValue : forbiddenValues) {
                assertThat(payload).doesNotContain(forbiddenValue);
            }
        }
    }
}
