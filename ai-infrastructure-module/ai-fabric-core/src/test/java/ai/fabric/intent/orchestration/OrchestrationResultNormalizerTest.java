package ai.fabric.intent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationResultNormalizerTest {

    private final OrchestrationResultNormalizer normalizer = new OrchestrationResultNormalizer();

    @Test
    void normalize_compoundWithOnlyConfirmations_promotesNextConfirmationInsteadOfFailureWrapper() {
        OrchestrationResult confirmA = OrchestrationResult.builder()
            .type(OrchestrationResultType.CONFIRMATION_REQUIRED)
            .success(false)
            .message("Confirm A?")
            .data(Map.of("action", "a"))
            .build();

        OrchestrationResult confirmB = OrchestrationResult.builder()
            .type(OrchestrationResultType.CONFIRMATION_REQUIRED)
            .success(false)
            .message("Confirm B?")
            .data(Map.of("action", "b"))
            .build();

        OrchestrationResult raw = OrchestrationResult.builder()
            .type(OrchestrationResultType.COMPOUND_HANDLED)
            .success(false)
            .message("Some intents failed. See results for details.")
            .children(List.of(confirmA, confirmB))
            .build();

        OrchestrationResult normalized = normalizer.normalize(raw);
        assertThat(normalized.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(normalized.isSuccess()).isFalse();
        assertThat(normalized.getMessage()).isEqualTo("Confirm B?");
        assertThat(normalized.getData()).containsEntry("action", "b");
        assertThat(normalized.getChildren()).hasSize(2);
        assertThat(normalized.getMetadata())
            .containsKey("compoundAggregation");
    }

    @Test
    void normalize_compoundWithSuccessAndPending_keepsCompoundVisible() {
        OrchestrationResult action = OrchestrationResult.builder()
            .type(OrchestrationResultType.ACTION_EXECUTED)
            .success(true)
            .message("Action succeeded")
            .data(Map.of("action", "safe_read"))
            .build();

        OrchestrationResult confirm = OrchestrationResult.builder()
            .type(OrchestrationResultType.CONFIRMATION_REQUIRED)
            .success(false)
            .message("Confirm write?")
            .data(Map.of("action", "write"))
            .build();

        OrchestrationResult raw = OrchestrationResult.builder()
            .type(OrchestrationResultType.COMPOUND_HANDLED)
            .success(false)
            .message("Some intents failed. See results for details.")
            .children(List.of(action, confirm))
            .build();

        OrchestrationResult normalized = normalizer.normalize(raw);
        assertThat(normalized.getType()).isEqualTo(OrchestrationResultType.COMPOUND_HANDLED);
        assertThat(normalized.isSuccess()).isTrue();
        assertThat(normalized.getMessage()).contains("completed").contains("require confirmation");
        assertThat(normalized.getChildren()).hasSize(2);
        assertThat(normalized.getMetadata()).containsKey("compoundAggregation");
    }

    @Test
    void normalize_compoundWithOnlyChildError_bubblesToTopLevelError() {
        OrchestrationResult childError = OrchestrationResult.builder()
            .type(OrchestrationResultType.ERROR)
            .success(false)
            .message("No action handler registered for action 'invalid'")
            .errorCode(OrchestrationResultNormalizer.ERROR_CODE_ACTION_NOT_FOUND)
            .build();

        OrchestrationResult raw = OrchestrationResult.builder()
            .type(OrchestrationResultType.COMPOUND_HANDLED)
            .success(true)
            .message("Some intents failed. See results for details.")
            .children(List.of(childError))
            .metadata(Map.of("sessionId", "s1"))
            .build();

        OrchestrationResult normalized = normalizer.normalize(raw);
        assertThat(normalized.getType()).isEqualTo(OrchestrationResultType.ERROR);
        assertThat(normalized.isSuccess()).isFalse();
        assertThat(normalized.getErrorCode()).isEqualTo(OrchestrationResultNormalizer.ERROR_CODE_ACTION_NOT_FOUND);
        assertThat(normalized.getMessage()).contains("No action handler registered");
        assertThat(normalized.getMetadata()).containsEntry("errorCode", OrchestrationResultNormalizer.ERROR_CODE_ACTION_NOT_FOUND);
    }

    @Test
    void normalize_compoundWithPrimarySuccessAndSoftChildError_promotesPrimaryType() {
        OrchestrationResult action = OrchestrationResult.builder()
            .type(OrchestrationResultType.ACTION_EXECUTED)
            .success(true)
            .message("Action succeeded")
            .data(Map.of("action", "relationship_query"))
            .build();

        OrchestrationResult childError = OrchestrationResult.builder()
            .type(OrchestrationResultType.ERROR)
            .success(false)
            .message("No action handler registered for action 'summarize'")
            .errorCode(OrchestrationResultNormalizer.ERROR_CODE_ACTION_NOT_FOUND)
            .build();

        OrchestrationResult raw = OrchestrationResult.builder()
            .type(OrchestrationResultType.COMPOUND_HANDLED)
            .success(false)
            .message("Some intents failed. See results for details.")
            .children(List.of(action, childError))
            .metadata(Map.of("sessionId", "s1"))
            .build();

        OrchestrationResult normalized = normalizer.normalize(raw);
        assertThat(normalized.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(normalized.isSuccess()).isTrue();
        assertThat(normalized.getData()).containsEntry("action", "relationship_query");
        assertThat(normalized.getMetadata()).containsEntry("softChildErrorCode", OrchestrationResultNormalizer.ERROR_CODE_ACTION_NOT_FOUND);
    }

    @Test
    void normalize_compoundWithPrimarySuccessAndGenerationFailure_promotesPrimaryType() {
        OrchestrationResult action = OrchestrationResult.builder()
            .type(OrchestrationResultType.ACTION_EXECUTED)
            .success(true)
            .message("Action succeeded")
            .data(Map.of("action", "relationship_query"))
            .build();

        OrchestrationResult generationError = OrchestrationResult.builder()
            .type(OrchestrationResultType.ERROR)
            .success(false)
            .message("Failed to generate response: provider unavailable")
            .data(Map.of("generationError", "provider unavailable", "requiresGeneration", true))
            .build();

        OrchestrationResult raw = OrchestrationResult.builder()
            .type(OrchestrationResultType.COMPOUND_HANDLED)
            .success(false)
            .message("Some intents failed. See results for details.")
            .children(List.of(action, generationError))
            .metadata(Map.of("sessionId", "s1"))
            .build();

        OrchestrationResult normalized = normalizer.normalize(raw);
        assertThat(normalized.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(normalized.isSuccess()).isTrue();
        assertThat(normalized.getData()).containsEntry("action", "relationship_query");
        assertThat(normalized.getMetadata()).containsEntry("softChildErrorCode", OrchestrationResultNormalizer.ERROR_CODE_GENERATION_FAILED);
    }

    @Test
    void normalize_compoundWithoutErrors_promotesPrimaryChildType() {
        OrchestrationResult info = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message("Refund policy is 30 days.")
            .data(Map.of("answer", "Refund policy is 30 days."))
            .build();

        OrchestrationResult raw = OrchestrationResult.builder()
            .type(OrchestrationResultType.COMPOUND_HANDLED)
            .success(true)
            .message("Some intents failed. See results for details.")
            .children(List.of(info))
            .build();

        OrchestrationResult normalized = normalizer.normalize(raw);
        assertThat(normalized.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(normalized.isSuccess()).isTrue();
        assertThat(normalized.getMessage()).contains("Refund policy");
        assertThat(normalized.getData()).containsEntry("answer", "Refund policy is 30 days.");
    }

    @Test
    void normalize_errorWithoutErrorCode_derivesActionNotFoundFromMessage() {
        OrchestrationResult raw = OrchestrationResult.builder()
            .type(OrchestrationResultType.ERROR)
            .success(false)
            .message("No action handler registered for action 'invalid'")
            .build();

        OrchestrationResult normalized = normalizer.normalize(raw);
        assertThat(normalized.getErrorCode()).isEqualTo(OrchestrationResultNormalizer.ERROR_CODE_ACTION_NOT_FOUND);
        assertThat(normalized.getMetadata()).containsEntry("errorCode", OrchestrationResultNormalizer.ERROR_CODE_ACTION_NOT_FOUND);
    }
}
