package com.ai.infrastructure.intent;

import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.intent.action.AIActionRegistry;
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
}
