package ai.fabric.execution.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.gateway.AIExecutionFailure;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionResumeResult;
import ai.fabric.execution.gateway.AIExecutionResumeStatus;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.gateway.ExecutionDurability;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpecialistInputContractTest {

    private static final SpecialistId SPECIALIST_ID =
        SpecialistId.of("billing-advisor", "1");
    private static final SpecialistSchemaId RESPONSE_SCHEMA =
        SpecialistSchemaId.parse("billing-amount-response@1");
    private static final Instant NOW =
        Instant.parse("2026-07-29T10:00:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void protectsResponseSchemaWithDefensiveCopies() {
        var schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        SpecialistInputResponseContract contract =
            new SpecialistInputResponseContract(RESPONSE_SCHEMA, schema);

        schema.put("title", "mutated source");

        assertThat(contract.schema()).doesNotContainKey("title");
        assertThatThrownBy(() ->
            contract.schema().put("title", "mutated result")
        ).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNonObjectResponseSchemaRoots() {
        assertThatThrownBy(() -> new SpecialistInputResponseContract(
            RESPONSE_SCHEMA,
            objectMapper.createArrayNode()
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("root must be an object");
    }

    @Test
    void validatesRequirementBounds() {
        assertThatThrownBy(() -> new SpecialistInputRequirement(
            "invalid-purpose",
            "What amount should be assessed?",
            RESPONSE_SCHEMA,
            Duration.ofMinutes(5),
            2
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpecialistInputRequirement(
            "MISSING_AMOUNT",
            " ",
            RESPONSE_SCHEMA,
            Duration.ofMinutes(5),
            2
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpecialistInputRequirement(
            "MISSING_AMOUNT",
            "What amount should be assessed?",
            RESPONSE_SCHEMA,
            Duration.ZERO,
            2
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpecialistInputRequirement(
            "MISSING_AMOUNT",
            "What amount should be assessed?",
            RESPONSE_SCHEMA,
            Duration.ofMinutes(5),
            0
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsWaitingOutcomeDistinctFromSuccessConfirmationAndFailure() {
        NeedsUserInput inputRequest = needsUserInput();
        AIExecutionResult<String> waiting = new AIExecutionResult<>(
            "exec-1",
            SPECIALIST_ID,
            AIExecutionStatus.WAITING_FOR_INPUT,
            null,
            List.of(),
            Map.of(),
            null,
            NOW,
            NOW,
            null,
            inputRequest
        );

        assertThat(waiting.waitingForInput()).isTrue();
        assertThatThrownBy(() -> new AIExecutionResult<>(
            "exec-1",
            SPECIALIST_ID,
            AIExecutionStatus.WAITING_FOR_INPUT,
            "unexpected",
            List.of(),
            Map.of(),
            null,
            NOW,
            NOW,
            null,
            inputRequest
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AIExecutionResult<>(
            "exec-1",
            SPECIALIST_ID,
            AIExecutionStatus.SUCCEEDED,
            "done",
            List.of(),
            Map.of(),
            new AIExecutionFailure("FAILURE", "Failed.", false),
            NOW,
            NOW,
            null,
            null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesResumeResultPayloadExclusivity() {
        AIExecutionResult<String> success = new AIExecutionResult<>(
            "exec-1",
            SPECIALIST_ID,
            AIExecutionStatus.SUCCEEDED,
            "done",
            List.of(),
            Map.of(),
            null,
            NOW,
            NOW
        );

        assertThat(AIExecutionResumeResult.resumed(success).executionResult())
            .isSameAs(success);
        assertThatThrownBy(() -> new AIExecutionResumeResult<>(
            AIExecutionResumeStatus.RESUMED,
            null,
            null
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AIExecutionResumeResult<>(
            AIExecutionResumeStatus.DENIED,
            success,
            new AIExecutionFailure("DENIED", "Denied.", false)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private NeedsUserInput needsUserInput() {
        return new NeedsUserInput(
            "input-request-1",
            "exec-1",
            SPECIALIST_ID,
            "MISSING_AMOUNT",
            "What amount should be assessed?",
            new SpecialistInputResponseContract(
                RESPONSE_SCHEMA,
                objectMapper.createObjectNode().put("type", "object")
            ),
            InputDeliveryTarget.HOST_APPLICATION,
            ExecutionDurability.EPHEMERAL,
            NOW,
            NOW.plus(Duration.ofMinutes(5)),
            2
        );
    }
}
