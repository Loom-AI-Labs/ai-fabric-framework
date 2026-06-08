package com.ai.curated.defaultpack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultCuratedPackTest {

    @Test
    void shouldPackageGenericThinkerAndExecutorModes() throws Exception {
        String pack = readResource("ai-curated/packs/default.yml");
        assertThat(pack)
            .contains("default-mode: thinker")
            .contains("thinker:")
            .contains("planning-mode: ITERATIVE")
            .contains("rag-cooperation-mode: PARALLEL_ACTIONS_AND_RAG")
            .contains("force-grounding-eligible-read-action-post-generation: true")
            .contains("executor:")
            .contains("actions-enabled: true")
            .contains("planning-mode: SINGLE_PASS");
        assertThat(pack)
            .doesNotContain("commerce_")
            .doesNotContain("cart_assistant")
            .doesNotContain("resolver_assistant")
            .doesNotContain("commerce");
    }

    @Test
    void shouldPackageGenericGuardrailsWithoutCommerceCoupling() throws Exception {
        List<String> promptResources = List.of(
            "prompts/intent-extraction/multi-step/v1/classify.md",
            "prompts/intent-extraction/multi-step/v1/select-actions.md",
            "prompts/intent-extraction/multi-step/v1/fill-params.md",
            "prompts/orchestration/read-action-resolution/v1/user.md",
            "prompts/rag/generation/v1/answer.md",
            "prompts/rag/generation/v1/answer-managed.md",
            "prompts/rag/generation/v1/no-context.md",
            "prompts/rag/generation/v1/no-context-managed.md",
            "prompts/orchestration/post-action-generation/v1/user-generic.md",
            "prompts/orchestration/post-action-generation/v1/user-generic-managed.md",
            "prompts/orchestration/post-action-generation/v1/user-relationship-query.md",
            "prompts/orchestration/post-action-generation/v1/user-relationship-query-managed.md"
        );

        for (String promptResource : promptResources) {
            String prompt = readResource(promptResource);
            assertThat(prompt)
                .as(promptResource)
                .doesNotContain("private vertical brand")
                .doesNotContain("commerce")
                .doesNotContain("commerce")
                .doesNotContain("shopper");
        }

        assertThat(readResource("prompts/intent-extraction/multi-step/v1/classify.md"))
            .contains("assistant implementation, infrastructure, internal status")
            .contains("Never use directAnswer to discuss assistant implementation")
            .contains("Select or attach the specific item so I can answer about it.")
            .contains("supported knowledge, records, documents, summaries, comparisons, and approved actions");

        assertThat(readResource("prompts/rag/generation/v1/answer-managed.md"))
            .contains("Do not quote context section names, metadata keys, implementation labels")
            .contains("Do not treat retrieved search results as the current target")
            .contains("Translate failed lookups into user-facing missing live evidence");

        assertThat(readResource("prompts/orchestration/read-action-resolution/v1/user.md"))
            .contains("For private or user-owned resource reads")
            .contains("Do not turn display names, titles, labels, example ids, or generated summaries into executable identifiers");

        assertThat(readResource("prompts/orchestration/post-action-generation/v1/user-generic-managed.md"))
            .contains("Render monetary values in user-facing form")
            .doesNotContain("USD prices")
            .doesNotContain("785.95");
    }

    private static String readResource(String resourcePath) throws IOException {
        var classLoader = DefaultCuratedPackTest.class.getClassLoader();
        try (var stream = Objects.requireNonNull(classLoader.getResourceAsStream(resourcePath), resourcePath)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
