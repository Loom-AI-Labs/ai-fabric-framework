package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.information.ReadActionResolutionService;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InformationGenerationResponseSupportTest {

    @Test
    void shouldBuildDirectAnswerWithOrchestratorMetadata() {
        Intent intent = Intent.builder()
            .directAnswer("Sure, I can help.")
            .build();

        OrchestrationResult result = InformationGenerationResponseSupport.directAnswer(
            intent,
            OrchestrationContext.forUser("user-1")
        );

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Sure, I can help.");

        Map<?, ?> data = result.getData();
        assertThat(data.get("answer")).isEqualTo("Sure, I can help.");
        assertThat(data.get("documents")).isEqualTo(List.of());
        assertThat(data.get("requiresGeneration")).isEqualTo(false);
        assertThat(data.get("requiresRetrieval")).isEqualTo(false);

        Map<?, ?> metadata = (Map<?, ?>) data.get("metadata");
        assertThat(metadata.get("source")).isEqualTo("orchestrator");
        assertThat(metadata.get("userId")).isEqualTo("user-1");
        assertThat(metadata.get("authenticated")).isEqualTo(true);
    }

    @Test
    void shouldGenerateInformationWithoutRetrievalAndCopyMetadata() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("Generated answer")
                .model("gpt-test")
                .processingTimeMs(25L)
                .build()
        );
        RagResponseGenerationSupport generationSupport = newSupport(aiCoreService);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("requiresRetrieval", false);

        OrchestrationResult result = InformationGenerationResponseSupport.generationOnly(
            Intent.builder().requiresGeneration(true).build(),
            PipelineContext.from("explain warranty", OrchestrationContext.forUser("user-1")),
            "explain warranty",
            metadata,
            generationSupport
        );

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Generated answer");
        assertThat(result.getMetadata()).containsEntry("responseGenerationModel", "gpt-test");
        verify(aiCoreService).generateTextResponse(anyString(), eq(LlmPurpose.GENERATION));

        Map<?, ?> data = result.getData();
        assertThat(data.get("answer")).isEqualTo("Generated answer");
        assertThat(data.get("ragResponse")).isNull();
        assertThat(data.get("requiresGeneration")).isEqualTo(true);
        assertThat(data.get("requiresRetrieval")).isEqualTo(false);
        assertThat(data.get("metadata")).isEqualTo(Map.of("requiresRetrieval", false));
    }

    @Test
    void shouldGenerateAnswerFromReadActionEvidenceAndAttachDiagnostics() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("Order 42 is delayed.")
                .build()
        );
        RagResponseGenerationSupport generationSupport = newSupport(aiCoreService);
        ReadActionResolutionService.ResolutionOutcome resolutionOutcome =
            ReadActionResolutionService.ResolutionOutcome.answerFromActionsOnly(
                "READ ACTION EVIDENCE\n- order 42 status: delayed",
                List.of("orders"),
                List.of(),
                Map.of("attempted", true, "executedActionsCount", 1)
            );

        OrchestrationResult result = InformationGenerationResponseSupport.fromReadActionEvidence(
            Intent.builder().requiresGeneration(true).build(),
            PipelineContext.from("where is order 42", OrchestrationContext.forUser("user-1")),
            "where is order 42",
            Map.of("readActionResolutionForcedGeneration", true),
            resolutionOutcome,
            generationSupport
        );

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Order 42 is delayed.");
        assertThat(result.getMetadata()).containsKey("readActionResolution");

        Map<?, ?> data = result.getData();
        assertThat(data.get("answer")).isEqualTo("Order 42 is delayed.");
        assertThat(data.get("requiresRetrieval")).isEqualTo(false);
        assertThat(data.get("metadata")).isEqualTo(Map.of("readActionResolutionForcedGeneration", true));
        Map<?, ?> diagnostics = (Map<?, ?>) data.get("readActionResolution");
        assertThat(diagnostics.get("executedActionsCount")).isEqualTo(1);
    }

    private RagResponseGenerationSupport newSupport(AICoreService aiCoreService) {
        AIServiceConfig config = new AIServiceConfig();
        config.getFeatures().setEnableGeneration(true);
        return new RagResponseGenerationSupport(
            aiCoreService,
            config,
            new PromptTemplateResolver(
                new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
                new PromptBundleProperties()
            ),
            new PromptRenderer()
        );
    }
}
