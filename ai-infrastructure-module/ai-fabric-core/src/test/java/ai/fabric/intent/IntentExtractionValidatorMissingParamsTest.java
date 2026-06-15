package ai.fabric.intent;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentExtractionValidatorMissingParamsTest {

    @Test
    void shouldTreatMissingRequiredActionParamsAsWarningSoProgressiveEngineCanAskClarification() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);

        AIActionMetaData meta = AIActionMetaData.builder()
            .name("create_purchase_order")
            .requiredParameters(Set.of("email", "shippingAddress"))
            .build();

        when(registry.findMetadata("create_purchase_order")).thenReturn(Optional.of(meta));
        when(registry.findHandler("create_purchase_order")).thenReturn(Optional.of(handler));

        IntentExtractionValidator validator = new IntentExtractionValidator(registry);

        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.ACTION)
                .action("create_purchase_order")
                .actionParams(Map.of("sku", "ELEC-PHONE-002", "quantity", 1))
                .build()))
            .build();

        IntentExtractionValidator.ValidationResult result = validator.validate(response, "i want to buy");

        assertThat(result).isNotNull();
        assertThat(result.valid()).isTrue();
        assertThat(result.errorCategory()).isEqualTo(IntentExtractionValidator.ErrorCategory.NONE);
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isNotEmpty();
        assertThat(result.issues()).anyMatch(issue ->
            issue != null
                && issue.code() == IntentExtractionValidator.IssueCode.ACTION_REQUIRED_PARAM_MISSING
                && issue.severity() == IntentExtractionValidator.Severity.WARNING
        );
    }
}
