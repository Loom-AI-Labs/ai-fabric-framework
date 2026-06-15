package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.ResponseSanitizationProperties;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.privacy.pii.PIIDetectionService;
import ai.fabric.security.ResponseSanitizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResponseSanitizationStepTest {

    @Test
    void shouldApplySanitizedMessageAndAnswerToResultMirrorsWithoutDroppingData() {
        @SuppressWarnings("unchecked")
        ObjectProvider<PIIDetectionService> piiProvider = mock(ObjectProvider.class);
        when(piiProvider.getIfAvailable()).thenReturn(null);

        ResponseSanitizationStep step = new ResponseSanitizationStep(
            new ResponseSanitizer(piiProvider, new ResponseSanitizationProperties())
        );
        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message("Live store data has no review records. Let me know if you need anything else.")
            .data(Map.of(
                "answer", "Live store data has no review records. Let me know if you need anything else.",
                "documents", List.of(Map.of("id", "doc-1"))
            ))
            .build();
        PipelineContext context = PipelineContext
            .from("reviews?", OrchestrationContext.forUser("user-1"))
            .toBuilder()
            .intentResult(result)
            .build();

        PipelineContext updated = step.process(context);

        assertThat(result.getMessage()).isEqualTo("Live store data has no review records.");
        assertThat(result.getData())
            .containsEntry("answer", "Live store data has no review records.")
            .containsKey("documents");
        assertThat(updated.getSanitizedPayload()).containsEntry("message", "Live store data has no review records.");
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitizedData = (Map<String, Object>) updated.getSanitizedPayload().get("data");
        assertThat(sanitizedData).containsEntry("answer", "Live store data has no review records.");
    }

    @Test
    void shouldSanitizeCompoundChildrenAndResultsDataBeforeReturning() {
        @SuppressWarnings("unchecked")
        ObjectProvider<PIIDetectionService> piiProvider = mock(ObjectProvider.class);
        when(piiProvider.getIfAvailable()).thenReturn(null);

        ResponseSanitizationStep step = new ResponseSanitizationStep(
            new ResponseSanitizer(piiProvider, new ResponseSanitizationProperties())
        );
        String childAnswer = """
            No return policy is available in live store data.

            If you need more information, please refer to the relevant section on our site or contact support for assistance.
            """.stripTrailing();
        OrchestrationResult child = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message(childAnswer)
            .data(Map.of("answer", childAnswer, "metadata", Map.of("raw", true)))
            .build();
        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message(childAnswer)
            .data(Map.of("answer", childAnswer, "results", List.of(child)))
            .children(List.of(child))
            .build();
        PipelineContext context = PipelineContext
            .from("policy?", OrchestrationContext.forUser("user-1"))
            .toBuilder()
            .intentResult(result)
            .build();

        PipelineContext updated = step.process(context);

        String expected = "No return policy is available in live store data.";
        assertThat(result.getMessage()).isEqualTo(expected);
        assertThat(child.getMessage()).isEqualTo(expected);
        assertThat(child.getData()).containsEntry("answer", expected);
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitizedData = (Map<String, Object>) updated.getSanitizedPayload().get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sanitizedResults = (List<Map<String, Object>>) sanitizedData.get("results");
        assertThat(sanitizedResults).singleElement()
            .satisfies(sanitizedChild -> {
                assertThat(sanitizedChild).containsEntry("message", expected);
                assertThat(sanitizedChild).doesNotContainKey("metadata");
                @SuppressWarnings("unchecked")
                Map<String, Object> childData = (Map<String, Object>) sanitizedChild.get("data");
                assertThat(childData).containsEntry("answer", expected);
                assertThat(childData).doesNotContainKey("metadata");
            });
    }
}
