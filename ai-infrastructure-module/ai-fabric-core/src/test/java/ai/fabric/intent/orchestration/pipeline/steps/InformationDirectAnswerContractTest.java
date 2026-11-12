package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.IntentExtractionValidator;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class InformationDirectAnswerContractTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void missingFinalAnswerIsNotReplacedWithSuccessfulAcknowledgement(String answer) {
        var intent = information(answer);
        var validation = new IntentExtractionValidator(null).validate(
            MultiIntentResponse.builder().intents(List.of(intent)).build());
        assertThat(validation.valid()).isTrue(); // READ planning can still provide the answer later.
        var result = InformationGenerationResponseSupport.directAnswer(intent, OrchestrationContext.anonymous());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ERROR);
        assertThat(result.getErrorCode()).isEqualTo("MODEL_DIRECT_ANSWER_MISSING");
        assertThat(result.getData().get("answer")).isNull();
        assertThat(((java.util.Map<?, ?>) result.getData().get("metadata")).get("answerSource"))
            .isEqualTo("MISSING_MODEL_DIRECT_ANSWER");
    }

    @Test
    void modelAuthoredAcknowledgementIsNotRewrittenOrLexicallyRejected() {
        var result = InformationGenerationResponseSupport.directAnswer(information("Okay."), OrchestrationContext.anonymous());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Okay.");
        assertThat(((java.util.Map<?, ?>) result.getData().get("metadata")).get("answerSource"))
            .isEqualTo("MODEL_DIRECT_ANSWER");
    }

    private Intent information(String answer) {
        return Intent.builder().type(IntentType.INFORMATION).intent("information")
            .requiresRetrieval(false).requiresGeneration(false).requiresTargetResolution(false)
            .needsAdvancedRAG(false).directAnswer(answer).build();
    }
}
