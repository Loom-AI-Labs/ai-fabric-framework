package ai.fabric.execution.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.chain.state.SpecialistChainCheckpoint;
import ai.fabric.execution.manager.ConversationManagerContextValue;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpecialistChainContractTest {

    @Test
    void acceptsEveryValidDirectiveShape() {
        SpecialistChainTargetRequest health = target("health-reader@1");
        SpecialistChainTargetRequest changes = target("change-reader@1");

        assertThat(new SpecialistChainDirective(
            SpecialistChainDirectiveType.ASK_USER,
            List.of(),
            "Which service should I investigate?",
            "A service boundary is required."
        ).targets()).isEmpty();
        assertThat(new SpecialistChainDirective(
            SpecialistChainDirectiveType.COMPLETE,
            List.of(),
            "No approved investigation is required.",
            "The request is already answerable.",
            List.of("result-health")
        ).supportingResultIds()).containsExactly("result-health");
        assertThat(new SpecialistChainDirective(
            SpecialistChainDirectiveType.INVOKE_ONE,
            List.of(health),
            null,
            "Health evidence is required."
        ).requiredSingleTarget()).isEqualTo(health);
        assertThat(new SpecialistChainDirective(
            SpecialistChainDirectiveType.INVOKE_PARALLEL,
            List.of(health, changes),
            null,
            "The checks are independent."
        ).targets()).hasSize(2);
        assertThat(new SpecialistChainDirective(
            SpecialistChainDirectiveType.HANDOFF,
            List.of(changes),
            null,
            "Change-risk ownership is appropriate."
        ).requiredSingleTarget()).isEqualTo(changes);
    }

    @Test
    void rejectsConflictingOrDuplicateDirectiveFields() {
        SpecialistChainTargetRequest target = target("health-reader@1");

        assertThatThrownBy(() -> new SpecialistChainDirective(
            SpecialistChainDirectiveType.COMPLETE,
            List.of(target),
            "Done",
            "Invalid target."
        )).hasMessageContaining("cannot supply targets");
        assertThatThrownBy(() -> new SpecialistChainDirective(
            SpecialistChainDirectiveType.INVOKE_ONE,
            List.of(target),
            "Do it",
            "Invalid message."
        )).hasMessageContaining("cannot supply a user-facing message");
        assertThatThrownBy(() -> new SpecialistChainDirective(
            SpecialistChainDirectiveType.INVOKE_PARALLEL,
            List.of(target),
            null,
            "Too few targets."
        )).hasMessageContaining("requires between 2");
        assertThatThrownBy(() -> new SpecialistChainDirective(
            SpecialistChainDirectiveType.INVOKE_PARALLEL,
            List.of(target, target),
            null,
            "Duplicate targets."
        )).hasMessageContaining("duplicate specialists");
        assertThatThrownBy(() -> new SpecialistChainDirective(
            SpecialistChainDirectiveType.INVOKE_ONE,
            List.of(target),
            null,
            "Invalid result attribution.",
            List.of("result-health")
        )).hasMessageContaining("cannot supply supporting result IDs");
        assertThatThrownBy(() -> new SpecialistChainDirective(
            SpecialistChainDirectiveType.COMPLETE,
            List.of(),
            "Done",
            "Duplicate result attribution.",
            List.of("result-health", "result-health")
        )).hasMessageContaining("must not contain duplicates");
    }

    @Test
    void rejectsUnversionedTargetsAndOversizedText() {
        assertThatThrownBy(() -> target("health-reader"))
            .hasMessageContaining("name@version");
        assertThatThrownBy(() -> new SpecialistChainTargetRequest(
            "health-reader@1",
            "x".repeat(
                SpecialistChainTargetRequest.MAX_OBJECTIVE_CHARACTERS + 1
            )
        )).hasMessageContaining("objective must not exceed");
        assertThatThrownBy(() -> new SpecialistChainDirective(
            SpecialistChainDirectiveType.COMPLETE,
            List.of(),
            "x".repeat(
                SpecialistChainDirective.MAX_MESSAGE_CHARACTERS + 1
            ),
            "Complete."
        )).hasMessageContaining("message must not exceed");
    }

    @Test
    void createsImmutableBoundedManagerState() {
        List<ConversationManagerContextValue> context = new ArrayList<>();
        context.add(new ConversationManagerContextValue(
            "incidentId",
            "inc-1"
        ));
        List<SpecialistChainTargetView> targets = new ArrayList<>();
        targets.add(new SpecialistChainTargetView(
            "health-reader@1",
            "Inspect service health.",
            true,
            true,
            false
        ));
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("status", "degraded");
        SpecialistChainResultView result = new SpecialistChainResultView(
            "result-1",
            "health-reader@1",
            "worker-1",
            "Checkout is degraded.",
            facts,
            List.of("metric-1"),
            "a".repeat(64),
            Instant.parse("2026-09-13T12:00:00Z")
        );

        SpecialistChainManagerInput input = new SpecialistChainManagerInput(
            "Investigate checkout.",
            context,
            targets,
            List.of(result),
            new SpecialistChainBudgetView(2, 2, 2, 1_000, 20_000),
            null
        );
        context.clear();
        targets.clear();
        facts.clear();

        assertThat(input.applicationContext()).hasSize(1);
        assertThat(input.approvedTargets()).hasSize(1);
        assertThat(input.completedResults().getFirst().facts())
            .containsEntry("status", "degraded");
        assertThatThrownBy(() -> input.completedResults().add(result))
            .isInstanceOf(UnsupportedOperationException.class);

        SpecialistChainManagerInput completionInput =
            new SpecialistChainManagerInput(
                "Summarize completed work.",
                List.of(),
                List.of(),
                List.of(result),
                new SpecialistChainBudgetView(1, 0, 0, 500, 5_000),
                "Use the exact completed result attribution."
            );

        assertThat(completionInput.approvedTargets()).isEmpty();
        assertThat(completionInput.completedResults()).containsExactly(result);
        assertThat(completionInput.previousDirectiveFeedback())
            .contains("exact completed result");
    }

    @Test
    void rejectsUnboundedOrDuplicatePersistedResultViews() {
        Instant completedAt = Instant.parse("2026-09-13T12:00:00Z");

        assertThatThrownBy(() -> new SpecialistChainResultView(
            "r".repeat(201),
            "health-reader@1",
            "worker-1",
            "Approved summary.",
            Map.of(),
            List.of(),
            "a".repeat(64),
            completedAt
        )).hasMessageContaining("resultId must not exceed");
        assertThatThrownBy(() -> new SpecialistChainResultView(
            "result-1",
            "health-reader@1",
            "worker-1",
            "Approved summary.",
            Map.of(
                "status",
                "x".repeat(
                    SpecialistChainResultProjection
                        .MAX_FACT_VALUE_CHARACTERS + 1
                )
            ),
            List.of(),
            "a".repeat(64),
            completedAt
        )).hasMessageContaining("fact value must not exceed");
        assertThatThrownBy(() -> new SpecialistChainResultView(
            "result-1",
            "health-reader@1",
            "worker-1",
            "Approved summary.",
            Map.of(),
            List.of("metric-1", "metric-1"),
            "a".repeat(64),
            completedAt
        )).hasMessageContaining("must not contain duplicates");
    }

    @Test
    void rejectsUnboundedPersistedTraceAndTerminalText() {
        Instant completedAt = Instant.parse("2026-09-13T12:00:00Z");
        SpecialistChainBudgetView budget = new SpecialistChainBudgetView(
            1,
            1,
            1,
            100,
            1_000
        );

        assertThatThrownBy(() -> new SpecialistChainWorkerTrace(
            "health-reader@1",
            "DELEGATION",
            "w".repeat(201),
            "result-1",
            SpecialistChainWorkerStatus.SUCCEEDED,
            List.of(),
            null,
            completedAt,
            completedAt
        )).hasMessageContaining("invocationId must not exceed");
        assertThatThrownBy(() -> new SpecialistChainStepTrace(
            0,
            "manager-1",
            SpecialistChainDirectiveType.COMPLETE,
            "x".repeat(SpecialistChainDirective.MAX_REASON_CHARACTERS + 1),
            null,
            List.of(),
            budget,
            completedAt,
            completedAt
        )).hasMessageContaining("reason must not exceed");
        assertThatThrownBy(() -> new SpecialistChainExecutionResult(
            "execution-1",
            SpecialistChainId.of("incident-chain", "1"),
            "a".repeat(64),
            SpecialistChainExecutionStatus.COMPLETED,
            "x".repeat(SpecialistChainDirective.MAX_MESSAGE_CHARACTERS + 1),
            null,
            List.of(),
            List.of(),
            null,
            0,
            null,
            false,
            true,
            completedAt,
            completedAt
        )).hasMessageContaining("message must not exceed");
    }

    @Test
    void validatesDefinitionAndLimitRelationships() {
        assertThat(SpecialistChainLimits.conservative().maxDuration())
            .isEqualTo(Duration.ofMinutes(2));
        assertThatThrownBy(() -> new SpecialistChainLimits(
            Duration.ofSeconds(30),
            1,
            2,
            2,
            1,
            2_000
        )).hasMessageContaining("completion decision");
        assertThatThrownBy(() -> new SpecialistChainLimits(
            Duration.ofSeconds(30),
            3,
            1,
            2,
            1,
            2_000
        )).hasMessageContaining("cannot exceed");
    }

    @Test
    void checkpointRejectsManagerStateThatCouldNotBeSafelyRestored() {
        assertThatThrownBy(() -> SpecialistChainCheckpoint.initial(
            "x".repeat(SpecialistChainManagerInput.MAX_MESSAGE_CHARACTERS + 1),
            List.of(),
            null,
            0
        )).hasMessageContaining("currentUserMessage exceeds");

        assertThatThrownBy(() -> SpecialistChainCheckpoint.initial(
            "Investigate checkout.",
            List.of(
                new ConversationManagerContextValue("tenant", "one"),
                new ConversationManagerContextValue("tenant", "two")
            ),
            null,
            0
        )).hasMessageContaining("duplicate name");
    }

    private SpecialistChainTargetRequest target(String specialist) {
        return new SpecialistChainTargetRequest(
            specialist,
            "Inspect only approved evidence."
        );
    }
}
