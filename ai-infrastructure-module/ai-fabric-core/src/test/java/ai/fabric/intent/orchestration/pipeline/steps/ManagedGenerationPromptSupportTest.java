package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.PromptBundleProperties;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.ManagedPromptDefaults;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedGenerationPromptSupportTest {

    @ParameterizedTest
    @MethodSource("managedTemplates")
    void shouldRenderConfiguredBreadthWithoutBlanketEvidenceOrNextStepBans(String version, String family, String name) {
        String answerGuidance = "ADVISORY: Offer clearly labeled general advice and reasonable inferences; suggest useful next steps.";
        String retrievalGuidance = "EXPLORATORY: Separate business evidence from general knowledge and unverified hypotheses. No web research is enabled.";

        String prompt = render(version, family, name, Map.of(
            "answerGenerationPrompt", answerGuidance,
            "retrievalPrompt", retrievalGuidance,
            "assistantUiPrompt", "Use a warm, consultative voice."));

        assertThat(prompt).contains(answerGuidance, retrievalGuidance, "Use a warm, consultative voice.",
                "Follow the managed answer-generation and retrieval guidance for response scope",
                "separately from verified business facts", "Never claim external research")
            .doesNotContain("{{", "Use only the relevant", "Use only the FACTS",
                "Do not infer quality, suitability", "Do not infer safety, reliability",
                "Do not recommend handoffs or next steps", "Do not add next-step or handoff sentences",
                "When evidence is missing, end after", "Do not append generic closers",
                ManagedPromptDefaults.ANSWER_GENERATION_PROMPT, ManagedPromptDefaults.RETRIEVAL_PROMPT);
    }

    @ParameterizedTest
    @MethodSource("managedTemplates")
    void shouldKeepOmittedManagedSlotsGrounded(String version, String family, String name) {
        String prompt = render(version, family, name, Map.of("assistantUiPrompt", "Compact presentation."));

        assertThat(prompt).contains(ManagedPromptDefaults.ANSWER_GENERATION_PROMPT,
                ManagedPromptDefaults.RETRIEVAL_PROMPT, "stop at the grounded conclusion",
                "Without explicit broader guidance, keep the answer grounded in the supplied evidence.")
            .doesNotContain("{{");
    }

    private String render(String version, String family, String name, Map<String, String> overlay) {
        PromptBundleProperties properties = new PromptBundleProperties();
        if (!"v1".equals(version)) properties.setOverlays(List.of(version));
        return ManagedGenerationPromptSupport.renderManagedGenerationPrompt(
            new PromptTemplateResolver(new ClasspathPromptTemplateStore(new DefaultResourceLoader()), properties),
            new PromptRenderer(), family, name,
            Map.of("query", "Compare the available options", "context", "Current authorized business evidence",
                "action_name", "read_options", "instruction", "Explain the options", "facts", "Authorized facts",
                "relational_query", "Authorized query"), overlay);
    }

    private static Stream<Arguments> managedTemplates() {
        return Stream.of("v1", "v1-default-optimized").flatMap(version -> Stream.of(
            Arguments.of(version, "rag/generation", "answer-managed"),
            Arguments.of(version, "rag/generation", "no-context-managed"),
            Arguments.of(version, "orchestration/post-action-generation", "user-generic-managed"),
            Arguments.of(version, "orchestration/post-action-generation", "user-relationship-query-managed")));
    }
}
