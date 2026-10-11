package ai.fabric.intent.extraction;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.IntentExtractionJsonSupport;
import ai.fabric.intent.IntentExtractionValidator;
import ai.fabric.intent.orchestration.policy.SemanticRecoveryPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticRecoveryPromptSupportTest {

    private final IntentExtractionJsonSupport jsonSupport =
        new IntentExtractionJsonSupport(new ObjectMapper());

    @Test
    void includesTypedPriorDecisionWithoutProviderMetadata() {
        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.ACTION)
                .action("invented_action")
                .obligationId("obligation-1")
                .userGoal("retrieve approved policy")
                .build()))
            .metadata(Map.of("providerPrivateMetadata", "must-not-be-replayed"))
            .build();

        String addon = SemanticRecoveryPromptSupport.buildPromptAddon(
            rejectedAttempt(response),
            SemanticRecoveryPolicy.strictDefaults(),
            jsonSupport
        );

        assertThat(addon)
            .contains("invented_action")
            .contains("obligation-1")
            .doesNotContain("providerPrivateMetadata")
            .doesNotContain("must-not-be-replayed");
    }

    @Test
    void rejectsAnOversizedPriorDecisionInsteadOfSendingAnUnboundedPrompt() {
        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.ACTION)
                .action("invented_action")
                .obligationId("obligation-1")
                .userGoal("x".repeat(17_000))
                .build()))
            .build();

        assertThatThrownBy(() -> SemanticRecoveryPromptSupport.buildPromptAddon(
            rejectedAttempt(response),
            SemanticRecoveryPolicy.strictDefaults(),
            jsonSupport
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("exceeds bounded semantic recovery context");
    }

    private ExtractionAttempt rejectedAttempt(MultiIntentResponse response) {
        IntentExtractionValidator.ValidationIssue issue =
            new IntentExtractionValidator.ValidationIssue(
                IntentExtractionValidator.IssueCode.ACTION_UNREGISTERED,
                IntentExtractionValidator.Severity.ERROR,
                0,
                "action",
                "No handler registered for model-selected action"
            );
        return ExtractionAttempt.builder()
            .success(false)
            .response(response)
            .validationResult(new IntentExtractionValidator.ValidationResult(
                false,
                IntentExtractionValidator.ErrorCategory.UNSAFE,
                List.of(issue),
                List.of(issue.message()),
                List.of()
            ))
            .build();
    }
}
