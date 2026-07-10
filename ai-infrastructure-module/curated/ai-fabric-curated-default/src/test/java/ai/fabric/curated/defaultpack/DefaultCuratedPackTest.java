package ai.fabric.curated.defaultpack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultCuratedPackTest {

    @Test
    void shouldPackageGenericThinkerAndExecutorModes() throws Exception {
        String pack = readResource("ai-curated/packs/default.yml");
        assertThat(pack)
            .contains("overlays:")
            .contains("v1-default-optimized")
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

    @Test
    void shouldPackageOptimizedDefaultOverlayPrompts() throws Exception {
        assertThat(readResource("prompts/intent-extraction/multi-step/v1-default-optimized/classify.md"))
            .contains("Recent conversation follow-ups")
            .contains("Backend-owned identifier rule")
            .contains("Policy/guidance document rule")
            .contains("up to the last 3 user/assistant messages")
            .contains("Do not ask the user for internal identifiers")
            .contains("not executable schemas");

        assertThat(readResource("prompts/intent-extraction/multi-step/v1-default-optimized/fill-params.md"))
            .contains("Do NOT fill backend-owned")
            .contains("Do NOT ask the user to provide backend-owned identifiers")
            .contains("A policy/guidance document may justify why an action is appropriate");

        assertThat(readResource("prompts/intent-extraction/compound/v1-default-optimized/system.md"))
            .contains("RECENT CONVERSATION FOLLOW-UPS")
            .contains("POLICY/GUIDANCE DOCUMENT RULE")
            .contains("Do not ask the user for internal identifiers");

        assertThat(readResource("prompts/rag/generation/v1-default-optimized/answer-managed.md"))
            .contains("live action facts, or current resource state")
            .contains("must not override current live facts")
            .contains("Do not infer missing live facts or missing action parameters");

        assertThat(readResource("prompts/orchestration/read-action-resolution/v1-default-optimized/user.md"))
            .contains("current-user/current-tenant/current-session resource reads")
            .contains("Never ask the user for internal action parameters");

        assertThat(readResource("prompts/orchestration/post-action-generation/v1-default-optimized/user-generic-managed.md"))
            .contains("Do not override FACTS with generic policy")
            .contains("do not infer the missing fact from guidance text");

        assertThat(readResource("prompts/behavior/analysis/v1-default-optimized/system.md"))
            .contains("Treat previous analysis as the baseline state")
            .contains("Later repeated negative events can reverse an earlier healthy baseline")
            .contains("Do not copy raw event JSON");
    }

    @Test
    void shouldPackageOnlyGenericOptimizedOverlayResources() throws Exception {
        for (String promptResource : optimizedOverlayPromptResources()) {
            String prompt = readResource(promptResource);
            assertThat(prompt)
                .as(promptResource)
                .isNotBlank()
                .doesNotContain("private vertical brand")
                .doesNotContain("commerce_")
                .doesNotContain("commerce")
                .doesNotContain("cart_assistant")
                .doesNotContain("resolver_assistant")
                .doesNotContain("shopper")
                .doesNotContain("catalog/search")
                .doesNotContain("product-search");
        }
    }

    @Test
    void shouldPackageOnlyGenericPromptResources() throws Exception {
        for (String promptResource : promptResources()) {
            String prompt = readResource(promptResource);
            assertThat(prompt)
                .as(promptResource)
                .isNotBlank()
                .doesNotContain("private vertical brand")
                .doesNotContain("commerce_")
                .doesNotContain("commerce")
                .doesNotContain("cart_assistant")
                .doesNotContain("resolver_assistant")
                .doesNotContain("shopper")
                .doesNotContain("catalog/search")
                .doesNotContain("product-search");
        }
    }

    private static String readResource(String resourcePath) throws IOException {
        var classLoader = DefaultCuratedPackTest.class.getClassLoader();
        try (var stream = Objects.requireNonNull(classLoader.getResourceAsStream(resourcePath), resourcePath)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> promptResources() throws IOException, URISyntaxException {
        var classLoader = DefaultCuratedPackTest.class.getClassLoader();
        var promptsUrl = Objects.requireNonNull(classLoader.getResource("prompts"), "prompts");
        Path promptsRoot = Path.of(promptsUrl.toURI());
        try (Stream<Path> paths = Files.walk(promptsRoot)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .map(promptsRoot::relativize)
                .map(path -> "prompts/" + path.toString().replace('\\', '/'))
                .sorted()
                .toList();
        }
    }

    private static List<String> optimizedOverlayPromptResources() throws IOException, URISyntaxException {
        return promptResources().stream()
            .filter(path -> path.contains("/v1-default-optimized/"))
            .toList();
    }
}
