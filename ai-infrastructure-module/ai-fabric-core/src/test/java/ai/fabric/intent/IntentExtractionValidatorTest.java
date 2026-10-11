package ai.fabric.intent;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.action.AIActionRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentExtractionValidatorTest {

    @Test
    void shouldFailStructurallyWhenResponseIsNull() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        IntentExtractionValidator validator = new IntentExtractionValidator(registry);

        IntentExtractionValidator.ValidationResult result = validator.validate(null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(IntentExtractionValidator.ErrorCategory.STRUCTURAL);
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void shouldWarnButRemainValidWhenNoIntentsPresent() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        IntentExtractionValidator validator = new IntentExtractionValidator(registry);

        IntentExtractionValidator.ValidationResult result = validator.validate(MultiIntentResponse.builder().intents(List.of()).build());

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void shouldFailWhenActionNotRegistered() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler(anyString())).thenReturn(Optional.empty());
        IntentExtractionValidator validator = new IntentExtractionValidator(registry);

        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.ACTION)
                .action("unknown_action")
                .intent("unknown_action")
                .build()))
            .build();

        IntentExtractionValidator.ValidationResult result = validator.validate(response);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(IntentExtractionValidator.ErrorCategory.UNSAFE);
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void shouldFailWhenIntentMissingType() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        IntentExtractionValidator validator = new IntentExtractionValidator(registry);

        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(null)
                .intent("refund_policy")
                .build()))
            .build();

        IntentExtractionValidator.ValidationResult result = validator.validate(response);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(IntentExtractionValidator.ErrorCategory.STRUCTURAL);
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void shouldAcceptCompleteOutOfScopeIntent() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        IntentExtractionValidator validator = new IntentExtractionValidator(registry);

        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.OUT_OF_SCOPE)
                .requiresRetrieval(false)
                .requiresGeneration(false)
                .requiresTargetResolution(false)
                .directAnswer("I can help with approved account and policy requests.")
                .build()))
            .build();

        IntentExtractionValidator.ValidationResult result = validator.validate(response);

        assertThat(result.valid()).isTrue();
        assertThat(result.errorCategory()).isEqualTo(IntentExtractionValidator.ErrorCategory.NONE);
    }

    @Test
    void shouldRequireModelAuthoredOutOfScopeResponse() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        IntentExtractionValidator validator = new IntentExtractionValidator(registry);

        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.OUT_OF_SCOPE)
                .requiresRetrieval(false)
                .requiresGeneration(false)
                .requiresTargetResolution(false)
                .build()))
            .build();

        IntentExtractionValidator.ValidationResult result = validator.validate(response);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(IntentExtractionValidator.ErrorCategory.INCOMPLETE);
        assertThat(result.issues())
            .extracting(IntentExtractionValidator.ValidationIssue::code)
            .contains(IntentExtractionValidator.IssueCode.OUT_OF_SCOPE_RESPONSE_MISSING);
    }

    @Test
    void shouldRejectOutOfScopeExecutionRequest() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        IntentExtractionValidator validator = new IntentExtractionValidator(registry);

        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.OUT_OF_SCOPE)
                .requiresRetrieval(true)
                .requiresGeneration(false)
                .requiresTargetResolution(false)
                .directAnswer("I can help with approved account and policy requests.")
                .build()))
            .build();

        IntentExtractionValidator.ValidationResult result = validator.validate(response);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(IntentExtractionValidator.ErrorCategory.INCOMPLETE);
        assertThat(result.issues())
            .extracting(IntentExtractionValidator.ValidationIssue::code)
            .contains(IntentExtractionValidator.IssueCode.OUT_OF_SCOPE_EXECUTION_NOT_ALLOWED);
    }
}
